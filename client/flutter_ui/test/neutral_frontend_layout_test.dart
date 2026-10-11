import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_ui/providers/study_providers.dart';
import 'package:flutter_ui/providers/profile_resource.dart';
import 'package:flutter_ui/widgets/context_follow_up.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/bridge/study_host_api.g.dart';
import 'package:flutter_ui/providers/frontend_feature_providers.dart';
import 'package:flutter_ui/providers/app_settings_providers.dart';
import 'package:flutter_ui/theme/study_theme.dart';
import 'package:flutter_ui/widgets/attachment_options_sheet.dart';
import 'package:flutter_ui/widgets/citations_sheet.dart';
import 'package:flutter_ui/widgets/source_links.dart';
import 'package:flutter_ui/pages/settings_page.dart';
import 'package:flutter_ui/pages/appearance_page.dart';
import 'package:flutter_ui/pages/providers_page.dart';
import 'package:flutter_ui/pages/provider_edit_page.dart';
import 'package:flutter_ui/pages/provider_models_page.dart';
import 'package:flutter_ui/pages/model_config_detail_page.dart';
import 'package:flutter_ui/pages/skills_page.dart';
import 'package:flutter_ui/pages/skill_detail_page.dart';
import 'package:flutter_ui/pages/import_skill_page.dart';
import 'package:flutter_ui/pages/memories_page.dart';
import 'package:flutter_ui/pages/memory_detail_page.dart';
import 'package:flutter_ui/pages/memory_edit_page.dart';
import 'package:flutter_ui/pages/files_page.dart';
import 'package:flutter_ui/pages/file_detail_page.dart';
import 'package:flutter_ui/pages/symbol_check_page.dart';
import 'package:flutter_ui/pages/artifacts_page.dart';
import 'package:flutter_ui/pages/artifact_preview_page.dart';
import 'package:flutter_ui/pages/artifact_versions_page.dart';
import 'package:flutter_ui/pages/doc_service_config_page.dart';
import 'package:flutter_ui/pages/doc_tasks_page.dart';
import 'package:flutter_ui/pages/doc_task_detail_page.dart';
import 'package:flutter_ui/pages/backup_page.dart';
import 'package:flutter_ui/pages/restore_backup_page.dart';
import 'package:flutter_ui/pages/diagnostics_page.dart';
import 'package:flutter_ui/pages/chat_page.dart';
import 'package:flutter_ui/pages/search_chat_page.dart';
import 'package:flutter_ui/pages/chart_fullscreen_page.dart';
import 'package:flutter_ui/pages/table_fullscreen_page.dart';
import 'package:flutter_ui/pages/params_fullscreen_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  void mock(String method, Object? value) =>
      messenger.setMockDecodedMessageHandler<Object?>(
        BasicMessageChannel<Object?>(
          'dev.flutter.pigeon.flutter_ui.StudyHostApi.$method',
          StudyHostApi.pigeonChannelCodec,
        ),
        (_) async => [value],
      );
  final memory = MemoryItemDto(
    id: 'memory',
    type: 'PREFERENCE',
    content: '回答时先给结论，再说明理由。涉及多个步骤时使用编号，并且保留来源。',
    sourceTopic: '讨论回答格式',
    sourceFact: '用户明确要求',
    evidenceType: 'USER_INSTRUCTION',
    status: 'ACTIVE',
    revision: 2,
    createdAt: 1,
    updatedAt: 2,
    sourceQuote: '先给结论',
    sourceConversationId: 'conversation',
  );
  final file = MaterialFileDto(
    id: 'material',
    profileId: 'default-learner',
    name: '一份带有很长文件名的研究报告与实验材料.pdf',
    extension: 'pdf',
    state: 'SUCCEEDED',
    detail: '文件已保存，尚未进行解析。',
    size: 2000000,
    sha256: 'hash',
    createdAt: 1,
    pageNumber: 3,
    symbolCheckPending: true,
  );
  final model = ModelDto(
    id: 'model',
    name: '一个较长的模型显示名称',
    providerId: 'provider',
    enabled: true,
    supportText: true,
    supportImage: true,
    supportTool: true,
  );
  final provider = ProviderDto(
    id: 'provider',
    name: '兼容 API 提供商',
    type: 'OpenAI',
    endpoint: 'https://example.com/v1',
    apiKeyMasked: '••••••••',
    enabled: true,
    isPrimary: true,
    models: [model],
  );
  final skill = SkillDto(
    name: 'document-processing',
    description: '处理文档的说明与资源',
    builtin: false,
    enabled: false,
    state: 'PARTIAL',
    detail: '依赖尚未配置',
    files: ['SKILL.md', 'scripts/process.py'],
    license: 'MIT',
  );
  final task = DocumentTaskInfo({
    'id': 'task',
    'sourceId': file.id,
    'fileName': file.name,
    'state': 'FAILED',
    'start': 3,
    'end': 5,
    'downloaded': false,
    'error': 'DOWNLOAD_FAILED',
  });

  setUp(() {
    mock(
      'getBootstrap',
      StudyBootstrapDto(
        currentProfileId: 'default-learner',
        contextEpoch: 1,
        profiles: [
          LearnerProfileDto(
            id: 'default-learner',
            name: '个人',
            memoryEnabled: true,
            activeStudyMode: 'CHAT',
          ),
        ],
        activeModelId: model.id,
        activeModelName: model.name,
        studyMode: 'CHAT',
        reduceMotion: false,
        darkMode: false,
      ),
    );
    mock('getMaterials', [file]);
    mock('getMemories', [memory]);
    mock('getProviders', [provider]);
    mock('getSkills', [skill]);
    mock('getArtifacts', [
      ArtifactDto(
        id: 'artifact',
        name: '研究报告阅读笔记.docx',
        extension: 'docx',
        state: 'SUCCEEDED',
        size: 4096,
        sha256: 'hash',
        createdAt: 1,
        previewContent: '# 研究笔记\n\n此处显示真实的预览内容。\n\n## 核心结论\n\n保留完整来源与可读的段落。',
      ),
    ]);
    mock('getArtifactPreview', ArtifactDto(
      id: 'artifact',
      name: '研究报告阅读笔记.docx',
      extension: 'docx',
      state: 'SUCCEEDED',
      size: 4096,
      sha256: 'hash',
      createdAt: 1,
      previewContent: '# 研究笔记\n\n此处显示真实的预览内容。',
    ));
    mock('getConversations', <ConversationSummaryDto>[]);
    mock(
      'getDiagnostics',
      DiagnosticsDto(
        deviceModel: 'Android',
        osVersion: '16',
        appVersion: '1.0',
        recentCrashCount: 0,
        databaseRevision: 1,
        logSummary: '暂无日志',
      ),
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel('me.rerere.rikkahub/study_events'),
      (_) async => null,
    );
    messenger.setMockMethodCallHandler(
      FrontendFeatures.channel,
      (call) async => switch (call.method) {
        'docConfig' => {
          'endpoint': 'https://example.com/documents',
          'enabled': false,
          'hasCredential': false,
        },
        'docTasks' => [
          {
            'id': task.id,
            'sourceId': task.sourceId,
            'fileName': task.fileName,
            'state': task.state,
            'start': 3,
            'end': 5,
            'downloaded': false,
            'error': task.error,
          },
        ],
        _ => null,
      },
    );
  });

  final pages = <String, Widget>{
    'settings': const SettingsPage(),
    'appearance': const AppearancePage(),
    'providers': const ProvidersPage(),
    'provider-edit': ProviderEditPage(provider: provider),
    'provider-models': ProviderModelsPage(provider: provider),
    'model-config': ModelConfigDetailPage(provider: provider, model: model),
    'skills': const SkillsPage(),
    'skill-detail': SkillDetailPage(skill: skill),
    'import': const ImportSkillPage(),
    'memories': const MemoriesPage(),
    'memory-detail': const MemoryDetailPage(resource: ProfileResourceRoute(
      id: 'memory', profileId: 'default-learner', epoch: 1)),
    'memory-edit': const MemoryEditPage(resource: ProfileResourceRoute(
      id: 'memory', profileId: 'default-learner', epoch: 1)),
    'files': const FilesPage(),
    'file-detail': const FileDetailPage(resource: ProfileResourceRoute(
      id: 'material', profileId: 'default-learner', epoch: 1)),
    'symbol': const SymbolCheckPage(resource: ProfileResourceRoute(
      id: 'material', profileId: 'default-learner', epoch: 1)),
    'artifacts': const ArtifactsPage(),
    'artifact-preview': const ArtifactPreviewPage(resource: ProfileResourceRoute(
      id: 'artifact', profileId: 'default-learner', epoch: 1)),
    'versions': const ArtifactVersionsPage(resource: ProfileResourceRoute(
      id: 'artifact', profileId: 'default-learner', epoch: 1)),
    'document-config': const DocServiceConfigPage(),
    'tasks': const DocTasksPage(),
    'task-detail': const DocTaskDetailPage(resource: ProfileResourceRoute(
      id: 'task', profileId: 'default-learner', epoch: 1)),
    'backup': const BackupPage(),
    'restore': const RestoreBackupPage(),
    'diagnostics': const DiagnosticsPage(),
    'chat': const ChatPage(),
    'search': const SearchChatPage(),
    'chart-entry': const ChartFullscreenPage(),
    'table-entry': const TableFullscreenPage(),
    'parameters-entry': const ParamsFullscreenPage(),
    'attachment-sheet': AttachmentOptionsSheet(onSelect: (_) {}),
    'sources-sheet': const CitationsSheet(
      citations: [
        CitationItem(
          index: 1,
          title: 'A source with a long title',
          publisher: 'example.com',
          summary: '来源说明',
          url: 'https://example.com/source',
        ),
      ],
    ),
  };
  for (final dark in [false, true]) {
    for (final entry in pages.entries) {
      testWidgets(
        '${entry.key}: ${dark ? 'dark large narrow' : 'light mobile'} layout is usable',
        (tester) async {
          tester.view.devicePixelRatio = 1;
          tester.view.physicalSize = dark
              ? const Size(320, 720)
              : const Size(390, 844);
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          await tester.pumpWidget(
            ProviderScope(
              child: MaterialApp(
                theme: dark ? StudyTheme.darkTheme() : StudyTheme.lightTheme(),
                home: MediaQuery(
                  data: MediaQueryData(
                    size: dark ? const Size(320, 720) : const Size(390, 844),
                    textScaler: TextScaler.linear(dark ? 1.3 : 1),
                  ),
                  child: entry.key.endsWith('sheet')
                      ? Scaffold(body: entry.value)
                      : entry.value,
                ),
              ),
            ),
          );
          for (var i = 0; i < 8; i++) {
            await tester.pump(const Duration(milliseconds: 100));
          }
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox.shrink());
        },
      );
    }
  }
  testWidgets(
    'Follow-up keeps draft when conversation changes during preparation',
    (tester) async {
      final pending = Completer<bool>();
      var sends = 0;
      messenger.setMockDecodedMessageHandler<Object?>(
        BasicMessageChannel<Object?>(
          'dev.flutter.pigeon.flutter_ui.StudyHostApi.sendMessage',
          StudyHostApi.pigeonChannelCodec,
        ),
        (_) async {
          sends++;
          return [SimpleCommandResult(success: true, idResult: 'created')];
        },
      );
      final container = ProviderContainer();
      await container.read(bootstrapProvider.future);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(
            theme: StudyTheme.lightTheme(),
            home: Scaffold(
              body: ContextFollowUp(
                hint: '继续追问',
                contextText: '当前图表',
                beforeSend: () => pending.future,
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '保留这段草稿');
      await tester.pump();
      await tester.tap(find.byTooltip('发送消息'));
      await tester.pump();
      container.read(activeConversationIdProvider.notifier).state = 'another';
      container.read(activeConversationIdProvider.notifier).state = null;
      pending.complete(true);
      await tester.pumpAndSettle();
      expect(sends, 0);
      expect(find.text('保留这段草稿'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
      container.dispose();
    },
  );
  test('Source links and empty citations cannot manufacture references', () {
    expect(isWebSource('javascript:alert(1)'), isFalse);
    expect(isWebSource('file:///private'), isFalse);
    expect(isWebSource('https://example.com/source'), isTrue);
    expect(
      messageCitations(
        MessageDto(
          id: 'm',
          nodeId: 'node',
          revision: 1,
          role: 'ASSISTANT',
          parts: [MessagePartDto(type: 'text', text: '没有来源')],
          generationState: 'COMPLETED',
          createdAt: '0',
        ),
      ),
      isEmpty,
    );
  });
  test('Appearance preferences survive a provider recreation', () async {
    final persisted = <String, dynamic>{};
    messenger.setMockMethodCallHandler(AppearanceStorage.channel, (call) async {
      if (call.method == 'load') return persisted;
      persisted.addAll(Map<String, dynamic>.from(call.arguments as Map));
      return null;
    });
    final container = ProviderContainer();
    container.read(themeModeProvider.notifier).setMode(ThemeMode.dark);
    container.read(fontScaleProvider.notifier).setScale(1.25);
    await Future<void>.delayed(Duration.zero);
    container.dispose();
    await AppearanceStorage.load();
    final restored = ProviderContainer();
    expect(restored.read(themeModeProvider), ThemeMode.dark);
    expect(restored.read(fontScaleProvider), 1.25);
    restored.dispose();
    AppearanceStorage.values = {};
  });
}
