import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/chat_timeline.dart';

void main() {
  Future<void> show(
    WidgetTester tester, {
    int count = 30,
    double height = 100,
    double viewport = 400,
    bool follow = true,
    String identity = 'conversation-a',
    Widget? lastChild,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            height: viewport,
            child: ChatTimeline(
              key: ValueKey(identity),
              autoFollow: follow,
              itemCount: count,
              itemBuilder: (_, index) => SizedBox(
                height: index == count - 1 ? height : 100,
                child: index == count - 1 && lastChild != null
                    ? lastChild
                    : Text('message $index'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  ScrollPosition position(WidgetTester tester) =>
      tester.widget<ListView>(find.byType(ListView)).controller!.position;

  Future<void> readHistory(WidgetTester tester) async {
    await tester.drag(find.byType(ListView), const Offset(0, 250));
    await tester.pumpAndSettle();
    expect(position(tester).extentAfter, greaterThan(60));
    expect(find.byTooltip('回到最新消息'), findsOneWidget);
  }

  testWidgets('Opens a long conversation at the actual lazy-list bottom', (
    tester,
  ) async {
    await show(tester, count: 150, height: 900);
    expect(position(tester).extentAfter, lessThan(1));
    expect(find.text('message 149'), findsOneWidget);
    expect(find.byTooltip('回到最新消息'), findsNothing);
  });

  testWidgets('Follows streamed growth and delayed image layout', (
    tester,
  ) async {
    await show(tester);
    await show(tester, height: 850);
    expect(position(tester).extentAfter, lessThan(1));
    await show(tester, count: 31, height: 1200);
    expect(position(tester).extentAfter, lessThan(1));
  });

  testWidgets('Reading history survives new chunks and viewport changes', (
    tester,
  ) async {
    await show(tester);
    await readHistory(tester);
    final offset = position(tester).pixels;
    await show(tester, height: 900);
    expect(position(tester).pixels, closeTo(offset, 1));
    await show(tester, height: 900, viewport: 220);
    expect(position(tester).pixels, closeTo(offset, 1));
    expect(find.byTooltip('回到最新消息'), findsOneWidget);
  });

  testWidgets('Returning to latest resumes following subsequent chunks', (
    tester,
  ) async {
    await show(tester);
    await readHistory(tester);
    await tester.tap(find.byTooltip('回到最新消息'));
    await tester.pumpAndSettle();
    expect(position(tester).extentAfter, lessThan(1));
    await show(tester, height: 950);
    expect(position(tester).extentAfter, lessThan(1));
  });

  testWidgets('Keyboard resizing keeps the latest answer visible', (
    tester,
  ) async {
    await show(tester);
    await show(tester, viewport: 180);
    expect(position(tester).extentAfter, lessThan(1));
    await show(tester, viewport: 500);
    expect(position(tester).extentAfter, lessThan(1));
  });

  testWidgets(
    'Disabled auto-follow allows explicit return without enabling it',
    (tester) async {
      await show(tester, follow: false);
      expect(position(tester).pixels, 0);
      await tester.tap(find.byTooltip('回到最新消息'));
      await tester.pumpAndSettle();
      expect(position(tester).extentAfter, lessThan(1));
      final offset = position(tester).pixels;
      await show(tester, follow: false, height: 900);
      expect(position(tester).pixels, closeTo(offset, 1));
      expect(find.byTooltip('回到最新消息'), findsOneWidget);
    },
  );

  testWidgets('New conversation identity resets history-reading state', (
    tester,
  ) async {
    await show(tester);
    await readHistory(tester);
    await show(tester, identity: 'conversation-b', count: 50);
    expect(position(tester).extentAfter, lessThan(1));
    expect(find.byTooltip('回到最新消息'), findsNothing);
  });

  testWidgets('Pending layout callback is safe after leaving chat', (
    tester,
  ) async {
    await show(tester);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  testWidgets('Scrolling inside a card does not pause the chat timeline', (
    tester,
  ) async {
    final innerController = ScrollController();
    addTearDown(innerController.dispose);
    Widget card() => SingleChildScrollView(
      key: const ValueKey('nested-card'),
      controller: innerController,
      child: const SizedBox(height: 900, child: Text('long table')),
    );
    await show(tester, height: 180, lastChild: card());
    await tester.drag(
      find.byKey(const ValueKey('nested-card')),
      const Offset(0, -80),
    );
    await tester.pumpAndSettle();
    expect(innerController.offset, greaterThan(0));
    await show(tester, height: 320, lastChild: card());
    expect(position(tester).extentAfter, lessThan(1));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('User gesture wins over a queued streaming layout check', (
    tester,
  ) async {
    await show(tester);
    final gesture = await tester.startGesture(
      tester.getCenter(find.byType(ListView)),
    );
    await gesture.moveBy(const Offset(0, 160));
    await tester.pump();
    final offset = position(tester).pixels;
    await show(tester, height: 750);
    expect(position(tester).pixels, closeTo(offset, 1));
    await gesture.up();
    await tester.pumpAndSettle();
    expect(find.byTooltip('回到最新消息'), findsOneWidget);
  });
}
