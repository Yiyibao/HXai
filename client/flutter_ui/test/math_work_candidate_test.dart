import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/pages/math_work_page.dart';
import 'package:flutter_ui/providers/frontend_feature_providers.dart';
import 'package:flutter_ui/providers/material_library_providers.dart';
import 'package:flutter_ui/providers/study_providers.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final entry = LibraryEntry({'id': 'source-A', 'sha256': 'a' * 64, 'name': '数学原稿.pdf', 'extension': 'pdf', 'state': 'SUCCEEDED'});
  final candidates = jsonEncode({'results': [{'task_id': 'parse-A', 'start_page': 1, 'end_page': 2, 'downloaded': true, 'processed_pages': [1, 2]}]});
  final candidate = jsonEncode({'reference': {'task_id': 'parse-A', 'result_revision': 1, 'candidate_sha256': 'b' * 64},
    'untrusted_candidate': {'transcription': 'x+x=3*x', 'coverage': {'complete': false}}, 'ambiguities': ['核对负号和上下标']});
  Future<ProviderContainer> show(WidgetTester tester, Future<Object?> Function(String) handler) async {
    messenger.setMockMethodCallHandler(FrontendFeatures.channel, (call) async {
      if (call.method == 'library.mathWork.list') return jsonEncode({'items': [], 'next_offset': 0, 'has_more': false});
      return handler(call.method);
    });
    final container = ProviderContainer(overrides: [materialLibraryProvider.overrideWith((ref) async => LibrarySnapshot(
      LibraryAccess(ref.watch(activeProfileIdProvider), ref.watch(contextEpochProvider)), {'files': [entry.data], 'trash': []}))]);
    addTearDown(container.dispose);
    container.read(activeProfileIdProvider.notifier).state = 'A';
    final access = LibraryAccess('A', container.read(contextEpochProvider));
    await tester.pumpWidget(UncontrolledProviderScope(container: container, child: MaterialApp(home: MathWorkPage(access: access, initial: entry))));
    await tester.pumpAndSettle();
    return container;
  }
  Future<void> choose(WidgetTester tester) async {
    final button = find.text('载入已有解析/OCR候选（不上传）');
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pumpAndSettle();
    await tester.ensureVisible(button); await tester.pumpAndSettle();
    await tester.tap(button); await tester.pump(); await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(find.text('物理页 1–2 · 已下载')); await tester.pump(); await tester.pump(const Duration(milliseconds: 300));
  }
  Finder transcription() => find.widgetWithText(TextField, '对照原页的完整转录，一步一行；保留原方法');
  tearDown(() => messenger.setMockMethodCallHandler(FrontendFeatures.channel, null));

  testWidgets('Declining candidate replacement preserves the users unsaved correction', (tester) async {
    await show(tester, (method) async => method.endsWith('parseResults') ? candidates : candidate);
    await tester.ensureVisible(transcription()); await tester.enterText(transcription(), 'x+x=2*x');
    await choose(tester);
    expect(find.text('用候选替换本页未保存的转录？'), findsOneWidget);
    await tester.tap(find.text('保留当前转录')); await tester.pumpAndSettle();
    expect(tester.widget<TextField>(transcription()).controller!.text, 'x+x=2*x');
    expect(find.text('本页由已下载解析候选起草；可修订转录。请对照原页核对，再清除已解决的歧义。'), findsNothing);
  });
  testWidgets('A late candidate cannot populate the changed profile', (tester) async {
    final delayed = Completer<Object?>();
    final container = await show(tester, (method) async => method.endsWith('parseResults') ? candidates : delayed.future);
    await choose(tester);
    container.read(activeProfileIdProvider.notifier).state = 'B'; await tester.pump();
    delayed.complete(candidate); await tester.pumpAndSettle();
    expect(find.text('档案已改变，请重新打开；旧确认不会提交到新档案。'), findsOneWidget);
    expect(find.text('x+x=3*x'), findsNothing);
  });
  testWidgets('An open candidate chooser hides old owner data when epoch changes', (tester) async {
    final container = await show(tester, (method) async => candidates);
    final button = find.text('载入已有解析/OCR候选（不上传）');
    await tester.ensureVisible(button); await tester.tap(button); await tester.pump(); await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('物理页 1–2 · 已下载'), findsOneWidget);
    container.read(contextEpochProvider.notifier).state++;
    await tester.pump(); await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('物理页 1–2 · 已下载'), findsNothing);
    expect(find.text('档案已改变；旧候选不再显示'), findsOneWidget);
    await tester.tap(find.text('关闭')); await tester.pumpAndSettle();
  });
}
