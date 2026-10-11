import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/pages/image_create_studio.dart';
import 'package:flutter_ui/providers/image_creation_providers.dart';
import 'package:flutter_ui/providers/frontend_feature_providers.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/theme/study_theme.dart';

class _Profile extends ActiveProfileIdNotifier {
  @override
  String build() => 'owner';
}

class _Epoch extends ContextEpochNotifier {
  @override
  int build() => 0;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <String>[];
  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(FrontendFeatures.channel, (call) async {
          calls.add(call.method);
          return null;
        });
  });
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(FrontendFeatures.channel, null),
  );
  Future<void> mount(
    WidgetTester tester, {
    bool dark = false,
    double scale = 1,
    List<Map> templates = const [],
  }) async {
    tester.view.physicalSize = const Size(320, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          activeProfileIdProvider.overrideWith(_Profile.new),
          contextEpochProvider.overrideWith(_Epoch.new),
          imageTasksProvider.overrideWith((_) async => []),
          imageConfigProvider.overrideWith(
            (_) async => {
              'ready': true,
              'model': 'gpt-image-2',
              'protocol': 'images',
            },
          ),
          imageTemplatesProvider.overrideWith((_) async => templates),
        ],
        child: MaterialApp(
          theme: dark ? StudyTheme.darkTheme() : StudyTheme.lightTheme(),
          builder: (_, child) => MediaQuery(
            data: MediaQueryData(
              textScaler: TextScaler.linear(scale),
              disableAnimations: true,
            ),
            child: child!,
          ),
          home: const ImageCreateStudio(owner: 'owner', epoch: 0),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('Style fills a matching draft without any paid submission', (
    tester,
  ) async {
    await mount(tester);
    await tester.ensureVisible(find.text('写实摄影'));
    await tester.tap(find.text('写实摄影'));
    await tester.pumpAndSettle();
    final field = tester.widget<TextField>(find.byType(TextField).first);
    expect(field.controller!.text, contains('陶瓷花瓶'));
    expect(calls, isNot(contains('imageStart')));
    expect(tester.takeException(), isNull);
  });
  testWidgets('Purpose collects content and exposes an editable draft', (
    tester,
  ) async {
    await mount(tester);
    await tester.scrollUntilVisible(
      find.text('海报'),
      500,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('海报'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(1), '秋日书店');
    await tester.enterText(find.byType(TextField).at(2), '读一本好书');
    await tester.tap(find.text('预览提示词'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<TextField>(find.byType(TextField).first).controller!.text,
      contains('读一本好书'),
    );
    expect(calls, isNot(contains('imageStart')));
  });
  testWidgets('Personal template replaces subject without submitting', (
    tester,
  ) async {
    await mount(
      tester,
      templates: [
        {
          'id': 'one',
          'name': '纸艺封面',
          'prompt': '主题：{主题}。分层纸艺。',
          'size': '1536x1024',
          'quality': 'high',
        },
      ],
    );
    await tester.scrollUntilVisible(
      find.text('纸艺封面'),
      500,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('纸艺封面'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(1), '湖边书屋');
    await tester.tap(find.text('填入草稿'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<TextField>(find.byType(TextField).first).controller!.text,
      '主题：湖边书屋。分层纸艺。',
    );
    expect(calls, isNot(contains('imageStart')));
  });
  for (final dark in [false, true]) {
    testWidgets('Studio supports narrow large text and theme (dark=$dark)', (
      tester,
    ) async {
      await mount(tester, dark: dark, scale: 1.6);
      await tester.scrollUntilVisible(
        find.text('我的模板'),
        400,
        scrollable: find.byType(Scrollable).first,
      );
      expect(tester.takeException(), isNull);
      expect(calls, isEmpty);
    });
  }
  testWidgets('Studio revokes drafts and templates when profile changes', (tester) async {
    await mount(tester);
    final container = ProviderScope.containerOf(tester.element(find.byType(ImageCreateStudio)));
    container.read(activeProfileIdProvider.notifier).state = 'another-owner';
    await tester.pumpAndSettle();
    expect(find.text('档案已改变，请重新打开创建页'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
    expect(calls, isEmpty);
  });
  testWidgets('Template editor saves the recipe through the protected bridge', (tester) async {
    await mount(tester);
    final editor = editImageTemplate(tester.element(find.byType(ImageCreateStudio)),
      tester.state<ConsumerState<ImageCreateStudio>>(find.byType(ImageCreateStudio)).ref,
      {'name':'水彩封面','prompt':'主题：{主题}。透明水彩，留白。','size':'1536x1024','quality':'high'});
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存')); await tester.pumpAndSettle();
    await editor;
    expect(calls, contains('imageTemplateSave'));
    expect(calls, isNot(contains('imageStart')));
    expect(tester.takeException(), isNull);
  });
}
