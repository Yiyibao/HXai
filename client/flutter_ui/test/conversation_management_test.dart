import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/conversation_drawer.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String method, Future<Object?> Function(List<Object?>) handler) {
    final channel = BasicMessageChannel<Object?>(
      'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method', StudyHostApi.pigeonChannelCodec);
    messenger.setMockDecodedMessageHandler<Object?>(channel,
      (args) => handler((args as List).cast<Object?>()));
    addTearDown(() => messenger.setMockDecodedMessageHandler<Object?>(channel, null));
  }

  testWidgets('Native pins precede recent chats and archived chats can be restored', (tester) async {
    tester.view.physicalSize = const Size(900, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    ConversationSummaryDto item(String id, String time, {bool pinned = false, bool archived = false}) =>
      ConversationSummaryDto(id: id, title: id, previewText: '', updatedAt: time,
        isPinned: pinned, isArchived: archived, studyMode: 'CHAT', profileId: 'default-learner');
    final rows = [item('old pinned', '0', pinned: true),
      item('recent chat', DateTime.now().toIso8601String()),
      item('stored archive', '1', archived: true)];
    mock('getProjects', (_) async => [<ProjectDto>[]]);
    mock('getConversations', (args) async => [rows.where((row) => row.isArchived == args[3])
      .skip(args[2] as int).take(args[1] as int).toList()]);
    mock('setConversationArchived', (args) async {
      rows.singleWhere((row) => row.id == args[1]).isArchived = args[2] as bool;
      return [SimpleCommandResult(success: true)];
    });
    var container = ProviderContainer();
    addTearDown(() => container.dispose());
    Future<void> mount() async {
      await tester.pumpWidget(UncontrolledProviderScope(container: container,
        child: const MaterialApp(home: Scaffold(body: ConversationDrawer()))));
      await tester.pumpAndSettle();
    }
    Future<void> openMenu(String title) async {
      final row = find.ancestor(of: find.text(title), matching: find.byType(Row)).first;
      await tester.tap(find.descendant(of: row, matching: find.byIcon(Icons.more_horiz)));
      await tester.pumpAndSettle();
    }
    await mount();
    expect(tester.getTopLeft(find.text('old pinned')).dy,
      lessThan(tester.getTopLeft(find.text('recent chat')).dy));
    expect(find.byIcon(Icons.push_pin), findsOneWidget);
    expect(find.text('stored archive'), findsNothing);
    await openMenu('recent chat');
    await tester.tap(find.text('归档聊天'));
    await tester.pumpAndSettle();
    expect(find.text('recent chat'), findsNothing);
    await tester.tap(find.text('已归档聊天'));
    await tester.pumpAndSettle();
    expect(find.text('old pinned'), findsNothing);
    expect(find.text('recent chat'), findsOneWidget);
    expect(find.text('stored archive'), findsOneWidget);
    await openMenu('recent chat');
    await tester.tap(find.text('取消归档'));
    await tester.pumpAndSettle();
    expect(find.text('recent chat'), findsNothing);
    await tester.pumpWidget(const SizedBox());
    container.dispose();
    container = ProviderContainer();
    await mount();
    expect(find.text('recent chat'), findsOneWidget);
    expect(find.text('stored archive'), findsNothing);
    expect(tester.getTopLeft(find.text('old pinned')).dy,
      lessThan(tester.getTopLeft(find.text('recent chat')).dy));
  });
}
