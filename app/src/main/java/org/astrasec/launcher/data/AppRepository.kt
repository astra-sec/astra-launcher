package org.astrasec.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import org.astrasec.launcher.model.LaunchableApp
import java.text.Collator
import java.util.Locale

class AppRepository(private val context: Context) {
    private val packageManager = context.packageManager

    fun loadLaunchableApps(): List<LaunchableApp> {
        val resolveInfos = buildList {
            addAll(queryCategory(Intent.CATEGORY_LAUNCHER))
            addAll(queryCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        }

        val collator = Collator.getInstance(Locale.getDefault())
        return resolveInfos
            .asSequence()
            .filterNot { resolveInfo -> resolveInfo.activityInfo.packageName == context.packageName }
            .distinctBy { resolveInfo ->
                ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name)
            }
            .map { resolveInfo ->
                LaunchableApp(
                    componentName = ComponentName(
                        resolveInfo.activityInfo.packageName,
                        resolveInfo.activityInfo.name
                    ),
                    label = resolveInfo.loadLabel(packageManager)?.toString().orEmpty()
                        .ifBlank { resolveInfo.activityInfo.packageName },
                    icon = resolveInfo.loadIcon(packageManager)
                )
            }
            .sortedWith { left, right -> collator.compare(left.label, right.label) }
            .toList()
    }

    fun launch(app: LaunchableApp): Result<Unit> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.componentName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    }

    fun openAppInfo(app: LaunchableApp): Result<Unit> = runCatching {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${app.componentName.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun queryCategory(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return packageManager.queryIntentActivities(intent, 0)
    }
}

