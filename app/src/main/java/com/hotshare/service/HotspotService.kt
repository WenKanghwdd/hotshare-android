package com.hotshare.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
 * HotShare 前台服务 — 管理 HTTP 服务器生命周期 + mDNS 注册
 *
 * 职责：
 * 1. 启动/关闭 NanoHTTPD 文件服务器
 * 2. 通过 NSD 注册 mDNS 服务（Mac 可发现 hotshare.local）
 * 3. 持有前台通知保持后台存活
 * 4. 暴露启动/停止/状态查询接口
 */
class HotspotService : Service() {

    companion object {
        private const val TAG = "HotspotService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hotshare_server"
        private const val DEFAULT_PORT = 8080
        private const val NSD_SERVICE_NAME = "HotShare"
        private const val NSD_SERVICE_TYPE = "_http._tcp."

        /** 当前服务是否运行中 */
        var isRunning = false
            private set

        /** 服务端口 */
        var port = DEFAULT_PORT
            private set

        /** 服务器 IP */
        var serverIp = ""
            private set

        /** 单例引用 */
        var instance: HotspotService? = null
            private set

        fun startIntent(context: Context, port: Int = DEFAULT_PORT): Intent {
            return Intent(context, HotspotService::class.java).apply {
                putExtra("port", port)
            }
        }
    }

    private var fileServer: FileServer? = null
    private var storageDir: File? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistered = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "服务启动...")
        port = intent?.getIntExtra("port", DEFAULT_PORT) ?: DEFAULT_PORT
        storageDir = FileUtil.getReceiveDir(this)
        Log.i(TAG, "接收目录: ${storageDir?.absolutePath}")

        startForeground(NOTIFICATION_ID, createNotification())

        try {
            startHttpServer()
            registerNsdService()
            isRunning = true
            serverIp = NetworkUtil.getWlanIpAddress(this)
            broadcastStatus("started")
            Log.i(TAG, "✅ HotShare 已就绪: http://$serverIp:$port")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 服务启动失败", e)
            isRunning = false
            broadcastStatus("error", e.message)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "服务停止...")
        instance = null
        isRunning = false
        unregisterNsdService()
        stopHttpServer()
        broadcastStatus("stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========== HTTP 服务器 ==========

    private fun startHttpServer() {
        stopHttpServer()
        val dir = storageDir ?: FileUtil.getReceiveDir(this)
        fileServer = FileServer(this, port, dir)
        fileServer?.start()
    }

    private fun stopHttpServer() {
        try { fileServer?.stop() } catch (e: Exception) { Log.w(TAG, "停止服务器异常", e) }
        fileServer = null
    }

    // ========== mDNS / NSD 注册 ==========

    /**
     * 注册 mDNS 服务，使 Mac 可通过 "HotShare.local" 发现
     */
    private fun registerNsdService() {
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = NSD_SERVICE_NAME
                serviceType = NSD_SERVICE_TYPE
                port = HotspotService.port
                // 可选: 添加 TXT record 包含额外信息
                // setAttribute("version", "1.1.0")
            }

            nsdManager?.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo?) {
                        nsdRegistered = true
                        val registeredName = info?.serviceName ?: "HotShare"
                        Log.i(TAG, "✅ mDNS 注册成功: $registeredName ($NSD_SERVICE_TYPE)")
                    }

                    override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                        nsdRegistered = false
                        Log.w(TAG, "⚠️ mDNS 注册失败 (errorCode=$errorCode)")
                    }

                    override fun onServiceUnregistered(info: NsdServiceInfo?) {
                        nsdRegistered = false
                        Log.i(TAG, "mDNS 已注销")
                    }

                    override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                        Log.w(TAG, "mDNS 注销失败 (errorCode=$errorCode)")
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ mDNS 注册异常（可能设备不支持）", e)
        }
    }

    private fun unregisterNsdService() {
        if (nsdRegistered) {
            try {
                nsdManager?.unregisterService(null) // null listener is ok
            } catch (e: Exception) {
                Log.w(TAG, "mDNS 注销异常", e)
            }
            nsdRegistered = false
        }
    }

    // ========== 通知 ==========

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

    // ========== 广播 ==========

    private fun broadcastStatus(status: String, message: String? = null) {
        val intent = Intent("com.hotshare.SERVICE_STATUS").apply {
            putExtra("status", status)
            putExtra("port", port)
            putExtra("ip", serverIp)
            message?.let { putExtra("message", it) }
        }
        sendBroadcast(intent)
    }

    fun getAccessUrl(): String = "http://$serverIp:$port"

    /** 获取活跃连接数和客户端列表 */
    fun getConnectionInfo(): Pair<Int, List<String>> {
        val count = fileServer?.getConnectionCount() ?: 0
        val clients = fileServer?.getActiveConnections() ?: emptyList()
        return Pair(count, clients)
    }
}
