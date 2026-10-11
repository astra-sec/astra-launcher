package org.astrasec.launcher.activitytools

data class ActivityTarget(
    val packageName: String,
    val className: String,
    val appLabel: String,
    val label: String,
    val exported: Boolean,
    val enabled: Boolean,
    val permission: String?,
    val permissionGranted: Boolean
) {
    val component: String get() = "$packageName/$className"
    val canLaunch: Boolean get() = exported && enabled && permissionGranted

    fun matches(query: String): Boolean = query.trim().split(WHITESPACE)
        .filter { it.isNotEmpty() }
        .all { term ->
            listOf(appLabel, label, packageName, className).any { it.contains(term, ignoreCase = true) }
        }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/** Accept Android's flattened component notation, including relative class names. */
fun parseActivityComponent(value: String): Pair<String, String>? {
    val parts = value.trim().split('/')
    if (parts.size != 2) return null
    val packageName = parts[0]
    val className = parts[1]
    val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    val classPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    if (!packagePattern.matches(packageName)) return null
    val expanded = if (className.startsWith('.')) packageName + className else className
    if (!classPattern.matches(expanded)) return null
    return packageName to expanded
}
