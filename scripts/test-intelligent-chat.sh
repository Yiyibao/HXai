#!/bin/bash
# 智能Chat系统集成测试脚本

echo "==================================="
echo "智能Chat系统集成完成验证"
echo "==================================="
echo ""

echo "✅ 1. 检查核心文件是否存在..."
files=(
  "client/ai/src/main/java/me/rerere/ai/ui/InteractiveSurface.kt"
  "client/app/src/main/java/me/rerere/rikkahub/data/ai/tools/InteractiveSurfaceTool.kt"
  "client/app/src/main/java/me/rerere/rikkahub/data/ai/InteractiveSurfaceManager.kt"
  "client/flutter_ui/lib/widgets/interactive_surface_message.dart"
  "docs/intelligent-chat-usage-guide.md"
)

all_exist=true
for file in "${files[@]}"; do
  if [ -f "$file" ]; then
    echo "  ✓ $file"
  else
    echo "  ✗ $file (缺失)"
    all_exist=false
  fi
done

echo ""
echo "✅ 2. 检查代码集成..."

# 检查ChatToolFactory是否包含智能界面工具
if grep -q "CreateInteractiveSurfaceTool" client/app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt; then
  echo "  ✓ ChatToolFactory已集成CreateInteractiveSurfaceTool"
else
  echo "  ✗ ChatToolFactory未集成工具"
  all_exist=false
fi

# 检查GenerationLoop是否包含系统提示
if grep -q "智能交互界面" client/app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt; then
  echo "  ✓ GenerationLoop已添加智能界面系统提示"
else
  echo "  ✗ GenerationLoop缺少系统提示"
  all_exist=false
fi

# 检查chat_page是否支持渲染
if grep -q "interactive_surface" client/flutter_ui/lib/pages/chat_page.dart; then
  echo "  ✓ chat_page已支持interactive_surface渲染"
else
  echo "  ✗ chat_page未集成渲染逻辑"
  all_exist=false
fi

# 检查MessagePartDto是否更新
if grep -q "surfaceId" client/flutter_ui/pigeons/study_host_api.dart; then
  echo "  ✓ MessagePartDto已添加surface字段"
else
  echo "  ✗ MessagePartDto未更新"
  all_exist=false
fi

echo ""
echo "==================================="
if [ "$all_exist" = true ]; then
  echo "✅ 所有集成检查通过！"
  echo ""
  echo "📱 下一步：在模拟器中测试"
  echo ""
  echo "测试步骤："
  echo "1. 编译并运行APK"
  echo "2. 发送测试消息：'帮我理解二次函数 y=ax²+bx+c'"
  echo "3. 观察AI是否生成交互界面(滑块+图表)"
  echo "4. 操作滑块,点击'继续分析'按钮"
  echo "5. 验证AI是否收到参数状态并继续对话"
  echo ""
  echo "更多测试场景见: docs/intelligent-chat-usage-guide.md"
else
  echo "❌ 部分检查未通过，请检查上述错误"
fi
echo "==================================="
