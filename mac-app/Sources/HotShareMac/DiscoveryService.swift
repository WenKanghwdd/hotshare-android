import Foundation

/// Bonjour 服务发现 — 自动发现手机上的 HotShare 服务
class DiscoveryService: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private var browser: NetServiceBrowser?
    private var services: [NetService] = []

    var onServiceFound: ((NetService) -> Void)?
    var onServiceLost: ((NetService) -> Void)?

    func startBrowsing() {
        browser = NetServiceBrowser()
        browser?.delegate = self
        browser?.searchForServices(ofType: "_http._tcp.", inDomain: "local.")
        NSLog("🔍 开始搜索 HotShare 服务 (Bonjour _http._tcp)...")
    }

    func stopBrowsing() {
        browser?.stop()
        browser = nil
        services.removeAll()
    }

    // MARK: - NetServiceBrowserDelegate

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        // 只关注名为 "HotShare" 的服务
        if service.name.contains("HotShare") || service.type == "_http._tcp." {
            NSLog("📱 发现服务: \(service.name)")
            service.delegate = self
            service.resolve(withTimeout: 5.0)
            services.append(service)
        }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        services.removeAll { $0 === service }
        onServiceLost?(service)
        if let ip = service.ipAddress {
            NSLog("📱 服务断开: \(service.name) (\(ip))")
        }
    }

    func netServiceBrowserDidStopSearch(_ browser: NetServiceBrowser) {
        NSLog("🔍 Bonjour 搜索停止")
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        NSLog("⚠️ Bonjour 搜索失败: \(errorDict)")
    }

    // MARK: - NetServiceDelegate

    func netServiceDidResolveAddress(_ sender: NetService) {
        if let ip = sender.ipAddress {
            NSLog("📱 服务已解析: \(sender.name) → \(ip):\(sender.port)")
        }
        onServiceFound?(sender)
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        NSLog("⚠️ 服务解析失败: \(sender.name) - \(errorDict)")
    }
}
