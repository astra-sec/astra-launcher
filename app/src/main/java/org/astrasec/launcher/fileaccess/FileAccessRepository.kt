package org.astrasec.launcher.fileaccess

import android.content.Context
import android.content.Intent
import android.net.Uri

class FileAccessRepository(private val context: Context) {
    private val packageManager = context.packageManager

    fun isAvailable(): Boolean =
        fileManagerIntent().resolveActivity(packageManager) != null || canPickDocument()

    fun openFileManager(): Boolean {
        val intent = fileManagerIntent()
        if (intent.resolveActivity(packageManager) == null) return false
        return startActivity(intent)
    }

    fun canPickDocument(): Boolean =
        documentPickerIntent().resolveActivity(packageManager) != null

    fun openSelectedDocument(uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri) ?: ANY_MIME_TYPE
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return startActivity(intent)
    }

    private fun fileManagerIntent(): Intent = Intent.makeMainSelectorActivity(
        Intent.ACTION_MAIN,
        CATEGORY_APP_FILES
    )

    private fun documentPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(ANY_MIME_TYPE)

    private fun startActivity(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    companion object {
        const val ANY_MIME_TYPE = "*/*"

        // Intent.CATEGORY_APP_FILES was added in API 29. The category string can still be
        // capability-tested safely on older releases without checking a device model.
        private const val CATEGORY_APP_FILES = "android.intent.category.APP_FILES"
    }
}
