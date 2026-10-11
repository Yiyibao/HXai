import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/widgets/interactive_surface_message.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String method, Future<Object?> Function(Object?) handler) {
    messenger.setMockDecodedMessageHandler<Object?>(
      BasicMessageChannel<Object?>(
        'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method',
        StudyHostApi.pigeonChannelCodec,
      ),
      handler,
    );
  }

  Widget surface({String conversation = 'a', String revision = 'hash-a'}) =>
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(
            body: InteractiveSurfaceMessage(
              profileId: 'owner',
              conversationId: conversation,
              messageId: 'message',
              surfaceId: 'tool',
              title: '预算',
              revision: revision,
              contextEpoch: 3,
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
                  {
                    'id': 'go',
                    'type': 'button',
                    'label': '继续分析',
                    'action': 'continue',
                  },
                ],
              }),
            ),
          ),
        ),
      );

  testWidgets(
    'Bridge load errors do not silently replace saved state with defaults',
    (tester) async {
      mock('getSurfaceState', (_) async => ['STALE', 'old branch', null]);
      await tester.pumpWidget(surface());
      await tester.pump();
      expect(find.text('重试加载界面'), findsOneWidget);
      expect(tester.widget<Slider>(find.byType(Slider)).onChanged, isNull);
      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNull,
      );
    },
  );

  testWidgets(
    'Unsuccessful bridge command without error details is still rejected',
    (tester) async {
      mock('getSurfaceState', (_) async => ['{"amount":7}']);
      mock('surfaceAction', (_) async => [SimpleCommandResult(success: false)]);
      await tester.pumpWidget(surface());
      await tester.pump();
      await tester.tap(find.text('继续分析'));
      await tester.pump();
      expect(find.textContaining('提交未完成'), findsOneWidget);
      expect(find.text('已提交，AI 将继续分析'), findsNothing);
    },
  );

  testWidgets(
    'Switching surface identity flushes only to its original conversation',
    (tester) async {
      final commands = <List<Object?>>[];
      mock('getSurfaceState', (_) async => ['{"amount":7}']);
      mock('surfaceAction', (args) async {
        commands.add(List<Object?>.from(args as List));
        return [SimpleCommandResult(success: true)];
      });
      await tester.pumpWidget(surface());
      await tester.pump();
      tester.widget<Slider>(find.byType(Slider)).onChanged!(9);
      await tester.pumpWidget(surface(conversation: 'b', revision: 'hash-b'));
      await tester.pump();
      expect(commands.single[1], 'a');
      expect(commands.single[4], 'hash-a');
      expect(jsonDecode(commands.single[5] as String), {'amount': 9});
      expect(tester.widget<Slider>(find.byType(Slider)).value, 7);
    },
  );
}
