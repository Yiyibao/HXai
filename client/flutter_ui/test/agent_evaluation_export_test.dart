import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/providers/frontend_feature_providers.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/agent_evaluation_export.dart';
import 'agent_task_catalog_test.dart' show bootstrap, resource;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(Future<Object?> Function(MethodCall) export) => messenger.setMockMethodCallHandler(FrontendFeatures.channel,
      (call) async => call.method == 'evaluation.last' ? null : await export(call));
  tearDown(() => messenger.setMockMethodCallHandler(FrontendFeatures.channel, null));
  Future<ProviderContainer> show(WidgetTester tester, {bool large = false}) async {
    final container = ProviderContainer(overrides: [bootstrapProvider.overrideWith((_) async => bootstrap())]);
    addTearDown(container.dispose);
    await container.read(bootstrapProvider.future);
    container.read(activeProfileIdProvider); container.read(contextEpochProvider);
    await tester.pumpWidget(UncontrolledProviderScope(container: container, child: MaterialApp(
      theme: ThemeData(brightness: large ? Brightness.dark : Brightness.light),
      builder: (context, child) => MediaQuery(data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(large ? 2 : 1)), child: child!),
      home: const Scaffold(body: SingleChildScrollView(child: AgentEvaluationExport(resource: resource, revision: 2, fence: 8))),
    )));
    await tester.pumpAndSettle(); return container;
  }
  Future<void> begin(WidgetTester tester, {String label = '保存任务评测快照'}) async {
    await tester.tap(find.widgetWithText(TextButton, label));await tester.pumpAndSettle();
    await tester.ensureVisible(find.byType(FilledButton));
    await tester.tap(find.byType(FilledButton));
    await tester.pump();await tester.pump();
  }
  Map<String, Object?> receipt() => {'owner': 'owner', 'taskId': 'task', 'artifactId': 'artifact'};

  testWidgets('read and cancelled consent do not export', (tester) async {
    var count = 0;
    mock((_) async { count++;return receipt(); });
    await show(tester);expect(count, 0);
    await tester.tap(find.text('保存任务评测快照'));await tester.pumpAndSettle();
    await tester.tap(find.text('不保存'));await tester.pumpAndSettle();expect(count, 0);
  });
  testWidgets('lost ack reuses request and explicit new snapshot changes identity', (tester) async {
    final requests = <Map>[];
    mock((call) async {
      requests.add(Map.of(call.arguments as Map));
      if (requests.length == 1) throw PlatformException(code: 'lost_ack');
      return receipt();
    });
    await show(tester);await begin(tester);await tester.pumpAndSettle();
    await begin(tester, label: '核验原导出请求');await tester.pumpAndSettle();
    expect(requests[0], requests[1]);expect(requests[0]['revision'], 2);expect(requests[0]['fence'], 8);
    expect(requests[0]['consent'], true);expect(find.text('快照已独立回读并保存；模型与数学质量待评估。'), findsOneWidget);
    await begin(tester, label: '生成新快照');await tester.pumpAndSettle();
    expect(requests[2]['requestId'], isNot(requests[1]['requestId']));
  });
  testWidgets('wrong owner receipt cannot publish success', (tester) async {
    mock((_) async => {...receipt(), 'owner': 'foreign'});
    await show(tester);await begin(tester);await tester.pumpAndSettle();
    expect(find.text('预览或另存快照'), findsNothing);
    expect(find.text('核验原导出请求'), findsOneWidget);
  });
  testWidgets('old epoch callback discarded and dark large text stays usable', (tester) async {
    final ack = Completer<Object?>();
    mock((_) => ack.future);
    final container = await show(tester, large: true);await begin(tester);
    container.read(contextEpochProvider.notifier).state = 2;await tester.pump();
    ack.complete(receipt());await tester.pumpAndSettle();
    expect(find.text('预览或另存快照'), findsNothing);expect(tester.takeException(), isNull);
  });
  testWidgets('reopened page discovers durable old revision before any capture', (tester) async {
    Map? submitted;
    messenger.setMockMethodCallHandler(FrontendFeatures.channel, (call) async {
      if (call.method == 'evaluation.last') return {'owner': 'owner', 'taskId': 'task', 'requestId': 'original-request', 'revision': '1', 'fence': '4'};
      submitted = call.arguments as Map;return receipt();
    });
    await show(tester);await begin(tester);await tester.pumpAndSettle();
    expect(submitted!['requestId'], 'original-request');expect(submitted!['revision'], 1);expect(submitted!['fence'], 4);
  });
}
