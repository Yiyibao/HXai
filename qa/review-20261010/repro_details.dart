import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/pages/file_detail_page.dart';
import 'package:flutter_ui/pages/memory_detail_page.dart';
import 'package:flutter_ui/pages/artifact_preview_page.dart';

// Audit probes assert safe behavior; failures reproduce current defects.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  void mock(String method, Object value) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<Object?>(BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method',
      StudyHostApi.pigeonChannelCodec,
    ), (_) async => [value]);
  }
  testWidgets('B profile must not display A file from route extra', (tester) async {
    mock('getMaterials', <MaterialFileDto>[]);
    final container = ProviderContainer();
    container.read(activeProfileIdProvider.notifier).state = 'B';
    await tester.pumpWidget(UncontrolledProviderScope(container: container,
      child: MaterialApp(home: FileDetailPage(file: MaterialFileDto(
        id: 'A-file', profileId: 'A', name: 'A-private-file.txt',
        extension: 'txt', state: 'SUCCEEDED', detail: 'A-private-detail',
        size: 1, sha256: '', createdAt: 0, symbolCheckPending: false,
      ))),
    ));
    await tester.pumpAndSettle();
    expect(find.text('A-private-file.txt'), findsNothing);
    await tester.pumpWidget(const SizedBox());
    container.dispose();
  });
  testWidgets('Deleted memory must not reappear from route extra', (tester) async {
    mock('getMemories', <MemoryItemDto>[]);
    await tester.pumpWidget(ProviderScope(child: MaterialApp(
      home: MemoryDetailPage(memory: MemoryItemDto(
        id: 'deleted-memory', type: 'PREFERENCE', content: 'deleted-private-content',
        sourceTopic: '', sourceFact: '', evidenceType: 'SELF_REPORTED',
        status: 'ACTIVE', revision: 1, createdAt: 0, updatedAt: 0,
      )),
    )));
    await tester.pumpAndSettle();
    expect(find.text('deleted-private-content'), findsNothing);
  });
  testWidgets('Artifact metadata must not be presented as document body', (tester) async {
    mock('getArtifacts', <ArtifactDto>[ArtifactDto(
      id: 'artifact', name: 'report.docx', extension: 'docx', state: 'SUCCEEDED',
      size: 1, sha256: '', createdAt: 0, previewContent: 'operation-status-only',
    )]);
    await tester.pumpWidget(const ProviderScope(child: MaterialApp(
      home: ArtifactPreviewPage(artifactId: 'artifact'),
    )));
    await tester.pumpAndSettle();
    expect(find.textContaining('此格式请使用系统应用'), findsOneWidget);
  });
}
