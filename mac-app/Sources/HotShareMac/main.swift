import Cocoa
import WebKit
import ServiceManagement

// ====== App 入口 ======
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
