#!/bin/bash
# HotShare 项目初始化脚本
# 用法: chmod +x setup.sh && ./setup.sh

set -e

echo "🔥 HotShare 项目初始化"
echo "======================="

# 检查 Java
if ! command -v java &> /dev/null; then
    echo "⚠️ 未检测到 Java，请先安装 JDK 17+"
    echo "   brew install openjdk@17"
    exit 1
fi
echo "✅ Java: $(java -version 2>&1 | head -1)"

# 生成 Gradle Wrapper
if [ ! -f "gradlew" ]; then
    echo "📦 生成 Gradle Wrapper..."
    # 方法1: 用 gradle 命令
    if command -v gradle &> /dev/null; then
        gradle wrapper --gradle-version 8.5
    else
        # 方法2: 手动下载
        echo "   下载 gradle-wrapper.jar..."
        curl -sL "https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar" \
            -o gradle/wrapper/gradle-wrapper.jar
        cat > gradlew << 'SCRIPT'
#!/bin/sh
DIR="$( cd "$( dirname "$0" )" && pwd )"
java -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
SCRIPT
        chmod +x gradlew
        cat > gradlew.bat << 'BATSCRIPT'
@if "%DEBUG%"=="" @echo off
@rem Gradle wrapper
@if "%OS%"=="Windows_NT" setlocal
set DIRNAME=%~dp0
"%JAVA_HOME%/bin/java" -classpath "%DIRNAME%gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
BATSCRIPT
    fi
    echo "✅ Gradle Wrapper 已生成"
fi

# Android SDK 检查
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo ""
    echo "⚠️ ANDROID_HOME 未设置"
    echo "   请安装 Android Studio，然后在 local.properties 中设置："
    echo "   sdk.dir=/Users/$(whoami)/Library/Android/sdk"
fi

echo ""
echo "✅ 初始化完成！"
echo "   用 Android Studio 打开本目录即可编译运行"
echo ""
echo "   或运行: ./gradlew installDebug"
