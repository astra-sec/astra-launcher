package org.astrasec.launcher.device

/** Rank filters already matched against the TV URI; generic content viewers are not TV players. */
internal fun tvInputHandlerPriority(
    authorities: List<String>,
    mimeTypes: List<String>,
    resolvedMimeType: String?,
    systemApp: Boolean
): Int {
    if ("android.media.tv" in authorities) return 2
    if (resolvedMimeType == null || !resolvedMimeType.startsWith("vnd.android.cursor.")) return 0
    if (resolvedMimeType in mimeTypes) return 2
    // IntentFilter may store a partial type without its trailing /*.
    val base = resolvedMimeType.substringBefore('/')
    return if (systemApp && mimeTypes.any { it == base || it == "$base/*" }) 1 else 0
}
