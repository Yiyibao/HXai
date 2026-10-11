# 智能Chat使用指南

## 概述

智能Chat让AI可以生成可交互的动态界面,包含图表、表格、滑块等组件。用户操作后可以继续与AI对话。

## 典型场景

### 1. 数学函数探索

**用户**: "帮我理解二次函数 y = ax² + bx + c 的图像变化"

**AI生成**:
```json
{
  "title": "二次函数参数探索",
  "version": 3,
  "components": [
    {
      "id": "a_param",
      "type": "slider",
      "label": "参数 a (控制开口)",
      "min": -3,
      "max": 3,
      "value": 1
    },
    {
      "id": "b_param",
      "type": "slider",
      "label": "参数 b (控制位置)",
      "min": -5,
      "max": 5,
      "value": 0
    },
    {
      "id": "c_param",
      "type": "slider",
      "label": "参数 c (纵向平移)",
      "min": -5,
      "max": 5,
      "value": 0
    },
    {
      "id": "chart",
      "type": "line_chart",
      "points": [[-3, 9], [-2, 4], [-1, 1], [0, 0], [1, 1], [2, 4], [3, 9]],
      "xLabel": "x",
      "yLabel": "y",
      "scale": "a_param",
      "selectable": true
    },
    {
      "id": "description",
      "type": "card",
      "text": "调整滑块观察函数图像变化。a控制开口大小,b和c控制位置。"
    },
    {
      "id": "continue",
      "type": "button",
      "label": "根据当前参数继续讲解",
      "action": "continue"
    }
  ],
  "layout": [
    ["a_param", "b_param", "c_param"],
    ["chart"],
    ["description"],
    ["continue"]
  ]
}
```

**用户操作**: 调整a=2, b=1, c=-1, 点击"继续讲解"

**AI收到**: `{"a_param": 2, "b_param": 1, "c_param": -1}`

**AI继续**: "很好!你选择了a=2,这让抛物线开口变大。当前函数y=2x²+x-1的顶点在..."

---

### 2. 数据分析与筛选

**用户**: "分析这个销售数据表格"

**AI生成**:
```json
{
  "title": "销售数据分析",
  "version": 3,
  "components": [
    {
      "id": "region_filter",
      "type": "select",
      "label": "选择地区",
      "options": ["全部", "北京", "上海", "广州", "深圳"],
      "value": "全部"
    },
    {
      "id": "data_table",
      "type": "table",
      "columns": ["月份", "地区", "销售额", "增长率"],
      "rows": [
        ["1月", "北京", "120万", "+15%"],
        ["1月", "上海", "150万", "+20%"],
        ["2月", "北京", "135万", "+12%"],
        ["2月", "上海", "180万", "+20%"]
      ],
      "filter": "region_filter",
      "filterColumn": 1
    },
    {
      "id": "trend_chart",
      "type": "bar_chart",
      "points": [[1, 120], [2, 135], [3, 145], [4, 160]],
      "xLabel": "月份",
      "yLabel": "销售额(万)"
    },
    {
      "id": "analyze",
      "type": "button",
      "label": "深入分析当前筛选",
      "action": "continue"
    }
  ]
}
```

**交互流程**:
1. 用户选择"上海"
2. 表格自动筛选显示上海数据
3. 点击"深入分析"
4. AI收到: `{"region_filter": "上海"}`
5. AI: "专注上海地区,我们看到2月有显著增长..."

---

### 3. 预算规划对比

**用户**: "帮我比较三个旅游方案"

**AI生成**:
```json
{
  "title": "旅游方案对比",
  "version": 3,
  "components": [
    {
      "id": "budget",
      "type": "slider",
      "label": "预算上限(元)",
      "min": 3000,
      "max": 10000,
      "value": 5000
    },
    {
      "id": "days",
      "type": "select",
      "label": "天数",
      "options": ["3天", "5天", "7天"],
      "value": "5天"
    },
    {
      "id": "comparison",
      "type": "table",
      "columns": ["方案", "费用", "特色", "推荐度"],
      "rows": [
        ["海南", "4500元", "海滩+潜水", "⭐⭐⭐⭐"],
        ["云南", "4800元", "古城+雪山", "⭐⭐⭐⭐⭐"],
        ["四川", "4200元", "美食+熊猫", "⭐⭐⭐⭐"]
      ]
    },
    {
      "id": "selected",
      "type": "input",
      "label": "你倾向的方案",
      "value": ""
    },
    {
      "id": "detail",
      "type": "button",
      "label": "查看详细行程",
      "action": "continue"
    }
  ]
}
```

