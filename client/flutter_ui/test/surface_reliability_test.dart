import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/interactive_surface.dart';

SurfaceDefinition spec({String title = '预算', double value = 2}) =>
    SurfaceDefinition.parse({
      'version': 3,
      'title': title,
      'components': [
        {
          'id': 'amount',
          'type': 'slider',
          'label': '人数',
          'min': 1,
          'max': 10,
          'value': value,
        },
        {'id': 'go', 'type': 'button', 'label': '继续分析', 'action': 'continue'},
      ],
    });

Widget page(Widget child) => MaterialApp(
  home: Scaffold(body: SingleChildScrollView(child: child)),
);

void main() {
  testWidgets('Narrow screen with large text keeps long tables and controls usable', (tester) async {
    tester.view.physicalSize = const Size(320, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final definition = SurfaceDefinition.parse({
      'version': 3, 'title': '文件数据与预算',
      'components': [
        {'id': 'data', 'type': 'table', 'columns': ['项目', '数量'],
          'rows': List.generate(200, (index) => ['项目 $index', index])},
        {'id': 'amount', 'type': 'slider', 'label': '预算人数', 'min': 1, 'max': 10, 'value': 2},
        {'id': 'go', 'type': 'button', 'label': '继续分析', 'action': 'continue'},
      ],
    });
    await tester.pumpWidget(MaterialApp(
      theme: ThemeData(brightness: Brightness.dark),
      home: MediaQuery(data: const MediaQueryData(textScaler: TextScaler.linear(1.8)),
        child: Scaffold(body: SingleChildScrollView(child: InteractiveSurfaceView(
          definition: definition, save: (_, _, _) async {},
        ))),
      ),
    ));
    await tester.pump();
    final tableViewport = find.byWidgetPredicate((widget) =>
      widget is ConstrainedBox && widget.constraints.maxHeight == 320);
    expect(tester.getSize(tableViewport).height, lessThanOrEqualTo(320));
    await tester.ensureVisible(find.text('继续分析'));
    await tester.tap(find.text('继续分析'));
    await tester.pump();
    expect(find.text('已提交，AI 将继续分析'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Load failure disables actions until saved state is recovered', (
    tester,
  ) async {
    var attempts = 0;
    await tester.pumpWidget(
      page(
        InteractiveSurfaceView(
          definition: spec(),
          load: () async {
            if (++attempts == 1) throw StateError('offline');
            return {'amount': 7};
          },
          save: (_, _, _) async {},
        ),
      ),
    );
    await tester.pump();
    expect(tester.widget<Slider>(find.byType(Slider)).onChanged, isNull);
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    await tester.tap(find.text('重试加载界面'));
    await tester.pump();
    expect(tester.widget<Slider>(find.byType(Slider)).value, 7);
    expect(find.text('状态已同步 · 可交互探索'), findsOneWidget);
  });

  testWidgets('Leaving before debounce flushes the latest state', (
    tester,
  ) async {
    Map<String, dynamic>? saved;
    await tester.pumpWidget(
      page(
        InteractiveSurfaceView(
          definition: spec(),
          save: (values, _, _) async {
            saved = values;
          },
        ),
      ),
    );
    await tester.pump();
    tester.widget<Slider>(find.byType(Slider)).onChanged!(8);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    expect(saved, {'amount': 8});
    expect(tester.takeException(), isNull);
  });

  testWidgets('Late saved-state load cannot overwrite a newer definition', (
    tester,
  ) async {
    final old = Completer<Map<String, dynamic>>();
    await tester.pumpWidget(
      page(
        InteractiveSurfaceView(
          definition: spec(),
          load: () => old.future,
          save: (_, _, _) async {},
        ),
      ),
    );
    await tester.pumpWidget(
      page(
        InteractiveSurfaceView(
          definition: spec(title: '新预算', value: 4),
          load: () async => {'amount': 5},
          save: (_, _, _) async {},
        ),
      ),
    );
    await tester.pump();
    old.complete({'amount': 9});
    await tester.pump();
    expect(tester.widget<Slider>(find.byType(Slider)).value, 5);
    expect(find.text('新预算'), findsOneWidget);
  });

  testWidgets(
    'A slow autosave keeps later edits dirty and sends the latest snapshot',
    (tester) async {
      final first = Completer<void>();
      final saved = <Map<String, dynamic>>[];
      await tester.pumpWidget(
        page(
          InteractiveSurfaceView(
            definition: spec(),
            save: (values, _, _) async {
              saved.add(values);
              if (saved.length == 1) await first.future;
            },
          ),
        ),
      );
      await tester.pump();
      tester.widget<Slider>(find.byType(Slider)).onChanged!(3);
      await tester.pump(const Duration(milliseconds: 450));
      tester.widget<Slider>(find.byType(Slider)).onChanged!(6);
      first.complete();
      await tester.pump();
      expect(find.text('有修改待保存'), findsOneWidget);
      await tester.pump(const Duration(milliseconds: 450));
      expect(saved.last, {'amount': 6});
      expect(find.text('状态已同步 · 可交互探索'), findsOneWidget);
    },
  );

  testWidgets(
    'Autosave failure is visible and retry preserves the edited value',
    (tester) async {
      var count = 0;
      Map<String, dynamic>? saved;
      await tester.pumpWidget(
        page(
          InteractiveSurfaceView(
            definition: spec(),
            save: (values, _, _) async {
              if (++count == 1) throw StateError('offline');
              saved = values;
            },
          ),
        ),
      );
      await tester.pump();
      tester.widget<Slider>(find.byType(Slider)).onChanged!(8);
      await tester.pump(const Duration(milliseconds: 450));
      expect(find.textContaining('修改尚未保存'), findsOneWidget);
      await tester.tap(find.text('重试保存修改'));
      await tester.pump();
      expect(saved, {'amount': 8});
      expect(find.text('状态已同步 · 可交互探索'), findsOneWidget);
    },
  );

  testWidgets(
    'Expanded view saves before closing and updates the inline controls',
    (tester) async {
      Map<String, dynamic>? saved;
      await tester.pumpWidget(
        page(
          InteractiveSurfaceView(
            definition: spec(),
            save: (values, _, _) async {
              saved = values;
            },
          ),
        ),
      );
      await tester.pump();
      await tester.tap(find.byTooltip('展开界面'));
      await tester.pumpAndSettle();
      expect(
        find.descendant(
          of: find.byType(Dialog),
          matching: find.byTooltip('展开界面'),
        ),
        findsNothing,
      );
      tester.widget<Slider>(find.byType(Slider).last).onChanged!(9);
      await tester.tap(find.byTooltip('保存并关闭'));
      await tester.pumpAndSettle();
      expect(saved, {'amount': 9});
      expect(tester.widget<Slider>(find.byType(Slider)).value, 9);
      expect(tester.takeException(), isNull);
    },
  );
}
