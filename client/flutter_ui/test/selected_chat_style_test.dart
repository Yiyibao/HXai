import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/theme/study_theme.dart';
import 'package:flutter_ui/widgets/interactive_surface.dart';
import 'package:flutter_ui/widgets/math_plot.dart';
import 'package:flutter_ui/widgets/surface_semantics.dart';

Map<String, dynamic> metric(String id, String label, num value, String unit) =>
    {'id': id, 'type': 'metric', 'label': label, 'value': value, 'unit': unit};
Map<String, dynamic> chart(String type) => {
  'id': 'trend',
  'type': type,
  'digits': 0,
  'xLabel': '日期（周一至周五）',
  'yLabel': '学习时长',
  'yUnit': '分钟',
  'points': [
    [1, 20],
    [2, 35],
    [3, 25],
    [4, 45],
    [5, 30],
  ],
};
Map<String, dynamic> fixture(String name) {
  final data = <String, dynamic>{
    'version': 4,
    'title': '本周学习概览',
    'components': <Map<String, dynamic>>[],
  };
  switch (name) {
    case 'card':
      data.addAll({
        'title': '今日学习记录',
        'presentation': 'panel',
        'components': [
          metric('minutes', '今日学习时长', 25, '分钟'),
          metric('questions', '已完成练习', 8, '道题'),
          {'id': 'next', 'type': 'card', 'text': '### 下一步学习计划\n\n复习二次函数'},
        ],
        'composition': {
          'type': 'column',
          'children': [
            {
              'type': 'row',
              'children': ['minutes', 'questions'],
            },
            'next',
          ],
        },
      });
    case 'two':
    case 'many':
      data.addAll({
        'title': '通勤方案比较',
        'components': [
          {
            'id': 'plans',
            'type': 'comparison',
            'items': [
              {
                'title': '方案一：步行',
                'text': '时间：**30 分钟**\n费用：**0 元**\n优点：免费、锻炼身体\n缺点：耗时较长、受天气影响\n适用情况：时间充裕、天气舒适',
              },
              {
                'title': '方案二：骑车',
                'text': '时间：**12 分钟**\n费用：**2 元**\n优点：速度快、出行灵活\n缺点：需要安全骑行路线\n适用情况：赶时间、天气良好',
              },
              if (name == 'many')
                {
                  'title': '方案三：公交',
                  'text': '时间：**20 分钟**\n费用：**3 元**\n优点：较省体力、便于携带物品\n缺点：可能拥挤、需要等车\n适用情况：雨天、身体疲劳',
                },
            ],
          },
        ],
      });
    case 'code':
      data.addAll({
        'title': 'Python · One Dark',
        'components': [
          {
            'id': 'code',
            'type': 'code_block',
            'language': 'python',
            'code': 'def calculate_average(numbers):\n    """计算一组数字的平均数"""\n    if not numbers:\n        return 0.0\n    total = sum(numbers)\n    return total / len(numbers)\n\nscores = [80, 90, 100]\naverage = calculate_average(scores)\nprint(f"平均分: {average:.1f}")',
          },
        ],
      });
    case 'math':
      data.addAll({
        'title': '二次函数 · 交互演示',
        'presentation': 'panel',
        'template': 'exploration',
        'components': [
          {
            'id': 'plot',
            'type': 'math_plot',
            'xMin': -5,
            'xMax': 5,
            'yMin': -80,
            'yMax': 80,
            'equalAspect': false,
            'curves': [
              {'kind': 'function', 'label': '二次函数', 'expression': 'a*x^2'},
            ],
          },
          {
            'id': 'a',
            'type': 'slider',
            'label': '调整系数 a',
            'min': -3,
            'max': 3,
            'value': 1,
          },
        ],
        'composition': {
          'type': 'column',
          'children': ['plot', 'a'],
        },
      });
    case 'bar':
    case 'scatter':
      data.addAll({
        'title': '学习时长',
        'components': [chart('${name}_chart')],
      });
    default:
      data.addAll({
        'presentation': 'panel',
        'template': 'analysis',
        'components': [
          {
            'id': 'conclusion',
            'type': 'rich_text',
            'text': '**关键结论**\n\n周四学习 45 分钟，是本周投入最多的一天。',
          },
          metric('total', '本周累计学习时长', 155, '分钟'),
          metric('days', '学习天数', 5, '天'),
          metric('done', '完成题目', 40, '道'),
          metric('rate', '正确率', 78, '%'),
          chart('line_chart'),
          {
            'id': 'details',
            'type': 'table',
            'columns': ['日期', '时长（分钟）'],
            'rows': [
              ['周一', 20],
              ['周二', 35],
              ['周三', 25],
              ['周四', 45],
              ['周五', 30],
            ],
          },
        ],
        'composition': {
          'type': 'tabs',
          'children': [
            {
              'type': 'column',
              'label': '概览',
              'children': [
                'conclusion',
                'total',
                {
                  'type': 'grid',
                  'columns': 3,
                  'children': ['days', 'done', 'rate'],
                },
                'trend',
              ],
            },
            {
              'type': 'column',
              'label': '明细',
              'children': ['details'],
            },
          ],
        },
      });
  }
  return data;
}

