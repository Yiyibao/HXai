import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/widgets/mascot.dart';
import 'package:flutter_ui/widgets/math_markdown.dart';

void main() {
  testWidgets('StudyMascot smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: StudyMascot(pose: MascotPose.idle, size: 64),
        ),
      ),
    );
    expect(find.byType(StudyMascot), findsOneWidget);
  });

  testWidgets('MathMarkdownView smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: MathMarkdownView(content: r'求解 $x^2 + 2x + 1 = 0$'),
        ),
      ),
    );
    expect(find.byType(MathMarkdownView), findsOneWidget);
  });
}
