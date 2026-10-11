@echo off
REM 智能Chat系统 - 模拟器测试快速启动脚本

echo ========================================
echo 智能Chat系统 - 模拟器测试
echo ========================================
echo.

echo [1/4] 检查Android SDK...
where adb >nul 2>&1
if %errorlevel% neq 0 (
    echo 错误: 未找到adb命令，请确保Android SDK已安装
    pause
    exit /b 1
)
echo OK - Android SDK已就绪
echo.

echo [2/4] 构建APK...
cd client
call gradlew.bat :app:assembleDebug
if %errorlevel% neq 0 (
    echo 错误: 构建失败
    pause
    exit /b 1
)
echo OK - APK构建成功
echo.

echo [3/4] 检查模拟器...
adb devices | findstr "device" >nul
if %errorlevel% neq 0 (
    echo 警告: 未检测到设备/模拟器
    echo 请先启动Android模拟器或连接真机
    pause
    exit /b 1
)
echo OK - 设备已连接
echo.

echo [4/4] 安装APK...
adb install -r app\build\outputs\apk\debug\app-debug.apk
if %errorlevel% neq 0 (
    echo 错误: 安装失败
    pause
    exit /b 1
)
echo OK - 应用已安装
echo.

echo ========================================
echo 安装完成！
echo ========================================
echo.
echo 测试步骤:
echo 1. 打开应用并配置API密钥
echo 2. 发送: "帮我理解二次函数 y=ax²+bx+c"
echo 3. 观察是否生成交互界面(滑块+图表+按钮)
echo 4. 操作滑块,点击"继续分析"按钮
echo 5. 验证AI是否收到参数并继续对话
echo.
echo 更多测试场景: docs\intelligent-chat-test-cases.md
echo.
pause
