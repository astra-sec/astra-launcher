package org.astrasec.launcher.device

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        // Some TV players expose MAIN with a TV URI but do not handle VIEW.
        val actions = listOf(Intent.ACTION_VIEW, Intent.ACTION_MAIN)
        val candidates = actions.flatMap { action ->
            val request = Intent(action).setDataAndType(uri, mimeType)
            runCatching {
                packageManager.queryIntentActivities(
                    request,
                    PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
                ).mapNotNull { result ->
                    val info = result.activityInfo ?: return@mapNotNull null
                    val filter = result.filter ?: return@mapNotNull null
                    if (!info.exported || !info.enabled || !info.applicationInfo.enabled ||
                        (info.permission != null && context.checkSelfPermission(info.permission) !=
                            PackageManager.PERMISSION_GRANTED)
                    ) return@mapNotNull null
                    val systemApp = info.applicationInfo.flags and
                        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    val priority = tvInputHandlerPriority(
                        filter.authoritiesIterator()?.asSequence()?.map { it.host }?.toList().orEmpty(),
                        filter.typesIterator()?.asSequence()?.toList().orEmpty(),
                        mimeType,
                        systemApp
                    )
                    if (priority == 0) return@mapNotNull null
                    priority to Intent(request).setComponent(ComponentName(info.packageName, info.name))
                }.distinctBy { it.second.component }
            }.getOrDefault(emptyList())
        }
        // A TV-specific MAIN handler is better than a generic system cursor VIEW handler.
        for (priority in listOf(2, 1)) {
            for (action in actions) {
                val players = candidates.filter { it.first == priority && it.second.action == action }
                    .map { it.second }
                if (players.isEmpty()) continue
                val intent = if (players.size == 1) players.single() else {
                    Intent.createChooser(players.first(), context.getString(org.astrasec.launcher.R.string.input_player_picker))
                        .putExtra(Intent.EXTRA_INITIAL_INTENTS, players.drop(1).toTypedArray())
                }
                if (startActivity(intent)) return true
            }
        }
        return false
    }

    private fun startActivity(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
