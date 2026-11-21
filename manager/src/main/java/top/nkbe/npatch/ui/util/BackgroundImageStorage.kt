package top.nkbe.npatch.ui.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object BackgroundImageStorage {
    private const val DIRECTORY_NAME = "backgrounds"
    private const val FILE_NAME = "background_image"

    suspend fun persistFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val targetFile = file(context)
        targetFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Unable to open background image input stream")
        targetFile.absolutePath
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        val targetFile = file(context)
        if (targetFile.exists()) {
            targetFile.delete()
        }
    }

    fun file(context: Context): File = context.filesDir.resolve(DIRECTORY_NAME).resolve(FILE_NAME)
}
