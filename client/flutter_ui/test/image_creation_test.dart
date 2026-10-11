import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/pages/image_creation_page.dart';
import 'package:flutter_ui/providers/image_creation_providers.dart';
import 'package:flutter_ui/providers/frontend_feature_providers.dart';
import 'package:flutter_ui/providers/app_settings_providers.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/theme/study_theme.dart';
import 'package:flutter_ui/widgets/image_generation_loading.dart';

class _App extends ConsumerWidget {
  final Widget child;
  final bool dark;
  final double scale;
  const _App(this.child, {this.dark = false, this.scale = 1});
  @override
  Widget build(BuildContext context, WidgetRef ref) => MaterialApp(
    theme: dark
        ? StudyTheme.darkTheme(accent: ref.watch(accentColorProvider))
        : StudyTheme.lightTheme(accent: ref.watch(accentColorProvider)),
    builder: (_, child) => MediaQuery(
      data: MediaQueryData(
        size: const Size(320, 640),
        textScaler: TextScaler.linear(scale),
      ),
      child: child!,
    ),
    home: Scaffold(
      body: Align(alignment: Alignment.bottomCenter, child: child),
    ),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  setUp(() {
    messenger.setMockMethodCallHandler(
      FrontendFeatures.channel,
      (call) async => throw PlatformException(code: 'FAILED', message: '接口未完成'),
    );
  });
  tearDown(
    () => messenger.setMockMethodCallHandler(FrontendFeatures.channel, null),
  );
  testWidgets('Loading honors reduced motion and live theme changes', (
    tester,
  ) async {
    Widget app(bool dark, AccentColor accent, bool reduced) => MaterialApp(
      theme: dark
          ? StudyTheme.darkTheme(accent: accent)
          : StudyTheme.lightTheme(accent: accent),
      home: MediaQuery(
        data: MediaQueryData(disableAnimations: reduced),
        child: const SizedBox(
          width: 300,
          height: 300,
          child: ImageGenerationLoading(),
        ),
      ),
    );
    await tester.pumpWidget(app(false, AccentColor.blue, false));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('生成中'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(tester.binding.hasScheduledFrame, isTrue);
    await tester.pumpWidget(app(true, AccentColor.purple, true));
    await tester.pumpAndSettle();
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    expect(tester.takeException(), isNull);
  });
  testWidgets('Dot wave shows only supplied progress percentages', (
    tester,
  ) async {
    Widget app(double? progress) => MaterialApp(
      home: MediaQuery(
        data: const MediaQueryData(disableAnimations: true),
        child: SizedBox(
          width: 300,
          height: 300,
          child: ImageGenerationLoading(progress: progress),
        ),
      ),
    );
    await tester.pumpWidget(app(0.38));
    await tester.pumpAndSettle();
    expect(find.text('38%'), findsOneWidget);
    await tester.pumpWidget(app(0.70));
    await tester.pumpAndSettle();
    expect(find.text('70%'), findsOneWidget);
    await tester.pumpWidget(app(null));
    await tester.pumpAndSettle();
    expect(find.text('生成中'), findsOneWidget);
    expect(find.textContaining('%'), findsNothing);
  });
  testWidgets('Accessibility draft updates enable submission', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          imageTasksProvider.overrideWith((_) async => []),
          imageConfigProvider.overrideWith(
            (_) async => {
              'ready': true,
              'model': 'gpt-image-2',
              'protocol': 'images',
            },
          ),
        ],
        child: const _App(ImageCreationInput()),
      ),
    );
    await tester.pumpAndSettle();
    final send = find.byWidgetPredicate(
      (w) => w is IconButton && w.tooltip == '生成图片',
    );
    expect(tester.widget<IconButton>(send).onPressed, isNull);
    tester.widget<TextField>(find.byType(TextField)).controller!.text =
        'A blue circle';
    await tester.pump();
    expect(tester.widget<IconButton>(send).onPressed, isNotNull);
    await tester.tap(send);
    await tester.pumpAndSettle();
    expect(
      tester.widget<TextField>(find.byType(TextField)).controller!.text,
      'A blue circle',
    );
    expect(tester.takeException(), isNull);
  });
  testWidgets('Failed task never claims a generated image', (tester) async {
    final task = ImageCreationTask({
      'id': 'failed',
      'conversationId': 'chat',
      'messageId': 'message',
      'prompt': 'sample',
      'rootId': 'failed',
      'model': 'image-model',
      'state': 'FAILED',
      'size': '1024x1024',
      'quality': 'low',
      'revision': 1,
      'createdAt': 1,
      'error': '图片服务暂时不可用',
    });
    await tester.pumpWidget(
      ProviderScope(
        child: _App(
          SingleChildScrollView(child: ImageCreationCard(task: task)),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('未完成'), findsOneWidget);
    expect(find.textContaining('AI 生成'), findsNothing);
    expect(tester.takeException(), isNull);
  });
  for (final dark in [false, true]) {
    testWidgets(
      'Image composer follows live accent and fits large text (dark=$dark)',
      (tester) async {
        tester.view.physicalSize = const Size(320, 640);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final container = ProviderContainer(
          overrides: [
            imageTasksProvider.overrideWith((_) async => []),
            imageConfigProvider.overrideWith(
              (_) async => {'ready': true, 'model': 'gpt-6.1-sol'},
            ),
          ],
        );
        addTearDown(container.dispose);
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: _App(const ImageCreationInput(), dark: dark, scale: 1.3),
          ),
        );
        await tester.pumpAndSettle();
        await tester.enterText(find.byType(TextField), '一只白猫');
        container.read(accentColorProvider.notifier).setColor(AccentColor.blue);
        await tester.pumpAndSettle();
        final button = tester.widget<IconButton>(
          find.byWidgetPredicate((w) => w is IconButton && w.tooltip == '生成图片'),
        );
        expect(
          button.style!.backgroundColor!.resolve({}),
          StudyPalette(dark, AccentColor.blue).accent,
        );
        expect(tester.takeException(), isNull);
        await tester.tap(
          find.byWidgetPredicate((w) => w is IconButton && w.tooltip == '生成图片'),
        );
        await tester.pumpAndSettle();
        expect(
          tester.widget<TextField>(find.byType(TextField)).controller!.text,
          '一只白猫',
        );
        expect(find.textContaining('接口未完成'), findsOneWidget);
      },
    );
    testWidgets('Image library has truthful empty state (dark=$dark)', (
      tester,
    ) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [imageTasksProvider.overrideWith((_) async => [])],
          child: _App(const ImageCreationPage(), dark: dark),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('创建第一张图片'), findsOneWidget);
      expect(find.text('创建图片'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }
  testWidgets('Viewer revokes image content when profile changes', (
    tester,
  ) async {
    final container = ProviderContainer(
      overrides: [imageTasksProvider.overrideWith((_) async => [])],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const _App(
          ImageCreationViewer(taskId: 'id', owner: 'default-learner', epoch: 1),
        ),
      ),
    );
    await tester.pumpAndSettle();
    container.read(activeProfileIdProvider.notifier).state = 'another';
    await tester.pumpAndSettle();
    expect(find.text('档案已改变，请重新打开图片'), findsOneWidget);
    expect(find.byType(ImageCreationInput), findsNothing);
  });
}
