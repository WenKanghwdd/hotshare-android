import Cocoa
import WebKit

class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    var window: NSWindow?
    var webView: WKWebView?
    var discoveryService: DiscoveryService?
    var macServer: MacServer?
    var statusItem: NSStatusItem?
    var activityIndicator: NSProgressIndicator?

    let macPort: UInt16 = 8900

    func applicationDidFinishLaunching(_ notification: Notification) {
        // 1. 启动本地 Mac 文件服务
        startMacServer()

        // 2. 创建主窗口
        createMainWindow()

        // 3. 开始发现手机
        startDiscovery()

        // 4. 添加菜单栏图标
        setupMenuBar()
    }

    // MARK: - 本地服务

    func startMacServer() {
        macServer = MacServer(port: macPort)
        macServer?.start()
        NSLog("📡 Mac 服务器已启动: http://localhost:\(macPort)")
    }

    // MARK: - 窗口

    func createMainWindow() {
        let screenRect = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 800, height: 600)
        let windowRect = NSRect(
            x: screenRect.minX + (screenRect.width - 900) / 2,
            y: screenRect.minY + (screenRect.height - 640) / 2,
            width: 900,
            height: 640
        )

        window = NSWindow(
            contentRect: windowRect,
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window?.title = "HotShare Mac"
        window?.minSize = NSSize(width: 700, height: 480)

        // 内容视图
        let contentView = MainViewController()
        contentView.macServer = macServer
        contentView.macPort = macPort
        contentView.discoveryCallback = { [weak self] service in
            self?.onPhoneDiscovered(service)
        }

        window?.contentViewController = contentView
        window?.center()
        window?.makeKeyAndOrderFront(nil)
        window?.delegate = self

        // 确保 App 激活
        NSApp.activate(ignoringOtherApps: true)
    }

    // MARK: - 发现手机服务

    func startDiscovery() {
        discoveryService = DiscoveryService()
        discoveryService?.onServiceFound = { [weak self] service in
            self?.onPhoneDiscovered(service)
        }
        discoveryService?.startBrowsing()
    }

    func onPhoneDiscovered(_ service: NetService) {
        guard let ip = service.ipAddress else { return }
        let url = "http://\(ip):\(service.port)"
        NSLog("📱 发现手机服务: \(url)")

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if let vc = self.window?.contentViewController as? MainViewController {
                vc.onPhoneDiscovered(url: url, hostName: service.name)
            }
        }
    }

    // MARK: - 菜单栏

    func setupMenuBar() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = statusItem?.button {
            button.title = "🔥"
            button.action = #selector(togglePopover)
            button.target = self
        }
    }

    @objc func togglePopover() {
        if window?.isVisible == true {
            window?.orderOut(nil)
        } else {
            window?.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    // MARK: - App 生命周期

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return false
    }

    func applicationWillTerminate(_ notification: Notification) {
        macServer?.stop()
        discoveryService?.stopBrowsing()
    }
}

// MARK: - 辅助方法

extension NetService {
    var ipAddress: String? {
        return addresses?.compactMap { data -> String? in
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            data.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) in
                let sockaddr = ptr.bindMemory(to: sockaddr.self)
                if let baseAddress = sockaddr.baseAddress {
                    getnameinfo(baseAddress, socklen_t(data.count),
                                &hostname, socklen_t(hostname.count),
                                nil, 0, NI_NUMERICHOST)
                }
            }
            let ip = String(cString: hostname)
            return ip.contains(".") ? ip : nil
        }.first
    }
}
