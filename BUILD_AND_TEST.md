# 构建与测试指南

## 快速开始

### Windows
```batch
scripts\test-intelligent-chat.bat
```

### Linux/Mac
```bash
bash scripts/test-intelligent-chat.sh
```

## 手动步骤

### 1. 构建APK
```bash
cd client
./gradlew :app:assembleDebug
```

输出: `client/app/build/outputs/apk/debug/app-debug.apk`

### 2. 安装
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 配置
1. 启动应用
2. 进入设置
3. 添加API密钥(OpenAI/Claude/Gemini)

### 4. 测试
发送消息: "帮我理解二次函数 y=ax²+bx+c"

### 5. 验证
- [ ] AI生成交互界面
- [ ] 界面包含滑块、图表、按钮
- [ ] 滑块可拖动
- [ ] 图表实时更新
- [ ] 点击按钮触发新对话
- [ ] AI收到参数状态

## 完整测试用例

见 `docs/intelligent-chat-test-cases.md`

## 故障排查

### 问题: 构建失败
**解决**: 检查Java版本 (需要JDK 17+)
```bash
java -version
```

### 问题: 界面不显示
**解决**: 查看logcat
```bash
adb logcat | grep -i "surface\|interactive"
```

### 问题: 按钮无响应
**解决**: 检查网络连接和API密钥配置

## 性能测试

### 测试大数据量
```
创建一个包含500个数据点的折线图
```

### 测试多个界面
```
在同一对话中创建3个不同的交互界面
```

## 成功标准

- ✅ APK成功构建
- ✅ 安装无错误
- ✅ 至少1个测试用例通过
- ✅ 无崩溃或卡顿
- ✅ 内存占用正常
