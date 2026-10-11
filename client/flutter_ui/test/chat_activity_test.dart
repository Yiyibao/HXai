import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/chat_activity.dart';

void main() {
  testWidgets('Dots animate and reduced motion keeps the status visible', (
    tester,
  ) async {
    Widget view(bool reduced) => MaterialApp(
      home: Scaffold(
        body: ChatActivity(label: '正在思考', reduceMotion: reduced),
      ),
    );
    await tester.pumpWidget(view(false));
    final before = tester
        .widgetList<Transform>(
          find.descendant(
            of: find.byType(ChatActivityDots),
            matching: find.byType(Transform),
          ),
        )
        .first
        .transform
        .storage
        .toList();
    await tester.pump(const Duration(milliseconds: 300));
    final after = tester
        .widgetList<Transform>(
          find.descendant(
            of: find.byType(ChatActivityDots),
            matching: find.byType(Transform),
          ),
        )
        .first
        .transform
        .storage
        .toList();
    expect(after, isNot(before));
    await tester.pumpWidget(view(true));
    await tester.pumpAndSettle();
    expect(find.text('正在思考'), findsOneWidget);
    expect(tester.hasRunningAnimations, isFalse);
    await tester.pumpWidget(const SizedBox.shrink());
    expect(tester.takeException(), isNull);
  });
}
