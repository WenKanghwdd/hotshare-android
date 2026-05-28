import Cocoa
import WebKit

/// 主视图控制器 — 内嵌 WebView + 工具栏 + QR 码
class MainViewController: NSViewController {
    var webView: WKWebView!
    var toolbarView: NSView!
    var statusLabel: NSTextField!
    var qrImageView: NSImageView!
    var qrPopover: NSPopover?
    var macServer: MacServer?
    var macPort: UInt16 = 8900
    var discoveryCallback: ((NetService) -> Void)?

    private var currentPhoneURL: String?
    private var phoneConnected = false

    override func loadView() {
        view = NSView(frame: NSRect(x: 0, y: 0, width: 900, height: 640))
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor(white: 0.08, alpha: 1).cgColor
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        setupUI()
        showLocalPage()
    }

    // MARK: - UI 布局

    func setupUI() {
        // 顶部工具栏
        toolbarView = NSView(frame: NSRect(x: 0, y: view.bounds.height - 48, width: view.bounds.width, height: 48))
        toolbarView.wantsLayer = true
        toolbarView.layer?.backgroundColor = NSColor(white: 0.12, alpha: 1).cgColor
        toolbarView.autoresizingMask = [.width, .minYMargin]

        // 状态标签
        statusLabel = NSTextField(labelWithString: "🔍 正在发现手机 HotShare 服务...")
        statusLabel.frame = NSRect(x: 16, y: 12, width: 350, height: 24)
        statusLabel.textColor = .lightGray
        statusLabel.font = NSFont.systemFont(ofSize: 13)
        statusLabel.autoresizingMask = [.maxXMargin]
        toolbarView.addSubview(statusLabel)

        // QR 码按钮
        let qrBtn = NSButton(title: "📱 显示 QR 码", target: self, action: #selector(showQRCode))
        qrBtn.frame = NSRect(x: toolbarView.bounds.width - 400, y: 8, width: 140, height: 32)
        qrBtn.bezelStyle = .rounded
        qrBtn.autoresizingMask = [.minXMargin]
        toolbarView.addSubview(qrBtn)

        // 断开连接按钮
        let disconnectBtn = NSButton(title: "📂 浏览手机文件", target: self, action: #selector(connectToPhone))
        disconnectBtn.frame = NSRect(x: toolbarView.bounds.width - 250, y: 8, width: 140, height: 32)
        disconnectBtn.bezelStyle = .rounded
        disconnectBtn.autoresizingMask = [.minXMargin]
        disconnectBtn.isEnabled = false
        disconnectBtn.tag = 100
        toolbarView.addSubview(disconnectBtn)

        view.addSubview(toolbarView)

        // WebView 配置
        let config = WKWebViewConfiguration()
        config.preferences.setValue(true, forKey: "developerExtrasEnabled") // 开发工具

        webView = WKWebView(frame: NSRect(x: 0, y: 0, width: view.bounds.width, height: view.bounds.height - 48),
                           configuration: config)
        webView.autoresizingMask = [.width, .height]
        webView.uiDelegate = self
        webView.navigationDelegate = self
        webView.setValue(false, forKey: "drawsBackground")
        webView.underPageBackgroundColor = NSColor(white: 0.06, alpha: 1)

        view.addSubview(webView, positioned: .below, relativeTo: toolbarView)
    }

    // MARK: - 页面加载

    func showLocalPage() {
        let localURL = "http://localhost:\(macPort)"
        if let url = URL(string: localURL) {
            webView.load(URLRequest(url: url))
        }
    }

    func onPhoneDiscovered(url: String, hostName: String) {
        currentPhoneURL = url
        phoneConnected = true
        statusLabel.stringValue = "✅ 已发现: \(hostName) (\(url))"

        if let btn = toolbarView.viewWithTag(100) as? NSButton {
            btn.isEnabled = true
            btn.title = "📂 浏览手机文件"
        }

        // 自动导航到手机页面
        connectToPhone()
    }

    @objc func connectToPhone() {
        guard let url = currentPhoneURL else {
            statusLabel.stringValue = "⚠️ 未发现手机服务"
            return
        }
        if let finalURL = URL(string: url) {
            webView.load(URLRequest(url: finalURL))
            statusLabel.stringValue = "📱 已连接: \(url)"
        }
    }

    @objc func showQRCode() {
        // Mac 端的 QR 码，包含 Mac 地址和服务信息
        let macAddress = getMacAddress()
        let qrContent = "hotshare://mac?host=\(macAddress)&port=\(macPort)&name=\(Host.current().localizedName ?? "Mac")"

        if let qrImage = QRCodeGenerator.generate(from: qrContent, size: NSSize(width: 300, height: 300)) {
            let imageView = NSImageView(frame: NSRect(x: 0, y: 0, width: 300, height: 300))
            imageView.image = qrImage

            let popover = NSPopover()
            popover.contentViewController = NSViewController()
            popover.contentViewController?.view = imageView
            popover.behavior = .transient
            popover.show(relativeTo: NSRect(x: toolbarView.bounds.width - 200, y: 0, width: 1, height: 1),
                        of: toolbarView, preferredEdge: .minY)
            qrPopover = popover
        }
    }

    private func getMacAddress() -> String {
        // 获取 Mac 在热点上的 IP
        let task = Process()
        task.launchPath = "/sbin/ifconfig"
        task.arguments = ["en0"]

        let pipe = Pipe()
        task.standardOutput = pipe
        task.launch()
        task.waitUntilExit()

        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        let output = String(data: data, encoding: .utf8) ?? ""

        // 尝试提取热点 IP (192.168.x.x)
        let ipPattern = try? NSRegularExpression(pattern: "inet\\s+(192\\.168\\.\\d+\\.\\d+)")
        if let match = ipPattern?.firstMatch(in: output, range: NSRange(output.startIndex..., in: output)),
           let range = Range(match.range(at: 1), in: output) {
            return String(output[range])
        }

        // 兜底：获取所有 en 开头的网卡的 IP
        let allIps = findAllIPs()
        return allIps.first { $0.hasPrefix("192.168.") } ?? allIps.first ?? "127.0.0.1"
    }

    private func findAllIPs() -> [String] {
        var addrs: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }

        var ptr = firstAddr
        while true {
            let addr = ptr.pointee
            if addr.ifa_addr.pointee.sa_family == __uint8_t(AF_INET) {
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                getnameinfo(addr.ifa_addr,
                           socklen_t(addr.ifa_addr.pointee.sa_len),
                           &hostname, socklen_t(hostname.count),
                           nil, 0, NI_NUMERICHOST)
                let ip = String(cString: hostname)
                if ip != "127.0.0.1" {
                    addrs.append(ip)
                }
            }
            guard let next = addr.ifa_next else { break }
            ptr = next
        }
        return addrs
    }
}

// MARK: - WKWebView Delegate

extension MainViewController: WKUIDelegate, WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if let url = webView.url?.absoluteString {
            if url.contains("localhost") {
                statusLabel.stringValue = "📡 已连接 Mac 本地服务"
            } else if url.contains("192.168.") {
                statusLabel.stringValue = "📱 已连接: \(url)"
            }
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        NSLog("WebView 加载失败: \(error.localizedDescription)")
    }

    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping ([URL]?) -> Void) {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true

        panel.beginSheetModal(for: view.window!) { response in
            completionHandler(response == .OK ? panel.urls : nil)
        }
    }
}
