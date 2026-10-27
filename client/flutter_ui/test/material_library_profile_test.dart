import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/files_page.dart';
import 'package:flutter_ui/providers/frontend_feature_providers.dart';
import 'package:flutter_ui/providers/study_providers.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  Map<String, Object?> catalog(String owner) => {'contractVersion': 1, 'trash': <Object?>[], 'files': [
    {'id': owner, 'name': '$owner-private-教材.md', 'sha256': '0' * 64, 'extension': 'md', 'folder': '$owner-folder',
      'tags': ['$owner-tag'], 'createdAt': 1, 'size': 10, 'state': 'SUCCEEDED', 'detail': '原件已保存；尚未提取'},
  ]};
  final materials = BasicMessageChannel<Object?>('dev.flutter.pigeon.flutter_ui.StudyHostApi.getMaterials', StudyHostApi.pigeonChannelCodec);
  final artifacts = BasicMessageChannel<Object?>('dev.flutter.pigeon.flutter_ui.StudyHostApi.getArtifacts', StudyHostApi.pigeonChannelCodec);
  setUp(() {
    messenger.setMockDecodedMessageHandler<Object?>(materials, (_) async => [<MaterialFileDto>[]]);
    messenger.setMockDecodedMessageHandler<Object?>(artifacts, (_) async => [<ArtifactDto>[]]);
  });
  tearDown(() {
    messenger.setMockMethodCallHandler(FrontendFeatures.channel, null);
    messenger.setMockDecodedMessageHandler<Object?>(materials, null);
    messenger.setMockDecodedMessageHandler<Object?>(artifacts, null);
  });
  testWidgets('Late old-profile library snapshot cannot publish private catalogue into the new profile', (tester) async {
    final delayed = Completer<Map<String, Object?>>();
    var oldRequestPending = false;
    messenger.setMockMethodCallHandler(FrontendFeatures.channel, (call) async {
      if (call.method != 'library.snapshot') throw PlatformException(code: 'UNEXPECTED');
      final owner = (call.arguments as Map)['profileId'] as String;
      if (owner == 'A') { oldRequestPending = true; return delayed.future; }
      return catalog(owner);
    });
    final container = ProviderContainer(); addTearDown(container.dispose);
    container.read(activeProfileIdProvider.notifier).state = 'A';
    await tester.pumpWidget(UncontrolledProviderScope(container: container, child: const MaterialApp(home: FilesPage())));
    await tester.pump();
    expect(oldRequestPending, isTrue);
    container.read(activeProfileIdProvider.notifier).state = 'B';
    await tester.pumpAndSettle();
    expect(find.text('B-private-教材.md'), findsOneWidget);
    delayed.complete(catalog('A'));
    await tester.pumpAndSettle();
    expect(find.text('A-private-教材.md'), findsNothing);
    expect(find.text('A-folder'), findsNothing);
    expect(find.text('A-tag'), findsNothing);
    expect(find.text('B-private-教材.md'), findsOneWidget);
  });
}
