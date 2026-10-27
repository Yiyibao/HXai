import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/pages/conversation_drawer.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/widgets/project_groups.dart';

class _Conversations extends ConversationsNotifier {
  @override
  Future<List<ConversationSummaryDto>> build() async => [];
}

class _Projects extends ProjectsNotifier {
  @override
  Future<List<ProjectDto>> build() async => [];
}

void main() {
  testWidgets(
    'Global task and usage entries exist without any accepted Chat task',
    (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            bootstrapProvider.overrideWith(
              (_) async => StudyBootstrapDto(
                currentProfileId: 'owner',
                contextEpoch: 1,
                profiles: [],
                studyMode: 'CHAT',
                reduceMotion: false,
                darkMode: false,
              ),
            ),
            conversationsProvider.overrideWith(_Conversations.new),
            projectsProvider.overrideWith(_Projects.new),
          ],
          child: const MaterialApp(home: Scaffold(body: ConversationDrawer())),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('任务中心'), findsOneWidget);
      expect(find.text('请求用量'), findsOneWidget);
    },
  );
}
