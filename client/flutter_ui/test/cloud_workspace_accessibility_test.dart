import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/pages/cloud_workspace_page.dart';
import 'package:flutter_ui/providers/study_providers.dart';

import 'agent_task_catalog_test.dart' show bootstrap;

void main() {
  testWidgets(
    'pairing inputs expose their purpose and retain password semantics',
    (tester) async {
      final semantics = tester.ensureSemantics();
      try {
        await tester.pumpWidget(
          ProviderScope(
            overrides: [
              bootstrapProvider.overrideWith((ref) async => bootstrap()),
              cloudWorkspaceProvider.overrideWith(
                (ref) async => {'enabled': false},
              ),
            ],
            child: const MaterialApp(home: CloudWorkspacePage()),
          ),
        );
        await tester.pumpAndSettle();
        final fields = find.byType(TextField);
        expect(
          tester.getSemantics(fields.at(0)).getSemanticsData().label,
          contains('独立权威 HTTPS 地址'),
        );
        final credential = tester.getSemantics(fields.at(1)).getSemanticsData();
        expect(credential.label, contains('管理员单次设备配对凭据'));
        expect(credential.flagsCollection.isObscured, isTrue);
      } finally {
        semantics.dispose();
      }
    },
  );
}
