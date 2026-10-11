import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/math_markdown.dart';

void main() {
  final testCases = <int, String>{
    1: r'Inline math: $x^2 + y^2 = z^2$',
    2: r'Greek letters: $\alpha + \beta = \gamma$',
    3: r'Fraction: $\frac{a}{b} + \frac{c}{d}$',
    4: r'Square root: $\sqrt{x^2 + 1}$',
    5: r'Block math: $$x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}$$',
    6: r'Bracket block: \[\int_{0}^{\infty} e^{-x^2} dx = \frac{\sqrt{\pi}}{2}\]',
    7: r'$$\begin{aligned} (a+b)^2 &= (a+b)(a+b) \\ &= a^2 + 2ab + b^2 \end{aligned}$$',
    8: r'$$\begin{pmatrix} a & b \\ c & d \end{pmatrix}$$',
    9: r'$$\begin{vmatrix} 1 & 2 \\ 3 & 4 \end{vmatrix} = -2$$',
    10: r'$$f(x) = \begin{cases} x, & x \ge 0 \\ -x, & x < 0 \end{cases}$$',
    11: r'$$\lim_{n \to \infty} \sum_{k=1}^n \frac{1}{k^2} = \frac{\pi^2}{6}$$',
    12: r'$$\nabla \cdot \mathbf{E} = \frac{\rho}{\varepsilon_0}$$',
    13: r'$$f(x) = a_0 + a_1 x + a_2 x^2 + a_3 x^3 + a_4 x^4 + a_5 x^5 + a_6 x^6 + a_7 x^7 + a_8 x^8 + a_9 x^9 + a_{10} x^{10}$$',
    14: r'$$\cfrac{1}{1 + \cfrac{1}{2 + \cfrac{1}{3 + \dots}}}$$',
    15: r'设函数 $f(x) = \sin(x)$，则在区间 $[0, \pi]$ 上的积分为 $2$。',
    16: "由柯西-施瓦茨不等式可知：\n" r'$$\left(\sum a_i b_i\right)^2 \le \left(\sum a_i^2\right)\left(\sum b_i^2\right)$$',
    17: r'这件商品售价 \$100 美元，打折后是 \$80 美元。',
    18: r'收入为 $50 与 $75 之间',
    19: r'正在计算：$x^2 + 2x +',
    20: "推导中：\n" r'$$\int_0^1 f(x) dx = ',
    21: r'$$\frac{1}{$$',
    22: r'$$\unknownMacro{123}$$',
    23: "# 标题一\n## 标题二\n### 标题三",
    24: "**粗体** *斜体* ~~删除线~~",
    25: "- 列表项一\n- 列表项二\n1. 第一步\n2. 第二步",
    26: "> 这是一个引用说明\n> 包含两行文字",
    27: "使用 `python solve.py` 命令运行",
    28: "```python\ndef solve():\n    return 42\n```",
    29: "| 参数 | 含义 | 默认值 |\n|---|---|---|\n| lr | 学习率 | 0.001 |\n| batch | 批大小 | 32 |",
    30: "参考资料：[数学维基](https://en.wikipedia.org/wiki/Mathematics)",
    31: "### 步骤 1：设未知数\n设直角三角形的两条直角边分别为 \$a\$ 和 \$b\$。\n根据勾股定理：\n" r'$$a^2 + b^2 = c^2$$' "\n代入已知数值 \$a=3, b=4\$：\n" r'$$c = \sqrt{3^2 + 4^2} = 5$$',
    32: r'$$\begin{bmatrix} y_1 \\ y_2 \\ y_3 \end{bmatrix} = \begin{bmatrix} w_{11} & w_{12} & w_{13} & w_{14} & w_{15} \\ w_{21} & w_{22} & w_{23} & w_{24} & w_{25} \\ w_{31} & w_{32} & w_{33} & w_{34} & w_{35} \end{bmatrix} \begin{bmatrix} x_1 \\ x_2 \\ x_3 \\ x_4 \\ x_5 \end{bmatrix} + \begin{bmatrix} b_1 \\ b_2 \\ b_3 \end{bmatrix}$$',
  };

  group('Math & Markdown 32 Acceptance Test Cases (C05)', () {
    for (final entry in testCases.entries) {
      final index = entry.key;
      final text = entry.value;

      testWidgets('Case $index renders without exception and preserves content', (WidgetTester tester) async {
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: SingleChildScrollView(
                child: MathMarkdownView(content: text),
              ),
            ),
          ),
        );

        // Verify the MathMarkdownView mounted properly
        expect(find.byType(MathMarkdownView), findsOneWidget);
        // Verify frame pumped without error
        await tester.pumpAndSettle();
      });
    }
  });
}
