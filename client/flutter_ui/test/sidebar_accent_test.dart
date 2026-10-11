import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/conversation_drawer.dart';
import 'package:flutter_ui/providers/app_settings_providers.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/theme/study_theme.dart';
import 'package:flutter_ui/widgets/accent_color_picker.dart';
import 'package:flutter_ui/widgets/project_groups.dart';

class _Projects extends ProjectsNotifier {
  @override
  Future<List<ProjectDto>> build() async => [];
}

class _Conversations extends ConversationsNotifier {
  @override
  Future<List<ConversationSummaryDto>> build() async => [];
}

class _TestApp extends ConsumerWidget {
  const _TestApp({required this.child, this.dark = false});
  final Widget child;
  final bool dark;
  @override
  Widget build(BuildContext context, WidgetRef ref) => MaterialApp(
    theme: dark
        ? StudyTheme.darkTheme(accent: ref.watch(accentColorProvider))
        : StudyTheme.lightTheme(accent: ref.watch(accentColorProvider)),
    home: Scaffold(body: child),
  );
}

double contrast(Color a, Color b) {
  final x = a.computeLuminance();
  final y = b.computeLuminance();
  return (x > y ? x + 0.05 : y + 0.05) / (x > y ? y + 0.05 : x + 0.05);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  setUp(() {
    AppearanceStorage.values = {};
    messenger.setMockMethodCallHandler(
      AppearanceStorage.channel,
      (_) async => null,
    );
  });
  tearDown(() {
    AppearanceStorage.values = {};
    messenger.setMockMethodCallHandler(AppearanceStorage.channel, null);
  });

  test('All accent choices preserve readable text in both appearances', () {
    for (final dark in [false, true]) {
      for (final color in AccentColor.values) {
        final c = StudyPalette(dark, color);
        expect(contrast(c.textBlack, c.userBubble), greaterThanOrEqualTo(4.5));
        expect(contrast(c.accent, c.onAccent), greaterThanOrEqualTo(4.5));
        expect(c.errorRed, StudyPalette(dark).errorRed);
        expect(c.bgWhite, StudyPalette(dark).bgWhite);
      }
    }
    expect(AccentColor.parse('unknown'), AccentColor.pink);
    expect(AccentColor.neutral.labelFor(true), '白色');
  });

  test(
    'Accent choice survives platform load and provider recreation',
    () async {
      final saved = <String, dynamic>{};
      messenger.setMockMethodCallHandler(AppearanceStorage.channel, (
        call,
      ) async {
        if (call.method == 'load') return saved;
        saved.addAll(Map<String, dynamic>.from(call.arguments as Map));
        return null;
      });
      final before = ProviderContainer();
      before.read(accentColorProvider.notifier).setColor(AccentColor.purple);
      await Future<void>.delayed(Duration.zero);
      before.dispose();
      await AppearanceStorage.load();
      final after = ProviderContainer();
      expect(after.read(accentColorProvider), AccentColor.purple);
      after.dispose();
    },
  );

  for (final dark in [false, true]) {
    testWidgets('Picker updates preview and theme immediately (dark=$dark)', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final container = ProviderContainer();
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: _TestApp(dark: dark, child: const AccentColorSetting()),
        ),
      );
      await tester.tap(find.text('高亮色'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accent-blue')));
      await tester.pumpAndSettle();
      expect(container.read(accentColorProvider), AccentColor.blue);
      final preview = tester.widget<Container>(
        find.byKey(const ValueKey('accent-preview')),
      );
      expect(
        (preview.decoration as BoxDecoration).color,
        StudyPalette(dark, AccentColor.blue).selectionFill,
      );
      await tester.tap(find.text('完成'));
      await tester.pumpAndSettle();
      final c = StudyPalette.of(
        tester.element(find.byType(AccentColorSetting)),
      );
      expect(c.accentColor, AccentColor.blue);
      expect(tester.takeException(), isNull);
    });

    testWidgets(
      'Small sidebar scrolls with placeholder projects and fixed footer (dark=$dark)',
      (tester) async {
        tester.view.physicalSize = const Size(320, 640);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        await tester.pumpWidget(
          ProviderScope(
            overrides: [
              bootstrapProvider.overrideWith(
                (_) async => StudyBootstrapDto(
                  currentProfileId: 'test',
                  contextEpoch: 1,
                  profiles: [],
                  activeModelId: '',
                  activeModelName: '',
                  studyMode: 'CHAT',
                  reduceMotion: false,
                  darkMode: dark,
                ),
              ),
              conversationsProvider.overrideWith(_Conversations.new),
              projectsProvider.overrideWith(_Projects.new),
            ],
            child: _TestApp(
              dark: dark,
              child: Builder(
                builder: (context) => MediaQuery(
                  data: MediaQuery.of(context)
                      .copyWith(textScaler: const TextScaler.linear(1.5)),
                  child: const ConversationDrawer(),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.text('图片'), findsOneWidget);
        await tester.tap(find.text('项目'));
        await tester.pumpAndSettle();
        expect(find.text('点击 +，按需求整理聊天'), findsOneWidget);
        expect(find.text('个人档案'), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
