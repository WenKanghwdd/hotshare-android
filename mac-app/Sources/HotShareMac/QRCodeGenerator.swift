import Cocoa
import CoreImage

/// QR 码生成器 — 使用 CoreImage 原生生成 QR 码
class QRCodeGenerator {
    /// 生成 QR 码图片
    static func generate(from string: String, size: NSSize) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }

        filter.setDefaults()
        let data = string.data(using: .utf8)
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("H", forKey: "inputCorrectionLevel") // 高纠错

        guard let ciImage = filter.outputImage else { return nil }

        // 放大到指定尺寸
        let scale = size.width / ciImage.extent.size.width
        let scaledImage = ciImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        // 转为 NSImage
        let rep = NSCIImageRep(ciImage: scaledImage)
        let nsImage = NSImage(size: size)
        nsImage.addRepresentation(rep)

        return nsImage
    }

    /// 生成带颜色的 QR 码
    static func generateColored(from string: String, size: NSSize,
                                 foreground: CIColor = CIColor(red: 0, green: 0, blue: 0),
                                 background: CIColor = CIColor(red: 1, green: 1, blue: 1)) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }

        filter.setDefaults()
        filter.setValue(string.data(using: .utf8), forKey: "inputMessage")
        filter.setValue("H", forKey: "inputCorrectionLevel")

        guard let ciImage = filter.outputImage else { return nil }

        // 着色
        let colorFilter = CIFilter(name: "CIFalseColor")
        colorFilter?.setDefaults()
        colorFilter?.setValue(ciImage, forKey: "inputImage")
        colorFilter?.setValue(foreground, forKey: "inputColor0")
        colorFilter?.setValue(background, forKey: "inputColor1")

        guard let coloredImage = colorFilter?.outputImage else { return nil }

        let scale = size.width / coloredImage.extent.size.width
        let scaledImage = coloredImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        let rep = NSCIImageRep(ciImage: scaledImage)
        let nsImage = NSImage(size: size)
        nsImage.addRepresentation(rep)

        return nsImage
    }
}
