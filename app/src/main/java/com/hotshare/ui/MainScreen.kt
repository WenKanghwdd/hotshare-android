package com.hotshare.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hotshare.service.HotspotService
import com.hotshare.util.FileUtil
import com.hotshare.util.NetworkUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(HotspotService.isRunning) }
    var serverIp by remember { mutableStateOf(HotspotService.serverIp) }
    var port by remember { mutableStateOf(HotspotService.port) }

    // 注册广播接收服务状态
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.getStringExtra("status")) {
                    "started" -> {
                        isRunning = true
                        serverIp = intent.getStringExtra("ip") ?: ""
                        port = intent.getIntExtra("port", 8080)
                    }
                    "stopped" -> {
                        isRunning = false
                    }
                    "error" -> {
                        isRunning = false
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter("com.hotshare.SERVICE_STATUS"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    // 轮询状态
    LaunchedEffect(Unit) {
        while (true) {
            isRunning = HotspotService.isRunning
            serverIp = HotspotService.serverIp
            port = HotspotService.port
            kotlinx.coroutines.delay(1000)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4FC3F7),
            secondary = Color(0xFF81C784),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))

                // 标题
                Text(
                    text = "🔥 HotShare",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "热点直连 · 零公网 · 高速传输",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // 状态卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 状态指示灯
                        val statusColor = if (isRunning) Color(0xFF4CAF50) else Color(0xFF757575)
                        val statusText = if (isRunning) "服务运行中" else "服务未启动"

                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(statusColor, RoundedCornerShape(6.dp))
                        )
                        Text(
                            text = statusText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = statusColor,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        if (isRunning) {
                            Spacer(modifier = Modifier.height(16.dp))

                            // IP 地址
                            Text(
                                text = "访问地址",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                            Text(
                                text = "http://$serverIp:$port",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // 操作提示
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF2D2D2D)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        "📋 操作步骤",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "1️⃣ 确保 Mac 已连接手机热点 Wi-Fi\n" +
                                        "2️⃣ 打开浏览器访问上方地址\n" +
                                        "3️⃣ 拖拽文件到网页上传/下载",
                                        fontSize = 13.sp,
                                        color = Color.LightGray,
                                        lineHeight = 22.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 控制按钮
                Button(
                    onClick = {
                        if (isRunning) {
                            context.stopService(HotspotService.startIntent(context))
                        } else {
                            ContextCompat.startForegroundService(
                                context,
                                HotspotService.startIntent(context)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) Color(0xFFEF5350) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (isRunning) "⏹ 停止服务" else "▶ 启动服务",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 文件发送到 Mac
                if (isRunning) {
                    val scope = rememberCoroutineScope()
                    var copiedFiles by remember { mutableStateOf(listOf<String>()) }

                    // 文件选择器
                    val filePickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenMultipleDocuments()
                    ) { uris: List<Uri> ->
                        scope.launch {
                            val dir = FileUtil.getReceiveDir(context)
                            val newFiles = mutableListOf<String>()
                            for (uri in uris) {
                                val copied = copyToServerDir(context, uri, dir)
                                if (copied != null) newFiles.add(copied)
                            }
                            copiedFiles = newFiles
                            if (newFiles.isNotEmpty()) {
                                Toast.makeText(context, "已添加 ${newFiles.size} 个文件", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 文件列表（最近复制的先显示）
                    val recentlyCopied = remember { mutableStateOf(listOf<String>()) }
                    Button(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7B4FC3)
                        )
                    ) {
                        Text("📤 选择文件发送到 Mac", fontSize = 14.sp)
                    }

                    // 显示目录下已有的文件
                    LaunchedEffect(isRunning) {
                        while (isRunning) {
                            val dir = FileUtil.getReceiveDir(context)
                            val files = dir.listFiles()
                                ?.filter { it.isFile }
                                ?.sortedByDescending { it.lastModified() }
                                ?.take(10)
                                ?.map { "${it.name}  (${FileUtil.formatSize(it.length())})" }
                                ?: emptyList()
                            recentlyCopied.value = files
                            kotlinx.coroutines.delay(3000)
                        }
                    }

                    if (recentlyCopied.value.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF2D2D2D)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "📄 最近文件",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.LightGray
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                recentlyCopied.value.forEach { fileInfo ->
                                    Text(
                                        fileInfo,
                                        fontSize = 11.sp,
                                        color = Color.Gray,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    val dir = FileUtil.getReceiveDir(context)
                    Text(
                        text = "📁 文件保存到: ${dir.absolutePath}",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 底部版本信息
                Text(
                    text = "HotShare v1.0.0",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )
                Text(
                    text = "局域网直连 · 零蜂窝流量",
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ====== 工具函数 ======

/**
 * 将用户选择的文件通过 ContentResolver 拷贝到服务器目录
 * @return 复制后的文件名，失败返回 null
 */
private suspend fun copyToServerDir(
    context: Context,
    uri: Uri,
    targetDir: File
): String? = withContext(Dispatchers.IO) {
    try {
        val fileName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
        val safeName = FileUtil.safeFileName(fileName)
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
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}
