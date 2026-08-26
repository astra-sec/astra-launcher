package org.astrasec.launcher.device

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.provider.Settings

data class SystemSettingTarget(
    val kind: SystemSettingKind,
    val label: String,
    val intent: Intent
)

enum class SystemSettingKind {
    WIFI,
    BLUETOOTH,
    SYSTEM
}

data class InputSource(
    val id: String,
    val label: String,
    val state: Int
)

class DeviceControlRepository(private val context: Context) {
    private val packageManager = context.packageManager

    fun availableSettings(): List<SystemSettingTarget> = listOf(
        SystemSettingTarget(
            SystemSettingKind.WIFI,
            context.getString(org.astrasec.launcher.R.string.wifi_settings),
            Intent(Settings.ACTION_WIFI_SETTINGS)
        ),
        SystemSettingTarget(
            SystemSettingKind.BLUETOOTH,
            context.getString(org.astrasec.launcher.R.string.bluetooth_settings),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        ),
        SystemSettingTarget(
            SystemSettingKind.SYSTEM,
            context.getString(org.astrasec.launcher.R.string.system_settings),
            Intent(Settings.ACTION_SETTINGS)
        )
    ).filter { target -> target.intent.resolveActivity(packageManager) != null }

    fun setting(kind: SystemSettingKind): SystemSettingTarget? =
        availableSettings().firstOrNull { target -> target.kind == kind }

    fun inputSources(): List<InputSource> {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return emptyList()
        return runCatching {
            manager.tvInputList
                .asSequence()
                .filter { info -> info.isPassthroughInput }
                .map { info ->
                    val label = runCatching { info.loadLabel(context).toString() }
                        .getOrNull()
                        .orEmpty()
                        .ifBlank { info.id.substringAfterLast('/').substringAfterLast('.') }
                    InputSource(
                        id = info.id,
                        label = label,
                        state = manager.getInputState(info.id)
                    )
                }
                .sortedBy { source -> source.label.lowercase() }
                .toList()
        }.getOrDefault(emptyList())
    }

    fun openSetting(target: SystemSettingTarget): Boolean = startActivity(target.intent)

    fun openInput(source: InputSource): Boolean {
        val uri = TvContract.buildChannelUriForPassthroughInput(source.id)
        return startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun startActivity(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
