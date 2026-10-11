package org.astrasec.launcher.activitytools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager

class ActivityToolsRepository(context: Context) {
    private val context = context.applicationContext
    private val packageManager = context.packageManager
    private val preferences = context.getSharedPreferences("activity_tools", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    fun loadActivities(): List<ActivityTarget> = packageManager.getInstalledPackages(
        PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
    ).flatMap { pkg ->
        pkg.activities.orEmpty().mapNotNull { info -> runCatching { target(info) }.getOrNull() }
    }.distinctBy { it.component }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ActivityTarget::appLabel)
            .thenBy(String.CASE_INSENSITIVE_ORDER, ActivityTarget::label)
            .thenBy { it.component })

    @Suppress("DEPRECATION")
    fun findActivity(component: Pair<String, String>): Result<ActivityTarget> = runCatching {
        target(packageManager.getActivityInfo(
            ComponentName(component.first, component.second),
            PackageManager.MATCH_DISABLED_COMPONENTS
        ))
    }

    fun favorites(): Set<String> = preferences.getStringSet("favorites", emptySet()).orEmpty().toSet()

    fun toggleFavorite(target: ActivityTarget): Boolean {
        val favorites = favorites().toMutableSet()
        val added = if (target.component in favorites) {
            favorites.remove(target.component)
            false
        } else {
            favorites.add(target.component)
            true
        }
        preferences.edit().putStringSet("favorites", favorites).apply()
        return added
    }

    fun launch(target: ActivityTarget): Result<Unit> = runCatching {
        // Recheck metadata in case the package changed while its details were open.
        val latest = findActivity(target.packageName to target.className).getOrThrow()
        check(latest.canLaunch) { "Activity is disabled, not exported, or requires an unavailable permission" }
        context.startActivity(Intent()
            .setComponent(ComponentName(latest.packageName, latest.className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun target(info: ActivityInfo): ActivityTarget {
        val component = ComponentName(info.packageName, info.name)
        val permission = info.permission?.takeIf { it.isNotBlank() }
        val appSetting = packageManager.getApplicationEnabledSetting(info.packageName)
        val activitySetting = packageManager.getComponentEnabledSetting(component)
        val appEnabled = effectiveEnabled(info.applicationInfo.enabled, appSetting)
        val componentEnabled = effectiveEnabled(info.enabled, activitySetting)
        return ActivityTarget(
            packageName = info.packageName,
            className = info.name,
            appLabel = info.applicationInfo.loadLabel(packageManager).toString(),
            label = info.loadLabel(packageManager).toString().ifBlank { info.name },
            exported = info.exported,
            enabled = appEnabled && componentEnabled,
            permission = permission,
            permissionGranted = permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    private fun effectiveEnabled(manifestEnabled: Boolean, setting: Int): Boolean = when (setting) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }
}
