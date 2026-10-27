import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/extensions_page.dart';
import 'package:flutter_ui/providers/frontend_feature_providers.dart';
import 'package:flutter_ui/providers/study_providers.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  setUp(() {
    messenger.setMockMethodCallHandler(
      const MethodChannel('me.rerere.rikkahub/study_events'),
      (_) async => null,
    );
    messenger.setMockDecodedMessageHandler<Object?>(
      BasicMessageChannel<Object?>(
        'dev.flutter.pigeon.flutter_ui.StudyHostApi.getBootstrap',
        StudyHostApi.pigeonChannelCodec,
      ),
      (_) async => [
        StudyBootstrapDto(
          currentProfileId: 'A',
          contextEpoch: 1,
          profiles: [],
          studyMode: 'CHAT',
          reduceMotion: false,
          darkMode: false,
        ),
      ],
    );
  });
  Map<String, Object> item(String owner) => {
    'slot': 'one',
    'id': 'id-$owner',
    'revision': 2,
    'grantEpoch': 2,
    'state': 'ENABLED',
    'config': jsonEncode({
      'name': '$owner-private-service',
      'endpoint': 'https://example.com/mcp',
      'serverName': 'test',
      'serverVersion': '1',
      'protocol': '2025-11-25',
      'tools': [
        {'name': 'read'},
      ],
    }),
  };
  Future<ProviderContainer> container() async {
    final c = ProviderContainer();
    addTearDown(c.dispose);
    await c.read(bootstrapProvider.future);
    c.read(activeProfileIdProvider);
    c.read(contextEpochProvider);
    return c;
  }

  Future<void> pump(
    WidgetTester tester,
    ProviderContainer c, {
    bool dark = false,
    double scale = 1,
  }) async {
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          theme: dark ? ThemeData.dark() : ThemeData.light(),
          home: MediaQuery(
            data: MediaQueryData(textScaler: TextScaler.linear(scale)),
            child: const ExtensionsPage(),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  tearDown(() {
    messenger.setMockMethodCallHandler(FrontendFeatures.channel, null);
  });

  testWidgets(
    'OAuth callback requires explicit completion and never enables tools',
    (tester) async {
      final methods = <String>[];
      var complete = false;
      messenger.setMockMethodCallHandler(FrontendFeatures.channel, (
        call,
      ) async {
        methods.add(call.method);
        if (call.method == 'extensions.list') {
          return {
            'installations': [],
            'events': [],
            'oauthFlows': complete
                ? []
                : [
                    {'slot': 'oauth', 'flow': 'pending', 'status': 'CALLBACK'},
                  ],
          };
        }
        if (call.method == 'extensions.oauth.complete') {
          complete = true;
          return {...item('A'), 'state': 'DISABLED'};
        }
        return null;
      });
      final c = await container();
      await pump(tester, c);
      await tester.scrollUntilVisible(
        find.text('完成连接'),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.text('完成连接'));
      await tester.pumpAndSettle();
      expect(methods.where((v) => v == 'extensions.oauth.complete').length, 1);
      expect(methods, isNot(contains('extensions.command')));
      expect(find.text('完成连接'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'durable state is shown and revoke sends CAS only after confirmation',
    (tester) async {
      final commands = <Map>[];
      messenger.setMockMethodCallHandler(FrontendFeatures.channel, (
        call,
      ) async {
        if (call.method == 'extensions.list') {
          return {
            'version': 1,
            'installations': [item('A')],
            'events': [],
          };
        }
        commands.add(call.arguments as Map);
        return {};
      });
      final c = await container();
      await pump(tester, c);
      expect(find.text('A-private-service · ENABLED'), findsOneWidget);
      await tester.tap(find.text('撤权'));
      await tester.pumpAndSettle();
      expect(commands, isEmpty);
      await tester.tap(find.text('确认'));
      await tester.pumpAndSettle();
      expect(commands.single, containsPair('action', 'revoke'));
      expect(commands.single, containsPair('profileId', 'A'));
      expect(commands.single, containsPair('epoch', 1));
      expect(commands.single, containsPair('revision', 2));
      expect(commands.single, containsPair('id', 'id-A'));
    },
  );

  testWidgets(
    'profile switch hides old catalog while the new snapshot is pending',
    (tester) async {
      final b = Completer<Object?>();
      messenger.setMockMethodCallHandler(FrontendFeatures.channel, (
        call,
      ) async {
        if ((call.arguments as Map)['profileId'] == 'B') return b.future;
        return {
          'version': 1,
          'installations': [item('A')],
          'events': [],
        };
      });
      final c = await container();
      await pump(tester, c);
      expect(find.textContaining('A-private'), findsOneWidget);
      c.read(activeProfileIdProvider.notifier).state = 'B';
      c.read(contextEpochProvider.notifier).state = 2;
      await tester.pump();
      await tester.pump();
      expect(find.textContaining('A-private'), findsNothing);
      b.complete({
        'version': 1,
        'installations': [item('B')],
        'events': [],
      });
      await tester.pumpAndSettle();
      expect(find.textContaining('B-private'), findsOneWidget);
      expect(find.textContaining('A-private'), findsNothing);
    },
  );

  testWidgets(
    'native profile event invalidates identity even when Chat is not mounted',
    (tester) async {
      var owner = 'A';
      var epoch = 1;
      messenger.setMockDecodedMessageHandler<Object?>(
        BasicMessageChannel<Object?>(
          'dev.flutter.pigeon.flutter_ui.StudyHostApi.getBootstrap',
          StudyHostApi.pigeonChannelCodec,
        ),
        (_) async => [
          StudyBootstrapDto(
            currentProfileId: owner,
            contextEpoch: epoch,
            profiles: [],
            studyMode: 'CHAT',
            reduceMotion: false,
            darkMode: false,
          ),
        ],
      );
      final events = StreamController<Map<String, dynamic>>();
      addTearDown(events.close);
      messenger.setMockMethodCallHandler(
        FrontendFeatures.channel,
        (call) async => {
          'version': 1,
          'installations': [
            item((call.arguments as Map)['profileId'] as String),
          ],
          'events': [],
        },
      );
      final c = ProviderContainer(
        overrides: [bridgeEventsProvider.overrideWith((ref) => events.stream)],
      );
      addTearDown(c.dispose);
      await c.read(bootstrapProvider.future);
      await pump(tester, c);
      expect(find.textContaining('A-private'), findsOneWidget);
      owner = 'B';
      epoch = 2;
      events.add({
        'type': 'profile_changed',
        'profileId': 'B',
        'contextEpoch': 2,
        'payload': {'newEpoch': 2},
      });
      await tester.pumpAndSettle();
      expect(find.textContaining('A-private'), findsNothing);
      expect(find.textContaining('B-private'), findsOneWidget);
    },
  );

  testWidgets(
    'small screen large text and dark state can scroll without overflow',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      messenger.setMockMethodCallHandler(
        FrontendFeatures.channel,
        (call) async => {
          'version': 1,
          'installations': [item('A')],
          'events': [],
        },
      );
      final c = await container();
      await pump(tester, c, dark: true, scale: 2);
      await tester.drag(find.byType(ListView).first, const Offset(0, -900));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );
}
