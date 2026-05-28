package com.hotshare.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

object FileUtil {

    /** 默认接收目录 */
    fun getReceiveDir(context: Context): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "HotShare"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 获取应用内部存储缓存 */
    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "hotshare_uploads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 安全文件名（去除路径穿越） */
    fun safeFileName(name: String): String {
        return name.replace(File.separator, "_")
            .replace("..", "")
            .take(255)
    }

    /** 格式化文件大小 */
    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    /**
     * 通过 ContentResolver 将文件从 Content URI 复制到目标目录
     */
    suspend fun copyContentUriToDir(
        context: Context,
        uri: Uri,
        targetDir: File
    ): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val fileName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
            val safeName = safeFileName(fileName)
            val destFile = File(targetDir, safeName)

            // 同名自动重命名
            val finalFile = if (destFile.exists()) {
                val base = safeName.substringBeforeLast(".")
                val ext = safeName.substringAfterLast(".", "")
                var n = 1
                var candidate: File
                do {
                    candidate = File(targetDir, "${base}_($n).${ext}")
                    n++
                } while (candidate.exists())
                candidate
            } else {
                destFile
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(finalFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }

            android.util.Log.i("HotShare", "📤 文件已复制: ${finalFile.name} (${finalFile.length()} bytes)")
            finalFile.name
        } catch (e: Exception) {
            android.util.Log.e("HotShare", "复制文件失败: $uri", e)
            null
        }
    }

    /**
     * 从 Content URI 中提取原始文件名
     */
    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}
