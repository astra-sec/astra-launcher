package org.astrasec.launcher.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.astrasec.launcher.LauncherActivity
import org.astrasec.launcher.R
import org.astrasec.launcher.dailyimage.DailyImageRepository
import org.astrasec.launcher.device.DeviceControlRepository
import org.astrasec.launcher.device.SystemSettingKind

class SettingsFragment : Fragment(R.layout.fragment_settings), DefaultFocusOwner {
    private var backButton: View? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as LauncherActivity
        backButton = view.findViewById(R.id.backButton)
        backButton?.setOnClickListener { activity.showHome() }
        bindSystemSettings(view)
        bindDailyImage(view)
        bindSource(view, R.id.openMeteoSource, "https://open-meteo.com/en/license")
        bindSource(view, R.id.wttrSource, "https://github.com/chubin/wttr.in")
        bindSource(view, R.id.ipWhoisSource, "https://ipwhois.io/documentation")
    }

    private fun bindDailyImage(root: View) {
        val snapshot = DailyImageRepository(requireContext().applicationContext).cachedImage()
        val info = root.findViewById<android.widget.TextView>(R.id.dailyImageInfo)
        val source = root.findViewById<View>(R.id.dailyImageSource)
        if (snapshot == null) {
            info.setText(R.string.daily_image_not_cached)
            source.visibility = View.GONE
            return
        }
        val metadata = snapshot.metadata
        info.text = getString(
            R.string.daily_image_info_format,
            metadata.title,
            metadata.provider.displayName,
            metadata.credit.ifBlank { getString(R.string.daily_image_credit_unknown) },
            metadata.license.ifBlank { getString(R.string.daily_image_license_unknown) }
        )
        source.visibility = if (metadata.sourceUrl.isBlank()) View.GONE else View.VISIBLE
        source.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(metadata.sourceUrl))) }
        }
    }

    private fun bindSystemSettings(root: View) {
        val repository = DeviceControlRepository(requireContext().applicationContext)
        val target = repository.setting(SystemSettingKind.SYSTEM)
        root.findViewById<View>(R.id.systemSettingsButton).apply {
            visibility = if (target == null) View.GONE else View.VISIBLE
            setOnClickListener {
                if (target != null && !repository.openSetting(target)) {
                    Toast.makeText(context, R.string.settings_launch_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bindSource(root: View, viewId: Int, url: String) {
        root.findViewById<View>(viewId).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    override fun requestDefaultFocus(): Boolean = backButton?.requestDirectionalFocus() == true

    override fun onDestroyView() {
        backButton = null
        super.onDestroyView()
    }
}
