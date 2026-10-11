import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_math_fork/flutter_math.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:flutter_ui/widgets/math_markdown.dart';

void main() {
  Future<void> render(WidgetTester tester, String text) => tester.pumpWidget(MaterialApp(home: Scaffold(body: SingleChildScrollView(child: MathMarkdownView(content: text)))));

  testWidgets('Markdown renders heading table and code as semantic widgets', (tester) async {
    await render(tester, '# Report\n\n| Name | Value |\n| --- | --- |\n| Budget | 42 |\n\n```dart\nprint(42);\n```');
    expect(find.byType(MarkdownBody), findsOneWidget);
    expect(find.byType(Table), findsOneWidget);
    expect(find.text('Report', findRichText: true), findsOneWidget);
    expect(find.text('Budget', findRichText: true), findsOneWidget);
    expect(find.textContaining('print(42);', findRichText: true), findsOneWidget);
    expect(find.textContaining('# Report', findRichText: true), findsNothing);
  });

  testWidgets('Inline and display math render while fenced math stays code', (tester) async {
    await render(tester, r'Inline $x^2$' '\n\n' r'$$y=2x$$' '\n\n```text\n' r'$$literal$$' '\n```');
    expect(find.byType(Math), findsNWidgets(2));
    expect(find.textContaining(r'$$literal$$', findRichText: true), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Unfinished stream preserves raw expression', (tester) async {
    await render(tester, r'Answer: $$\frac{1}{');
    expect(find.textContaining(r'$$\frac{1}{', findRichText: true), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
