package org.astrasec.launcher.apkupload

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import org.astrasec.launcher.LauncherActivity
import org.astrasec.launcher.R
import org.astrasec.launcher.ui.DefaultFocusOwner
import org.astrasec.launcher.ui.requestDirectionalFocus
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors

class ApkUploadFragment : Fragment(R.layout.fragment_apk_upload), DefaultFocusOwner {
    private data class UploadedApk(val file: File, val label: String, val packageName: String, val version: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var worker = Executors.newSingleThreadExecutor()
    private var server: ApkUploadServer? = null
    private var pending: UploadedApk? = null
    private var starting = false
    @Volatile private var active = false
    private val stopAfterTimeout = Runnable {
        stopReceiving()
        view?.findViewById<TextView>(R.id.uploadStatus)?.setText(R.string.apk_upload_expired)
    }

    private val unknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (view != null && synchronized(lock) { pending != null }) {
            if (requireContext().packageManager.canRequestPackageInstalls()) openInstaller()
            else showStatus(getString(R.string.apk_upload_permission_denied))
        }
    }

    private val installResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (view != null) showStatus(getString(R.string.apk_upload_installer_returned))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        active = true
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        // Restore before ActivityResult callbacks are delivered on START, including a return
        // from the unknown-sources screen after Android recreated this Activity.
        val inbox = File(requireContext().cacheDir, "apk-inbox")
        inbox.mkdirs()
        val restored = restorePending(requireContext(), inbox)
        synchronized(lock) { pending = restored }
        inbox.listFiles()?.filter { it.name != restored?.file?.name }?.forEach { it.delete() }
        view.findViewById<View>(R.id.backButton).setOnClickListener {
            (requireActivity() as LauncherActivity).showSettings()
        }
        view.findViewById<View>(R.id.toggleUpload).setOnClickListener {
            if (server == null) startReceiving() else stopReceiving()
        }
        view.findViewById<View>(R.id.installUpload).setOnClickListener { requestInstall() }
        view.findViewById<View>(R.id.discardUpload).setOnClickListener {
            synchronized(lock) {
                requireContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
                pending?.file?.delete()
                pending = null
            }
            renderPackage()
            showStatus("")
        }
        renderPackage()
    }

    private fun startReceiving() {
        if (starting) return
        starting = true
        val context = requireContext().applicationContext
        val pin = String.format(Locale.ROOT, "%06d", SecureRandom().nextInt(1_000_000))
        view?.findViewById<Button>(R.id.toggleUpload)?.isEnabled = false
        view?.findViewById<TextView>(R.id.uploadAddress)?.setText(R.string.apk_upload_starting)
        worker.execute {
            var candidate: ApkUploadServer? = null
            val result = runCatching {
                val addresses = localAddresses()
                check(addresses.isNotEmpty()) { context.getString(R.string.apk_upload_no_network) }
                val receiver = ApkUploadServer(File(context.cacheDir, "apk-inbox"), pin) { file ->
                    synchronized(lock) {
                        require(active) { "接收页面已关闭" }
                        require(pending == null) { context.getString(R.string.apk_upload_pending) }
                    }
                    @Suppress("DEPRECATION")
                    val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                        ?: throw IllegalArgumentException(context.getString(R.string.apk_upload_invalid))
                    val app = info.applicationInfo
                        ?: throw IllegalArgumentException(context.getString(R.string.apk_upload_invalid))
                    app.sourceDir = file.absolutePath
                    app.publicSourceDir = file.absolutePath
                    val label = runCatching { app.loadLabel(context.packageManager).toString() }
                        .getOrDefault(info.packageName)
                    synchronized(lock) {
                        require(active && pending == null) { context.getString(R.string.apk_upload_pending) }
                        val uploaded = UploadedApk(file, label, info.packageName, info.versionName.orEmpty())
                        // The upload runs off the main thread. Persist before acknowledging it,
                        // so a later process death does not orphan an accepted APK.
                        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                            .putString(KEY_FILE, uploaded.file.name)
                            .putString(KEY_LABEL, uploaded.label)
                            .putString(KEY_PACKAGE, uploaded.packageName)
                            .putString(KEY_VERSION, uploaded.version)
                            .commit()) { "无法保存待安装应用，请重试" }
                        pending = uploaded
                    }
                    mainHandler.post {
                        if (active) {
                            renderPackage()
                            showStatus(context.getString(R.string.apk_upload_received))
                        }
                    }
                    context.getString(R.string.apk_upload_received)
                }
                candidate = receiver
                val port = receiver.start()
                Pair(receiver, addresses.joinToString("\n") { "http://$it:$port" })
            }
            mainHandler.post {
                starting = false
                if (!active) {
                    candidate?.close()
                    return@post
                }
                view?.findViewById<Button>(R.id.toggleUpload)?.isEnabled = true
                result.fold(onSuccess = { (receiver, addresses) ->
                    server = receiver
                    view?.findViewById<TextView>(R.id.uploadAddress)?.text =
                        getString(R.string.apk_upload_address, addresses, pin)
                    view?.findViewById<Button>(R.id.toggleUpload)?.setText(R.string.apk_upload_stop)
                    mainHandler.postDelayed(stopAfterTimeout, 15 * 60 * 1000L)
                }, onFailure = {
                    candidate?.close()
                    view?.findViewById<TextView>(R.id.uploadAddress)?.setText(R.string.apk_upload_stopped)
                    showStatus(getString(R.string.apk_upload_failed, it.message.orEmpty()))
                })
            }
        }
    }

    private fun stopReceiving() {
        mainHandler.removeCallbacks(stopAfterTimeout)
        server?.close()
        server = null
        view?.findViewById<TextView>(R.id.uploadAddress)?.setText(R.string.apk_upload_stopped)
        view?.findViewById<Button>(R.id.toggleUpload)?.setText(R.string.apk_upload_start)
    }

    private fun renderPackage() {
        val root = view ?: return
        val apk = synchronized(lock) { pending }
        root.findViewById<TextView>(R.id.uploadPackageInfo).text = if (apk == null) {
            getString(R.string.apk_upload_empty)
        } else {
            getString(R.string.apk_upload_details, apk.label, apk.version, apk.packageName,
                String.format(Locale.ROOT, "%.1f", apk.file.length() / 1048576.0))
        }
        root.findViewById<Button>(R.id.installUpload).isEnabled = apk != null
        root.findViewById<Button>(R.id.discardUpload).isEnabled = apk != null
    }

    private fun requestInstall() {
        if (synchronized(lock) { pending == null }) return
        if (requireContext().packageManager.canRequestPackageInstalls()) {
            openInstaller()
        } else {
            showStatus(getString(R.string.apk_upload_permission))
            runCatching {
                unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${requireContext().packageName}")))
            }.onFailure { showStatus(getString(R.string.apk_upload_failed, it.message.orEmpty())) }
        }
    }

    private fun openInstaller() {
        val apk = synchronized(lock) { pending } ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.apkfiles", apk.file)
            // The system owns installation and its confirmation UI. Only this content URI is shared.
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            installResult.launch(intent)
            showStatus(getString(R.string.apk_upload_installer_opened))
        }.onFailure { showStatus(getString(R.string.apk_upload_failed, it.message.orEmpty())) }
    }

    private fun showStatus(text: String) {
        view?.findViewById<TextView>(R.id.uploadStatus)?.text = text
    }

    override fun requestDefaultFocus(): Boolean =
        view?.findViewById<View>(R.id.toggleUpload)?.requestDirectionalFocus() == true

    override fun onDestroyView() {
        active = false
        stopReceiving()
        synchronized(lock) {
            // Keep completed uploads across navigation, external authorization, and process
            // recreation. Only the explicit discard action removes their file and metadata.
            pending = null
        }
        worker.shutdownNow()
        super.onDestroyView()
    }

    private fun restorePending(context: Context, inbox: File): UploadedApk? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val name = preferences.getString(KEY_FILE, null)
        val packageName = preferences.getString(KEY_PACKAGE, null)
        val file = if (!name.isNullOrBlank() && name == File(name).name && name.endsWith(".apk")) {
            runCatching { File(inbox, name).canonicalFile }.getOrNull()
                ?.takeIf { it.parentFile == inbox.canonicalFile && it.isFile && it.length() > 0 }
        } else null
        if (file == null || packageName.isNullOrBlank()) {
            preferences.edit().clear().apply()
            return null
        }
        return UploadedApk(
            file,
            preferences.getString(KEY_LABEL, packageName).orEmpty().ifBlank { packageName },
            packageName,
            preferences.getString(KEY_VERSION, "").orEmpty()
        )
    }

    private fun localAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
        .mapNotNull { it.hostAddress }
        .distinct()

    private companion object {
        const val PREFERENCES = "apk_upload_pending"
        const val KEY_FILE = "file"
        const val KEY_LABEL = "label"
        const val KEY_PACKAGE = "package"
        const val KEY_VERSION = "version"
    }
}
