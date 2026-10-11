import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/widgets/interactive_surface_message.dart';
import 'package:flutter_ui/widgets/surface_workspace.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'Wide preview persists edits, restores inline state and closes on branch change',
    (tester) async {
      tester.view.physicalSize = const Size(1200, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      var saved = '{"amount":2}';
      void mock(String method, Future<Object?> Function(Object?) fn) =>
          messenger.setMockDecodedMessageHandler<Object?>(
            BasicMessageChannel<Object?>(
              'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method',
              StudyHostApi.pigeonChannelCodec,
            ),
            fn,
          );
      mock('getSurfaceState', (_) async => [saved]);
      mock('surfaceAction', (args) async {
        saved = (args as List)[5] as String;
        return [SimpleCommandResult(success: true)];
      });
      Widget scene(String branch) => ProviderScope(
        child: MaterialApp(
          home: Scaffold(
            body: SurfaceWorkspace(
              identity: branch,
              child: SingleChildScrollView(
                child: InteractiveSurfaceMessage(
                  profileId: 'owner',
                  conversationId: 'chat',
                  messageId: 'message',
                  surfaceId: 'tool',
                  title: '预算',
                  revision: 'revision',
                  contextEpoch: 1,
                  definitionJson: jsonEncode({
                    'version': 1,
                    'title': '预算',
                    'components': [
                      {
                        'id': 'amount',
                        'type': 'slider',
                        'label': '人数',
                        'min': 1,
                        'max': 10,
                        'value': 2,
                      },
                    ],
                  }),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpWidget(scene('branch-a'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('并排预览'));
      await tester.pumpAndSettle();
      expect(find.text('预算 · 已在预览区打开'), findsOneWidget);
      expect(find.byType(Slider), findsOneWidget);
      tester.widget<Slider>(find.byType(Slider)).onChanged!(7);
      await tester.pump();
      await tester.tap(find.byTooltip('保存并关闭预览'));
      await tester.pumpAndSettle();
      expect(find.text('预算 · 已在预览区打开'), findsNothing);
      expect(jsonDecode(saved)['amount'], 7);
      expect(tester.widget<Slider>(find.byType(Slider)).value, 7);
      await tester.tap(find.text('并排预览'));
      await tester.pumpAndSettle();
      await tester.pumpWidget(scene('branch-b'));
      await tester.pumpAndSettle();
      expect(find.byTooltip('保存并关闭预览'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