void main() {
  testWidgets('Two options are a table, many options are separated cards', (
    tester,
  ) async {
    for (final name in ['two', 'many']) {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: InteractiveSurfaceView(
                definition: SurfaceDefinition.parse(fixture(name)),
                save: (_, _, _) async {},
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(Table), name == 'two' ? findsOneWidget : findsNothing);
      expect(
        find.byType(SurfaceInfoCard),
        name == 'many' ? findsNWidgets(3) : findsNothing,
      );
      expect(tester.takeException(), isNull);
    }
  });
  testWidgets(
    'Math presets update formula and curve while the viewport stays fixed',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: InteractiveSurfaceView(
                definition: SurfaceDefinition.parse(fixture('math')),
                save: (_, _, _) async {},
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final chip = find.widgetWithText(ChoiceChip, '-3');
      await tester.ensureVisible(chip);
      await tester.tap(chip);
      await tester.pumpAndSettle();
      final plot = tester.widget<MathPlotView>(find.byType(MathPlotView));
      expect(plot.parameters['a'], -3);
      expect(plot.definition['yMin'], -80);
      expect(plot.definition['yMax'], 80);
      expect(find.text('y = (-3) × x²'), findsOneWidget);
      expect(tester.widget<Slider>(find.byType(Slider)).divisions, 60);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('Capture selected styles in mobile light and dark themes', (
    tester,
  ) async {
    for (final font in <String, String>{
      'QAFont': '../../.local/flutter/bin/cache/artifacts/material_fonts/roboto-regular.ttf',
      'QAChinese': 'C:/Windows/Fonts/msyh.ttc',
      'Ahem': 'C:/Windows/Fonts/msyh.ttc',
      'monospace': 'C:/Windows/Fonts/msyh.ttc',
      'MaterialIcons': '../../.local/flutter/bin/cache/artifacts/material_fonts/materialicons-regular.otf',
    }.entries) {
      final loader = FontLoader(font.key)
        ..addFont(
          Future.value(
            ByteData.sublistView(File(font.value).readAsBytesSync()),
          ),
        );
      await tester.runAsync(loader.load);
    }
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    for (final dark in [false, true]) {
      for (final name in [
        'card',
        'two',
        'many',
        'code',
        'bar',
        'scatter',
        'math',
        'overview',
      ]) {
        tester.view.physicalSize = const Size(390, 1120);
        final key = GlobalKey();
        final theme = dark ? StudyTheme.darkTheme() : StudyTheme.lightTheme();
        await tester.pumpWidget(
          MaterialApp(
            theme: theme.copyWith(
              textTheme: theme.textTheme.apply(
                fontFamily: 'QAFont',
                fontFamilyFallback: ['QAChinese'],
              ),
            ),
            home: RepaintBoundary(
              key: key,
              child: Scaffold(
                body: SingleChildScrollView(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: InteractiveSurfaceView(
                      definition: SurfaceDefinition.parse(fixture(name)),
                      save: (_, _, _) async {},
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        final boundary =
            key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
        await tester.runAsync(() async {
          final image = await boundary.toImage(pixelRatio: 1);
          final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
          final file = File(
            '../../qa/chat-style-selected-20261011/$name-${dark ? 'dark' : 'light'}.png',
          );
          await file.parent.create(recursive: true);
          await file.writeAsBytes(bytes!.buffer.asUint8List());
          image.dispose();
        });
      }
    }
  });
}
