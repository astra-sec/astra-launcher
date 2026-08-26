package org.astrasec.launcher.model

import android.content.ComponentName
import android.graphics.drawable.Drawable

data class LaunchableApp(
    val componentName: ComponentName,
    val label: String,
    val icon: Drawable
) {
    val stableId: Long = componentName.flattenToString().hashCode().toLong()
}

