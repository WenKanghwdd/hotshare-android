package com.hotshare.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hotshare.R
import com.hotshare.server.FileServer
import com.hotshare.util.FileUtil
import com.hotshare.util.NetworkUtil
import java.io.File

/**
 * HotShare 前台服务 — 管理 HTTP 服务器生命周期
 *
 * 职责：
 * 1. 启动/关闭 NanoHTTPD 文件服务器
 * 2. 持有前台通知保持后台存活
 * 3. 暴露启动/停止/状态查询接口
 */
class HotspotService : Service() {

    companion object {
        private const val TAG = "HotspotService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hotshare_server"
        private const val DEFAULT_PORT = 8080

        /** 当前服务是否运行中 */
        var isRunning = false
            private set

        /** 服务端口 */
        var port = DEFAULT_PORT
            private set

        /** 服务器 IP */
        var serverIp = ""
            private set

        /** 单例引用，方便 UI 读取状态 */
        var instance: HotspotService? = null
            private set

        /** 启动服务的 Intent */
        fun startIntent(context: Context, port: Int = DEFAULT_PORT): Intent {
            return Intent(context, HotspotService::class.java).apply {
                putExtra("port", port)
            }
        }
    }

    private var fileServer: FileServer? = null
    private var storageDir: File? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "服务启动...")

        // 解析端口
        port = intent?.getIntExtra("port", DEFAULT_PORT) ?: DEFAULT_PORT

        // 获取存储目录
        storageDir = FileUtil.getReceiveDir(this)
        Log.i(TAG, "接收目录: ${storageDir?.absolutePath}")

        // 启动前台通知
        startForeground(NOTIFICATION_ID, createNotification())

        try {
            // 启动 HTTP 服务器
            startHttpServer()
            isRunning = true
            serverIp = NetworkUtil.getWlanIpAddress(this)

            // 通知 UI 更新
            broadcastStatus("started")

            Log.i(TAG, "✅ HotShare 服务已就绪: http://$serverIp:$port")
            Log.i(TAG, "📁 文件保存到: ${storageDir?.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 服务启动失败", e)
            isRunning = false
            broadcastStatus("error", e.message)
        }

        // 被杀后自动重启
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "服务停止...")
        instance = null
        isRunning = false
        stopHttpServer()
        broadcastStatus("stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========== 内部方法 ==========

    private fun startHttpServer() {
        stopHttpServer()
        val dir = storageDir ?: FileUtil.getReceiveDir(this)
        fileServer = FileServer(this, port, dir)
        fileServer?.start()
    }

    private fun stopHttpServer() {
        try {
            fileServer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止服务器异常", e)
        }
        fileServer = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HotShare 文件传输",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 HotShare 后台运行"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HotShare 运行中")
            .setContentText("端口: $port | 文件保存到: 下载/HotShare")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun broadcastStatus(status: String, message: String? = null) {
        val intent = Intent("com.hotshare.SERVICE_STATUS").apply {
            putExtra("status", status)
            putExtra("port", port)
            putExtra("ip", serverIp)
            message?.let { putExtra("message", it) }
        }
        sendBroadcast(intent)
    }

    // ========== 静态 API ==========

    /** 获取访问地址 */
    fun getAccessUrl(): String {
        return "http://$serverIp:$port"
    }
}
