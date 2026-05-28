import Foundation
import AppKit

/// Mac 端文件服务器 — 让手机可以浏览/下载 Mac 上的文件
class MacServer {
    private let port: UInt16
    private var socket: Int32 = -1
    private var running = false
    private var queue: DispatchQueue!

    let shareDir: String

    init(port: UInt16) {
        self.port = port
        self.shareDir = NSHomeDirectory() + "/HotShare"
        self.queue = DispatchQueue(label: "com.hotshare.macserver", qos: .background)

        // 确保共享目录存在
        let fm = FileManager.default
        if !fm.fileExists(atPath: shareDir) {
            try? fm.createDirectory(atPath: shareDir, withIntermediateDirectories: true)
        }
    }

    func start() {
        running = true
        queue.async { [weak self] in
            self?.serve()
        }
    }

    func stop() {
        running = false
        if socket >= 0 {
            close(socket)
            socket = -1
        }
    }

    private func serve() {
        socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else {
            NSLog("❌ MacServer: 无法创建 socket")
            return
        }

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = CFSwapInt16HostToBig(port)
        addr.sin_addr.s_addr = INADDR_ANY

        var opt: Int32 = 1
        setsockopt(socket, SOL_SOCKET, SO_REUSEADDR, &opt, socklen_t(MemoryLayout<Int32>.size))

        let bindResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        guard bindResult == 0 else {
            NSLog("❌ MacServer: 绑定端口失败 \(port)")
            close(socket)
            socket = -1
            return
        }

        listen(socket, 5)
        NSLog("📡 MacServer: 正在监听端口 \(port)，共享目录: \(shareDir)")

        while running {
            var clientAddr = sockaddr()
            var addrLen = socklen_t(MemoryLayout<sockaddr>.size)
            let client = accept(socket, &clientAddr, &addrLen)

            guard client >= 0 else {
                if running { usleep(100000) }
                continue
            }

            handleClient(client)
            close(client)
        }
    }

    private func handleClient(_ client: Int32) {
        var buffer = [UInt8](repeating: 0, count: 4096)
        let n = read(client, &buffer, buffer.count)
        guard n > 0 else { return }

        let request = String(cString: buffer)
        let lines = request.components(separatedBy: "\r\n")
        guard let firstLine = lines.first else {
            sendResponse(client, status: 400, body: "Bad Request")
            return
        }

        let parts = firstLine.components(separatedBy: " ")
        guard parts.count >= 2 else {
            sendResponse(client, status: 400, body: "Bad Request")
            return
        }

        let method = parts[0]
        var path = parts[1]
            .removingPercentEncoding?
            .split(separator: "?").first
            .map(String.init) ?? "/"

        if path == "/" { path = "/index.html" }

        switch method {
        case "GET":
            handleGET(client, path: path)
        case "POST":
            handlePOST(client, request: request, path: path)
        default:
            sendResponse(client, status: 405, body: "Method Not Allowed")
        }
    }

    private func handleGET(_ client: Int32, path: String) {
        // 首页
        if path == "/index.html" {
            sendHTML(client)
            return
        }

        // API: 文件列表
        if path == "/api/files" {
            sendFileList(client)
            return
        }

        // API: 下载
        if path.hasPrefix("/api/download/") {
            let fileName = String(path.dropFirst("/api/download/".count))
            sendFileDownload(client, fileName: fileName)
            return
        }

        sendResponse(client, status: 404, body: "Not Found")
    }

    private func handlePOST(_ client: Int32, request: String, path: String) {
        if path == "/api/upload" {
            // 简易文件上传处理
            // 从 request body 提取文件内容
            let bodyParts = request.components(separatedBy: "\r\n\r\n")
            guard bodyParts.count >= 2 else {
                sendResponse(client, status: 400, body: "Bad Request")
                return
            }

            let body = bodyParts[1]
            // 对于大文件上传，需要更复杂的 multipart 处理
            // 这里简化为保存原始 body 到文件
            let fileName = "upload_\(Int(Date().timeIntervalSince1970)).bin"
            let filePath = "\(shareDir)/\(fileName)"

            do {
                try body.write(toFile: filePath, atomically: true, encoding: .utf8)
                let json = "{\"status\":\"ok\",\"name\":\"\(fileName)\"}"
                sendResponse(client, status: 200, contentType: "application/json", body: json)
            } catch {
                sendResponse(client, status: 500, body: "Upload failed")
            }
            return
        }

        sendResponse(client, status: 404, body: "Not Found")
    }

    // MARK: - 页面

