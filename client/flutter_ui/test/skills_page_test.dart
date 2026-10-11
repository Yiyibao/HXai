import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/skills_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String name, Future<Object?> Function(Object?) handler) => messenger.setMockDecodedMessageHandler<Object?>(BasicMessageChannel<Object?>('dev.flutter.pigeon.flutter_ui.StudyHostApi.$name', StudyHostApi.pigeonChannelCodec), handler);
  testWidgets('Shows dependency limits and awaits confirmed skill enable', (tester) async {
    mock('getBootstrap', (_) async => [StudyBootstrapDto(currentProfileId: 'default-learner', contextEpoch: 1, profiles: [], studyMode: 'CHAT', reduceMotion: false, darkMode: false)]);
    var enabled = false;
    mock('getSkills', (_) async => [[SkillDto(name: 'docx', description: 'Word instructions', builtin: false, enabled: enabled, state: 'PARTIAL', detail: '缺失Python/Node；仅可加载说明', files: ['SKILL.md', 'scripts/run.py'], license: 'Source license')]]);
    mock('setSkillEnabled', (args) async { final values = args as List; expect(values[1], 'docx'); enabled = values[2] as bool; return [SimpleCommandResult(success: true)]; });
    await tester.pumpWidget(const ProviderScope(child: MaterialApp(home: SkillsPage())));
    for (var i = 0; i < 8; i++) { await tester.pump(const Duration(milliseconds: 100)); }
    expect(find.textContaining('部分支持'), findsOneWidget);
    expect(find.textContaining('缺失Python'), findsOneWidget);
    await tester.tap(find.byType(Switch)); await tester.pump(const Duration(milliseconds: 200));
    expect(enabled, isTrue); expect(tester.widget<Switch>(find.byType(Switch)).value, isTrue);
  });
}
