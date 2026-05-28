#!/bin/bash
# HotShare Mac App 构建脚本
# 用法: chmod +x build.sh && ./build.sh

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$PROJECT_DIR/.build"
APP_NAME="HotShareMac"
APP_BUNDLE="$BUILD_DIR/$APP_NAME.app"
APP_CONTENTS="$APP_BUNDLE/Contents"
APP_MACOS="$APP_CONTENTS/MacOS"
APP_RESOURCES="$APP_CONTENTS/Resources"

echo "🔥 构建 HotShare Mac App..."
echo "========================"

# 1. 编译 Swift
echo "📦 编译 Swift..."
cd "$PROJECT_DIR"
swift build -c release --product HotShareMac

# 2. 创建 .app 包
echo "📁 创建 .app 包..."
rm -rf "$APP_BUNDLE"
mkdir -p "$APP_MACOS"
mkdir -p "$APP_RESOURCES"

# 3. 复制二进制
cp "$BUILD_DIR/release/HotShareMac" "$APP_MACOS/"

# 4. 复制 Info.plist
cp "$PROJECT_DIR/Resources/Info.plist" "$APP_CONTENTS/"

# 5. 生成图标（使用 sips 从系统图标生成）
echo "🎨 生成图标..."
ICONS_DIR="$APP_RESOURCES"
ICONSET="$BUILD_DIR/AppIcon.iconset"
mkdir -p "$ICONSET"

# 创建一个简单的程序图标（使用系统工具）
# 实际应用中应该使用自定义图标
# 这里生成一个纯色带🔥的 png
if command -v python3 &> /dev/null; then
    python3 -c "
import struct, zlib
def create_png(size, path):
    # 简单红色渐变方形
    width, height = size, size
    pixels = []
    for y in range(height):
        row = []
        for x in range(width):
            # 蓝色到紫色渐变
            r = int(79 + (x/width) * 40)
            g = int(195 - (y/height) * 60)
            b = int(247 - (x/width) * 30)
            a = 255
            row.extend([r, g, b, a])
        pixels.append(bytes([0] + row))  # filter byte + row data
    
    raw = b''.join(pixels)
    
    def chunk(chunk_type, data):
        c = chunk_type + data
        crc = struct.pack('>I', zlib.crc32(c) & 0xffffffff)
        return struct.pack('>I', len(data)) + c + crc
    
    ihdr = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)  # RGBA
    idat = zlib.compress(raw)
    
    png = b'\\x89PNG\\r\\n\\x1a\\n'
    png += chunk(b'IHDR', ihdr)
    png += chunk(b'IDAT', idat)
    png += chunk(b'IEND', b'')
    
    with open(path, 'wb') as f:
        f.write(png)

for s in [16, 32, 64, 128, 256]:
    create_png(s, f'$ICONSET/icon_{s}x{s}.png')
    if s != 16:
        create_png(s*2, f'$ICONSET/icon_{s}x{s}@2x.png')
"

    # 转换为 icns
    iconutil -c icns "$ICONSET" -o "$ICONSET/../AppIcon.icns" 2>/dev/null || true
    if [ -f "$ICONSET/../AppIcon.icns" ]; then
        cp "$ICONSET/../AppIcon.icns" "$ICONS_DIR/"
    fi
fi

# 清理图标工作目录
rm -rf "$ICONSET"

echo ""
echo "✅ 构建完成!"
echo "📱 App 路径: $APP_BUNDLE"
echo ""
echo "   两种方式运行:"
echo "   1. 双击 $APP_NAME.app"
echo "   2. 终端执行: open \"$APP_BUNDLE\""
echo ""
echo "   如果需要复制到 Applications:"
echo "   cp -R \"$APP_BUNDLE\" /Applications/"
