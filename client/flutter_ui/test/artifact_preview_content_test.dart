import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/artifact_preview_page.dart';
import 'package:flutter_ui/providers/profile_resource.dart';
import 'package:flutter_ui/providers/study_providers.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String method, Future<Object?> Function(Object?) handler) {
    messenger.setMockDecodedMessageHandler<Object?>(BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method', StudyHostApi.pigeonChannelCodec), handler);
  }
  ArtifactDto artifact({String? body, String? coverage}) => ArtifactDto(
    id: 'document', name: 'result.docx', extension: 'docx', state: 'SUCCEEDED',
    size: 64, sha256: 'digest', createdAt: 1, statusDetail: '操作已完成',
    previewContent: body, previewCoverage: coverage);
  Future<ProviderContainer> show(WidgetTester tester) async {
    mock('getBootstrap', (_) async => [StudyBootstrapDto(currentProfileId: 'A', contextEpoch: 1,
      profiles: [], studyMode: 'CHAT', reduceMotion: false, darkMode: false)]);
    mock('getArtifacts', (_) async => [[artifact()]]);
    await tester.pumpWidget(const ProviderScope(child: MaterialApp(home: ArtifactPreviewPage(
      resource: ProfileResourceRoute(id: 'document', profileId: 'A', epoch: 1)))));
    await tester.pumpAndSettle();
    return ProviderScope.containerOf(tester.element(find.byType(ArtifactPreviewPage)));
  }
  tearDown(() {
    for (final method in ['getBootstrap', 'getArtifacts', 'getArtifactPreview']) {
      messenger.setMockDecodedMessageHandler<Object?>(BasicMessageChannel<Object?>(
        'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method', StudyHostApi.pigeonChannelCodec), null);
    }
  });
  testWidgets('DOCX body is previewed separately from status and extraction scope', (tester) async {
    mock('getArtifactPreview', (_) async => [artifact(body: 'Actual document body <not markup>', coverage: 'Office 文本提取；布局未处理')]);
    await show(tester);
    expect(find.widgetWithText(SelectableText, 'Actual document body <not markup>'), findsOneWidget);
    expect(find.text('处理状态：操作已完成'), findsOneWidget);
    expect(find.textContaining('Office 文本提取'), findsOneWidget);
    expect(find.text('此格式请使用系统应用查看完整内容。'), findsNothing);
  });
  testWidgets('unsupported extraction retains system open guidance instead of status as body', (tester) async {
    mock('getArtifactPreview', (_) async => [artifact(coverage: '无法提取正文：不支持此格式')]);
    await show(tester);
    expect(find.text('此格式请使用系统应用查看完整内容。'), findsOneWidget);
    expect(find.text('无法提取正文：不支持此格式'), findsOneWidget);
    expect(find.byType(SelectableText), findsNothing);
  });
  testWidgets('late preview cannot repopulate a restored or switched detail', (tester) async {
    mock('getArtifactPreview', (_) async => [artifact(body: 'first-private-body')]);
    final container = await show(tester);
    expect(find.text('first-private-body'), findsOneWidget);
    final late = Completer<Object?>();
    mock('getArtifactPreview', (_) => late.future);
    container.invalidate(artifactPreviewProvider('document'));
    await tester.pump();
    container.read(contextEpochProvider.notifier).state = 2;
    await tester.pump();
    late.complete([artifact(body: 'late-private-body')]);
    await tester.pumpAndSettle();
    expect(find.text('first-private-body'), findsNothing);
    expect(find.text('late-private-body'), findsNothing);
    expect(find.text('成果不可用'), findsOneWidget);
  });
}
