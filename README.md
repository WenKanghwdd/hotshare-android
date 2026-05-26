# 🔥 HotShare — 热点直连文件互传

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-blue)](https://www.android.com)
[![API](https://img.shields.io/badge/API-26%2B-brightgreen)](https://developer.android.com)

**安卓手机 ↔ Mac 通过手机热点高速互传文件，零公网零流量。**

不需要数据线、不需要路由器、不需要连外网。手机开个热点，Mac 浏览器打开一个网页就能传文件，速度可达 100MB/s+。

---

## 为什么做这个？

传个文件给 Mac，日常方案各有各的烦：

| 方案 | 问题 |
|---|---|
| 微信/QQ | 压缩图片、限制 1GB、要流量 |
| AirDrop | Mac 想传文件给安卓？没门 |
| 数据线 | 线不在手边、驱动问题 |
| 网盘 | 上传下载太慢，还要流量 |
| AirDroid | 走公网，有隐私顾虑 |

**HotShare 的方案：** 手机开热点，Mac 连上来，热点局域网里直传。什么都不经过 —— 不经过路由器、不经过运营商、不经过任何云。纯局域网，物理隔离级别的安全。

---

## 使用步骤

```
1. 手机打开 HotShare App → 点「启动服务」
2. 手机开启个人热点
3. Mac 连接该热点 Wi-Fi
4. Mac 浏览器打开 http://192.168.43.1:8080
5. 拖拽文件上传 / 点击下载
```

### 双向传输

| 方向 | 操作 |
|---|---|
| **Mac → 手机** | 浏览器打开网页，拖拽文件到上传区域 |
| **手机 → Mac** | App 里点「选择文件发送到 Mac」，选完文件，浏览器里下载 |

---

## 项目结构

```
hotshare-android/
├── app/
│   └── src/main/
│       ├── java/com/hotshare/
│       │   ├── MainActivity.kt           # Compose 入口
│       │   ├── server/
│       │   │   ├── FileServer.kt         # NanoHTTPD HTTP 服务器
│       │   │   └── MimeTypes.kt          # MIME 类型映射
│       │   ├── service/
│       │   │   └── HotspotService.kt     # Android 前台服务
│       │   ├── ui/
│       │   │   └── MainScreen.kt         # Compose UI + 文件选择器
│       │   └── util/
│       │       ├── NetworkUtil.kt        # 网络接口工具
│       │       └── FileUtil.kt           # 文件操作工具
│       ├── assets/web/
│       │   ├── index.html                # Web UI
│       │   ├── style.css                 # 暗色主题
│       │   └── app.js                    # 上传/下载/进度
│       └── res/
├── build.gradle.kts
└── settings.gradle.kts
```

## 技术栈

| 端 | 技术 |
|---|---|
| **安卓** | Kotlin + Jetpack Compose + NanoHTTPD |
| **Mac/Web** | HTML5 + CSS3 + Vanilla JS（零依赖） |
| **传输协议** | HTTP + multipart/form-data |
| **加密** | 热点局域网，物理隔离。无公网暴露 |

## 零流量保证

- HTTP 服务器只绑定 `wlan` 网络接口
- 蜂窝接口（`rmnet_data0` 等）完全不通
- 即使蜂窝有信号，传输也强制走热点局域网

## 快速开始

### 编译安装

```bash
./gradlew installDebug
```

需要在 Android Studio 配置好 JDK 17+，手机 Android 8.0+。

### 或者直接装 APK

Releases 页面下载最新 APK 安装。

---

## TODO

- [ ] mDNS / Bonjour 自动发现（免输入 IP）
- [ ] 多文件 zip 打包下载
- [ ] Mac 原生 SwiftUI 客户端
- [ ] 剪贴板同步

---

MIT License