---

### 4. 物理模拟(本地实时计算)

**用户**: "帮我理解自由落体"

**AI生成**:
```json
{
  "title": "自由落体模拟",
  "version": 3,
  "components": [
    {
      "id": "height",
      "type": "slider",
      "label": "初始高度 h (米)",
      "min": 10,
      "max": 100,
      "value": 45
    },
    {
      "id": "time_calc",
      "type": "calculation",
      "label": "落地时间 t",
      "parameter": "height",
      "expression": {
        "op": "power",
        "args": [
          {"op": "divide", "args": [{"op": "multiply", "args": [2, {"parameter": "height"}]}, 9.8]},
          0.5
        ]
      }
    },
    {
      "id": "velocity_calc",
      "type": "calculation",
      "label": "落地速度 v",
      "parameter": "height",
      "expression": {
        "op": "power",
        "args": [
          {"op": "multiply", "args": [2, 9.8, {"parameter": "height"}]},
          0.5
        ]
      }
    },
    {
      "id": "chart",
      "type": "line_chart",
      "points": [[0, 45], [1, 40], [2, 30], [3, 15], [3.03, 0]],
      "xLabel": "时间(秒)",
      "yLabel": "高度(米)"
    },
    {
      "id": "discuss",
      "type": "button",
      "label": "讨论这个高度的情况",
      "action": "continue"
    }
  ]
}
```

**特点**: 滑块变化时,calculation组件本地实时计算,无需调用AI

---

## 模型提示词建议

在系统提示中加入:

```
你可以使用create_interactive_surface工具创建交互界面。

何时使用:
- 需要展示数据图表
- 用户想调整参数观察变化
- 需要对比多个方案
- 交互式学习场景
- 数据筛选和探索

设计原则:
1. 每个界面聚焦一个主题
2. 滑块/选择器用于关键参数
3. 图表直观展示规律
4. calculation实现本地实时计算
5. 提供"继续分析"按钮让用户提交状态

不要仅为展示文本使用界面,纯文字回复更合适。
```

---

## 技术限制

- 最多24个组件
- 图表最多500个数据点
- 表格最多200行
- 文本字段最长4000字符
- 仅支持"continue"动作

---

## 调试建议

1. **界面不显示**: 检查definitionJson格式是否valid JSON
2. **状态不保存**: 确认surfaceId唯一且revision递增
3. **按钮无响应**: 检查action="continue"拼写
4. **图表数据错误**: 验证points格式[[x,y], ...]

---

## 最佳实践

✅ **好的设计**:
- 滑块控制关键参数
- 图表可视化规律
- 计算组件显示实时结果
- 按钮文案明确("根据当前参数继续分析")

❌ **避免**:
- 过多组件(超过15个会显得拥挤)
- 纯展示静态文本(用普通回复更好)
- 复杂的嵌套计算表达式
- 模糊的按钮文案("继续"太笼统)

---

## 示例代码片段

### 最简示例
```json
{
  "title": "Hello World",
  "version": 3,
  "components": [
    {"id": "t1", "type": "text", "text": "这是交互界面!"},
    {"id": "b1", "type": "button", "label": "继续", "action": "continue"}
  ]
}
```

### 图表示例
```json
{
  "id": "chart1",
  "type": "line_chart",
  "points": [[0,0], [1,1], [2,4], [3,9]],
  "xLabel": "x轴",
  "yLabel": "y轴",
  "selectable": true
}
```

### 联动示例
```json
{
  "components": [
    {"id": "s1", "type": "slider", "label": "缩放", "min": 1, "max": 10, "value": 5},
    {"id": "c1", "type": "line_chart", "points": [...], "scale": "s1"}
  ]
}
```