    private func sendHTML(_ client: Int32) {
        let html = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>HotShare Mac</title>
        <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { font-family: -apple-system, sans-serif; background: #0f0f13; color: #e8e8f0; padding: 20px; }
            h1 { text-align: center; font-size: 1.8rem; margin: 20px 0; background: linear-gradient(135deg, #4fc3f7, #81c784); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
            .info { text-align: center; color: #888; margin-bottom: 20px; }
            .files { max-width: 600px; margin: 0 auto; }
            .file-item { display: flex; align-items: center; gap: 12px; padding: 10px 14px; background: #1a1a24; border-radius: 10px; margin-bottom: 6px; }
            .file-item:hover { background: #252533; }
            .file-icon { font-size: 1.3rem; }
            .file-info { flex: 1; min-width: 0; }
            .file-name { font-size: 0.85rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .file-meta { font-size: 0.72rem; color: #888; margin-top: 2px; }
            .download-btn { padding: 4px 12px; background: rgba(79,195,247,0.15); color: #4fc3f7; border: 1px solid #4fc3f7; border-radius: 6px; font-size: 0.75rem; text-decoration: none; }
            .download-btn:hover { background: #4fc3f7; color: #000; }
            .empty { text-align: center; padding: 40px; color: #666; }
            .footer { text-align: center; margin-top: 30px; font-size: 0.7rem; color: #555; }
        </style>
        </head>
        <body>
        <h1>🔥 HotShare Mac</h1>
        <p class="info">从手机访问此页面可浏览和下载 Mac 上的文件</p>
        <div class="files" id="fileList"><div class="empty">📂 加载中...</div></div>
        <div class="footer">HotShare Mac Companion · 文件保存在 ~/HotShare</div>
        <script>
        fetch('/api/files').then(r=>r.json()).then(files=>{
            const el = document.getElementById('fileList');
            if (files.length===0) { el.innerHTML='<div class="empty">📭 暂无文件</div>'; return; }
            el.innerHTML = files.map(f=>`
                <div class="file-item">
                    <span class="file-icon">📄</span>
                    <div class="file-info">
                        <div class="file-name">${f.name}</div>
                        <div class="file-meta">${(f.size/1024).toFixed(1)} KB</div>
                    </div>
                    <a class="download-btn" href="/api/download/${encodeURIComponent(f.name)}">下载</a>
                </div>
            `).join('');
        }).catch(()=>{ document.getElementById('fileList').innerHTML='<div class="empty">⚠️ 加载失败</div>'; });
        </script>
        </body>
        </html>
        """
        sendResponse(client, status: 200, contentType: "text/html; charset=utf-8", body: html)
    }

    // MARK: - API

    private func sendFileList(_ client: Int32) {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(atPath: shareDir) else {
            sendResponse(client, status: 200, contentType: "application/json", body: "[]")
            return
        }

        let files = items.compactMap { name -> String? in
            let path = "\(shareDir)/\(name)"
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: path, isDirectory: &isDir), !isDir.boolValue else { return nil }
            let attrs = try? fm.attributesOfItem(atPath: path)
            let size = attrs?[.size] as? Int64 ?? 0
            let mtime = (attrs?[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
            let encoded = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name
            return "{\"name\":\"\(name)\",\"size\":\(size),\"mtime\":\(Int(mtime*1000)),\"encodedName\":\"\(encoded)\"}"
        }

        let json = "[\n\(files.joined(separator: ",\n"))\n]"
        sendResponse(client, status: 200, contentType: "application/json", body: json)
    }

    private func sendFileDownload(_ client: Int32, fileName: String) {
        let decoded = fileName.removingPercentEncoding ?? fileName
        let filePath = "\(shareDir)/\(decoded)"
        guard let data = try? Data(contentsOf: URL(fileURLWithPath: filePath)) else {
            sendResponse(client, status: 404, body: "Not Found")
            return
        }

        let headers = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: \(data.count)\r\nContent-Disposition: attachment; filename=\"\(decoded)\"\r\n\r\n"
        var headerData = Data(headers.utf8)
        headerData.append(data)

        headerData.withUnsafeBytes { ptr in
            write(client, ptr.baseAddress, headerData.count)
            return data.count
        }
    }

    // MARK: - 响应

    private func sendResponse(_ client: Int32, status: Int, contentType: String = "text/plain; charset=utf-8", body: String) {
        let response = "HTTP/1.1 \(status) \(statusText(status))\r\nContent-Type: \(contentType)\r\nContent-Length: \(body.utf8.count)\r\nAccess-Control-Allow-Origin: *\r\n\r\n\(body)"
        let data = Data(response.utf8)
        data.withUnsafeBytes { ptr in
            write(client, ptr.baseAddress, data.count)
            return data.count
        }
    }

    private func sendResponse(_ client: Int32, status: Int, body: String) {
        sendResponse(client, status: status, contentType: "text/plain; charset=utf-8", body: body)
    }

    private func statusText(_ code: Int) -> String {
        ["": "", "200": "OK", "400": "Bad Request", "404": "Not Found", "405": "Method Not Allowed", "500": "Internal Server Error"][String(code)] ?? "Unknown"
    }
}
