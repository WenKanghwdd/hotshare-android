package com.hotshare.server

object MimeTypes {
    private val mimeMap = mapOf(
        "html" to "text/html; charset=utf-8",
        "css"  to "text/css; charset=utf-8",
        "js"   to "application/javascript; charset=utf-8",
        "json" to "application/json",
        "png"  to "image/png",
        "jpg"  to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif"  to "image/gif",
        "webp" to "image/webp",
        "svg"  to "image/svg+xml",
        "ico"  to "image/x-icon",
        "mp4"  to "video/mp4",
        "mov"  to "video/quicktime",
        "mkv"  to "video/x-matroska",
        "mp3"  to "audio/mpeg",
        "wav"  to "audio/wav",
        "aac"  to "audio/aac",
        "pdf"  to "application/pdf",
        "zip"  to "application/zip",
        "rar"  to "application/vnd.rar",
        "gz"   to "application/gzip",
        "doc"  to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls"  to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt"  to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "txt"  to "text/plain; charset=utf-8",
        "apk"  to "application/vnd.android.package-archive",
    )

    fun guess(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return mimeMap[ext] ?: "application/octet-stream"
    }
}
