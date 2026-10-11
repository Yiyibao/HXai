import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/doc_task_detail_page.dart';
import 'package:flutter_ui/pages/file_detail_page.dart';
import 'package:flutter_ui/pages/memory_detail_page.dart';
import 'package:flutter_ui/pages/memory_edit_page.dart';
import 'package:flutter_ui/pages/symbol_check_page.dart';
import 'package:flutter_ui/providers/frontend_feature_providers.dart';
import 'package:flutter_ui/providers/profile_resource.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/theme/study_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const resource = ProfileResourceRoute(id: 'shared-id', profileId: 'A', epoch: 1);
  final pages = <String, Widget Function(ProfileResourceRoute)>{
    'file': (r) => FileDetailPage(resource: r),
    'memory': (r) => MemoryDetailPage(resource: r),
    'task': (r) => DocTaskDetailPage(resource: r),
    'edit': (r) => MemoryEditPage(resource: r),
    'symbol': (r) => SymbolCheckPage(resource: r),
  };
  final methods = <String>{};
  void mock(String method, Future<Object?> Function(List<Object?>) handler) {
    methods.add(method);
    messenger.setMockDecodedMessageHandler<Object?>(
      BasicMessageChannel<Object?>(
        'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method',
        StudyHostApi.pigeonChannelCodec,
      ),
      (args) => handler((args as List).cast<Object?>()),
    );
  }
  MaterialFileDto material(String profile) => MaterialFileDto(
    id: 'shared-id', profileId: profile, name: '$profile-private-file.txt',
    extension: 'txt', state: 'SUCCEEDED', detail: '$profile-private-detail',
    size: 1, sha256: '', createdAt: 1, symbolCheckPending: true,
    symbolCorrection: '$profile-private-correction',
  );
  MemoryItemDto memory(String profile, {int revision = 1}) => MemoryItemDto(
    id: 'shared-id', type: 'PREFERENCE', content: '$profile-private-memory',
    sourceTopic: '$profile-private-topic', sourceFact: '$profile-private-fact',
    evidenceType: 'USER_INSTRUCTION', status: 'ACTIVE', revision: revision,
    createdAt: 1, updatedAt: revision, sourceConversationId: '$profile-chat',
  );
  Map<String, Object?> task(String profile) => {
    'id': 'shared-id', 'sourceId': 'material', 'fileName': '$profile-private-task',
    'state': 'RUNNING', 'start': 1, 'end': 2, 'downloaded': false,
  };
  Future<ProviderContainer> container() async {
    final result = ProviderContainer();
    addTearDown(result.dispose);
    await result.read(bootstrapProvider.future);
    // The bootstrap is loaded before navigation captures its source identity.
    result.read(activeProfileIdProvider);
    result.read(contextEpochProvider);
    return result;
  }
  Future<void> pumpPage(WidgetTester tester, ProviderContainer c, Widget page) async {
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: MaterialApp(theme: StudyTheme.lightTheme(), home: page),
    ));
    await tester.pumpAndSettle();
  }
  void expectRevoked() {
    expect(find.textContaining('private'), findsNothing);
    expect(find.byType(TextField), findsNothing);
    expect(find.text('保存'), findsNothing);
    expect(find.text('确认并保存'), findsNothing);
    expect(find.text('取消任务'), findsNothing);
    expect(find.text('编辑'), findsNothing);
  }

  setUp(() {
    mock('getBootstrap', (_) async => [StudyBootstrapDto(
      currentProfileId: 'A', contextEpoch: 1, profiles: [], studyMode: 'CHAT',
      reduceMotion: false, darkMode: false,
    )]);
    mock('getMaterials', (args) async => [[material(args[0] as String)]]);
    mock('getMemories', (args) async => [[memory(args[0] as String)]]);
    messenger.setMockMethodCallHandler(FrontendFeatures.channel, (call) async {
      if (call.method == 'docTasks') return [task((call.arguments as Map)['profileId'] as String)];
      return null;
    });
  });
  tearDown(() {
    for (final method in methods) {
      messenger.setMockDecodedMessageHandler<Object?>(BasicMessageChannel<Object?>(
        'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method', StudyHostApi.pigeonChannelCodec,
      ), null);
    }
    methods.clear();
    messenger.setMockMethodCallHandler(FrontendFeatures.channel, null);
  });

  for (final entry in pages.entries) {
    testWidgets('${entry.key}: foreign route never renders even with colliding IDs', (tester) async {
      final c = await container();
      c.read(activeProfileIdProvider.notifier).state = 'B';
      await pumpPage(tester, c, entry.value(resource));
      expectRevoked();
      expect(find.textContaining('不可用'), findsOneWidget);
    });
    testWidgets('${entry.key}: deleted current resource has no snapshot or actions', (tester) async {
      mock('getMaterials', (_) async => [<MaterialFileDto>[]]);
      mock('getMemories', (_) async => [<MemoryItemDto>[]]);
      messenger.setMockMethodCallHandler(FrontendFeatures.channel, (_) async => []);
      final c = await container();
      await pumpPage(tester, c, entry.value(resource));
      expectRevoked();
      expect(find.textContaining('不可用'), findsOneWidget);
    });
    testWidgets('${entry.key}: deletion of an open resource removes content and drafts', (tester) async {
      final c = await container();
      await pumpPage(tester, c, entry.value(resource));
      expect(find.textContaining('A-private'), findsWidgets);
      if (entry.key == 'edit' || entry.key == 'symbol') {
        await tester.enterText(find.byType(TextField), 'private-unsaved-draft');
      }
      mock('getMaterials', (_) async => [<MaterialFileDto>[]]);
      mock('getMemories', (_) async => [<MemoryItemDto>[]]);
      messenger.setMockMethodCallHandler(FrontendFeatures.channel, (_) async => []);
      c.invalidate(materialsProvider);
      c.invalidate(memoriesProvider);
      c.invalidate(documentTasksProvider);
      await tester.pumpAndSettle();
      expectRevoked();
      expect(find.textContaining('不可用'), findsOneWidget);
    });
    testWidgets('${entry.key}: backup epoch change revokes an already open page', (tester) async {
      final c = await container();
      await pumpPage(tester, c, entry.value(resource));
      expect(find.textContaining('A-private'), findsWidgets);
      if (entry.key == 'edit' || entry.key == 'symbol') {
        await tester.enterText(find.byType(TextField), 'private-unsaved-draft');
      }
      c.read(contextEpochProvider.notifier).state = 2;
      await tester.pumpAndSettle();
      expectRevoked();
      expect(find.textContaining('不可用'), findsOneWidget);
    });
  }

  for (final name in ['file', 'memory', 'edit', 'symbol']) {
    testWidgets('$name: a failed refresh never reuses the preceding private data', (tester) async {
      final c = await container();
      await pumpPage(tester, c, pages[name]!(resource));
      expect(find.textContaining('A-private'), findsWidgets);
      final memoryPage = name == 'memory' || name == 'edit';
      mock(memoryPage ? 'getMemories' : 'getMaterials',
          (_) async => ['FAILED', 'private-backend-error', null]);
      if (memoryPage) {
        await c.read(memoriesProvider.notifier).refresh();
      } else {
        await c.read(materialsProvider.notifier).refresh();
      }
      await tester.pumpAndSettle();
      expectRevoked();
      expect(find.textContaining('不可用'), findsOneWidget);
    });
  }

  testWidgets('A pending bootstrap revokes editing until restored identity is known', (tester) async {
    final delayed = Completer<Object?>();
    final c = await container();
    await pumpPage(tester, c, const MemoryEditPage(resource: resource));
    await tester.enterText(find.byType(TextField), 'private-unsaved-draft');
    mock('getBootstrap', (_) => delayed.future);
    c.invalidate(bootstrapProvider);
    await tester.pump();
    expectRevoked();
    delayed.complete([StudyBootstrapDto(
      currentProfileId: 'A', contextEpoch: 2, profiles: [], studyMode: 'CHAT',
      reduceMotion: false, darkMode: false,
    )]);
    await tester.pumpAndSettle();
    expectRevoked();
    expect(find.textContaining('不可用'), findsOneWidget);
  });

  for (final name in ['edit', 'symbol']) {
    testWidgets('$name: refresh loading removes the editor and never restores its draft', (tester) async {
      final delayed = Completer<Object?>();
      var calls = 0;
      final method = name == 'edit' ? 'getMemories' : 'getMaterials';
      mock(method, (_) async {
        if (++calls == 2) return delayed.future;
        return [name == 'edit' ? [memory('A')] : [material('A')]];
      });
      final c = await container();
      await pumpPage(tester, c, pages[name]!(resource));
      await tester.enterText(find.byType(TextField), 'private-unsaved-draft');
      final refreshing = name == 'edit'
          ? c.read(memoriesProvider.notifier).refresh()
          : c.read(materialsProvider.notifier).refresh();
      await tester.pump();
      expectRevoked();
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      delayed.complete([name == 'edit' ? [memory('new')] : [material('new')]]);
      await refreshing;
      await tester.pumpAndSettle();
      expect(find.text('private-unsaved-draft'), findsNothing);
      expect(find.textContaining('new-private'), findsWidgets);
    });
    testWidgets('$name: late save success cannot navigate or announce success in another profile', (tester) async {
      final delayed = Completer<Object?>();
      var saves = 0;
      mock(name == 'edit' ? 'updateMemory' : 'checkSymbol', (args) {
        saves++;
        expect(args[0], 'A');
        expect(args[1], 'shared-id');
        return delayed.future;
      });
      final c = await container();
      await pumpPage(tester, c, pages[name]!(resource));
      await tester.enterText(find.byType(TextField), 'private-new-draft');
      await tester.tap(find.text(name == 'edit' ? '保存' : '确认并保存'));
      await tester.pump();
      expect(saves, 1);
      c.read(activeProfileIdProvider.notifier).state = 'B';
      await tester.pumpAndSettle();
      expectRevoked();
      delayed.complete([SimpleCommandResult(success: true)]);
      await tester.pumpAndSettle();
      expectRevoked();
      expect(find.text('记忆已更新并保存为新版本'), findsNothing);
      expect(tester.takeException(), isNull);
      expect(saves, 1);
    });
  }

  testWidgets('A delayed refresh cannot revive a file after restoring the same profile', (tester) async {
    final delayed = Completer<Object?>();
    var calls = 0;
    mock('getMaterials', (_) async {
      if (++calls == 2) return delayed.future;
      return [[material(calls == 1 ? 'A' : 'restored')]];
    });
    final c = await container();
    await pumpPage(tester, c, const FileDetailPage(resource: resource));
    final refreshing = c.read(materialsProvider.notifier).refresh();
    await tester.pump();
    expectRevoked();
    c.read(contextEpochProvider.notifier).state = 2;
    await tester.pumpAndSettle();
    delayed.complete([[material('old')]]);
    await refreshing;
    await tester.pumpAndSettle();
    expectRevoked();
    expect(c.read(materialsProvider).value!.single.name, 'restored-private-file.txt');
  });

  testWidgets('Delete confirmation revokes its private text and command on profile change', (tester) async {
    var deletes = 0;
    mock('deleteMemory', (_) async {
      deletes++;
      return [SimpleCommandResult(success: true)];
    });
    final c = await container();
    await pumpPage(tester, c, const MemoryDetailPage(resource: resource));
    await tester.tap(find.byTooltip('更多'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除记忆'));
    await tester.pumpAndSettle();
    expect(find.text('删除这条记忆？'), findsOneWidget);
    c.read(activeProfileIdProvider.notifier).state = 'B';
    await tester.pumpAndSettle();
    expect(find.textContaining('private'), findsNothing);
    expect(find.text('删除'), findsNothing);
    expect(deletes, 0);
  });

  testWidgets('Late document action errors neither expose text nor refresh the next profile', (tester) async {
    final delayed = Completer<Object?>();
    var actions = 0;
    var bQueries = 0;
    messenger.setMockMethodCallHandler(FrontendFeatures.channel, (call) async {
      final args = call.arguments as Map;
      if (call.method == 'docTasks') {
        if (args['profileId'] == 'B') bQueries++;
        return [task(args['profileId'] as String)];
      }
      if (call.method == 'docTaskAction') {
        actions++;
        expect(args['profileId'], 'A');
        expect(args['epoch'], 1);
        return delayed.future;
      }
      return null;
    });
    final c = await container();
    await pumpPage(tester, c, const DocTaskDetailPage(resource: resource));
    await tester.tap(find.text('取消任务'));
    await tester.pump();
    c.read(activeProfileIdProvider.notifier).state = 'B';
    await tester.pumpAndSettle();
    final queriesBeforeReceipt = bQueries;
    delayed.completeError(PlatformException(code: 'FAILED', message: 'A-private-error'));
    await tester.pumpAndSettle();
    expectRevoked();
    expect(actions, 1);
    expect(bQueries, queriesBeforeReceipt);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Returning from symbol editing after restore does not reveal the old file', (tester) async {
    final c = await container();
    final navigation = GoRouter(initialLocation: '/detail', routes: [
      GoRoute(path: '/detail', builder: (_, _) => const FileDetailPage(resource: resource)),
      GoRoute(path: '/files/symbol-check', builder: (_, state) => SymbolCheckPage(
        resource: state.extra as ProfileResourceRoute?,
      )),
    ]);
    addTearDown(navigation.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(container: c,
      child: MaterialApp.router(theme: StudyTheme.lightTheme(), routerConfig: navigation),
    ));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('原页核对'));
    await tester.tap(find.text('原页核对'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'private-unsaved-draft');
    c.read(contextEpochProvider.notifier).state = 2;
    await tester.pumpAndSettle();
    expectRevoked();
    navigation.pop();
    await tester.pumpAndSettle();
    expectRevoked();
    expect(find.text('文件详情'), findsOneWidget);
  });

  testWidgets('A current memory can still be edited with its queried revision and refreshed', (tester) async {
    var revision = 1;
    mock('getMemories', (_) async => [[memory('A', revision: revision)]]);
    mock('updateMemory', (args) async {
      expect(args[0], 'A');
      expect(args[1], 'shared-id');
      expect(args[2], 'new content');
      expect(args[3], 1);
      revision++;
      return [SimpleCommandResult(success: true)];
    });
    final c = await container();
    final navigation = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, _) => const Scaffold(body: Text('current list'))),
      GoRoute(path: '/edit', builder: (_, _) => const MemoryEditPage(resource: resource)),
    ]);
    addTearDown(navigation.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(container: c,
      child: MaterialApp.router(theme: StudyTheme.lightTheme(), routerConfig: navigation),
    ));
    navigation.push('/edit');
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'new content');
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    expect(find.text('current list'), findsOneWidget);
    expect(c.read(memoriesProvider).value!.single.revision, 2);
    expect(tester.takeException(), isNull);
  });
}
