import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/interactive_surface.dart';

Map<String, dynamic> budget() => {
  'version': 1,
  'title': '预算对比',
  'components': [
    {
      'id': 'people',
      'type': 'slider',
      'label': '人数',
      'min': 1,
      'max': 10,
      'value': 2,
    },
    {
      'id': 'total',
      'type': 'calculation',
      'label': '合计',
      'parameter': 'people',
      'a': 100,
      'b': 20,
    },
    {'id': 'go', 'type': 'button', 'label': '继续分析', 'action': 'continue'},
  ],
};
void main() {
  test(
    'Rejects invalid structure and unknown commands with readable failure',
    () {
      expect(
        () => SurfaceDefinition.parse({...budget(), 'version': 5}),
        throwsFormatException,
      );
      expect(
        () => SurfaceDefinition.parse({
          ...budget(),
          'components': [
            {'id': 'x', 'type': 'html', 'text': '<script/>'},
          ],
        }),
        throwsFormatException,
      );
      expect(
        () => SurfaceDefinition.parse({
          ...budget(),
          'components': [
            {'id': 'x', 'type': 'button', 'label': 'Run', 'action': 'execute'},
          ],
        }),
        throwsFormatException,
      );
    },
  );

  testWidgets(
    'Budget slider recalculates locally and only button submits action',
    (tester) async {
      final actions = <String?>[];
      Map<String, dynamic>? submitted;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: InteractiveSurfaceView(
              definition: SurfaceDefinition.parse(budget()),
              save: (values, action, _) async {
                actions.add(action);
                submitted = values;
              },
            ),
          ),
        ),
      );
      await tester.pump();
      expect(find.text('220'), findsOneWidget);
      tester.widget<Slider>(find.byType(Slider)).onChanged!(5);
      await tester.pump(const Duration(milliseconds: 450));
      expect(find.text('520'), findsOneWidget);
      expect(actions.whereType<String>(), isEmpty);
      await tester.tap(find.text('继续分析'));
      await tester.pump();
      expect(actions.last, 'go');
      expect(submitted!['people'], 5);
    },
  );

  testWidgets('Saved state reloads for a historical surface', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: InteractiveSurfaceView(
            definition: SurfaceDefinition.parse(budget()),
            load: () async => {'people': 7},
            save: (_, _, _) async {},
          ),
        ),
      ),
    );
    await tester.pump();
    expect(find.text('720'), findsOneWidget);
  });

  testWidgets('File data table and function chart render in the same catalog', (
    tester,
  ) async {
    final spec = SurfaceDefinition.parse({
      'version': 1,
      'title': '文件数据与函数',
      'components': [
        {
          'id': 'data',
          'type': 'table',
          'columns': ['项目', '数值'],
          'rows': [
            ['A', 2],
            ['B', 4],
          ],
        },
        {
          'id': 'factor',
          'type': 'slider',
          'label': '倍数',
          'min': 1,
          'max': 5,
          'value': 2,
        },
        {
          'id': 'function',
          'type': 'line_chart',
          'points': [
            [0, 0],
            [1, 1],
            [2, 4],
          ],
          'scale': 'factor',
        },
      ],
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: InteractiveSurfaceView(
              definition: spec,
              save: (_, _, _) async {},
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    expect(find.byType(DataTable), findsOneWidget);
    expect(find.text('A'), findsOneWidget);
    await tester.ensureVisible(find.text('查看图表数据'));
    await tester.tap(find.text('查看图表数据'));
    await tester.pump(const Duration(milliseconds: 250));
    expect(find.textContaining('2.0: 8.0'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'Stored selection replaces default and failed action retries same request',
    (tester) async {
      final requests = <String>[];
      final spec = SurfaceDefinition.parse({
        'version': 1,
        'title': '选项',
        'components': [
          {
            'id': 'choice',
            'type': 'select',
            'label': '方案',
            'options': ['A', 'B'],
            'value': 'A',
          },
          {'id': 'go', 'type': 'button', 'label': '提交', 'action': 'continue'},
        ],
      });
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: InteractiveSurfaceView(
              definition: spec,
              load: () async => {'choice': 'B'},
              save: (_, action, request) async {
                if (action != null) {
                  requests.add(request);
                  if (requests.length == 1)
                    throw StateError('synthetic failure');
                }
              },
            ),
          ),
        ),
      );
      await tester.pump();
      final dropdown = tester.widget<DropdownButtonFormField<String>>(
        find.byType(DropdownButtonFormField<String>),
      );
      expect(dropdown.initialValue, 'B');
      await tester.tap(find.text('提交'));
      await tester.pump();
      await tester.tap(find.text('提交'));
      await tester.pump();
      expect(requests.length, 2);
      expect(requests[0], requests[1]);
    },
  );

  testWidgets('Version two file table filters locally before continuation', (
    tester,
  ) async {
    final spec = SurfaceDefinition.parse({
      'version': 2,
      'title': '文件筛选',
      'components': [
        {
          'id': 'group',
          'type': 'select',
          'label': '类别',
          'options': ['*', 'A', 'B'],
          'value': '*',
        },
        {
          'id': 'data',
          'type': 'table',
          'columns': ['类别', '数值'],
          'rows': [
            ['A', 10],
            ['B', 20],
          ],
          'filter': 'group',
          'filterColumn': 0,
        },
        {'id': 'go', 'type': 'button', 'label': '分析所选', 'action': 'continue'},
      ],
    });
    Map<String, dynamic>? sent;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: InteractiveSurfaceView(
            definition: spec,
            save: (values, action, _) async {
              if (action != null) sent = values;
            },
          ),
        ),
      ),
    );
    await tester.pump();
    expect(find.text('20'), findsOneWidget);
    tester
        .widget<DropdownButtonFormField<String>>(
          find.byType(DropdownButtonFormField<String>),
        )
        .onChanged!('A');
    await tester.pump();
    expect(find.text('20'), findsNothing);
    expect(find.text('10'), findsOneWidget);
    await tester.tap(find.text('分析所选'));
    await tester.pump();
    expect(sent!['group'], 'A');
  });
}
