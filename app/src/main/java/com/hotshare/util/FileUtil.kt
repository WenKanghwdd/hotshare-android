package com.hotshare.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
}
