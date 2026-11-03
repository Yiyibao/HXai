import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'bridge/study_host_api.g.dart';
import 'pages/chat_page.dart';
import 'pages/image_creation_page.dart';
import 'pages/search_chat_page.dart';
import 'pages/chart_fullscreen_page.dart';
import 'pages/table_fullscreen_page.dart';
import 'pages/params_fullscreen_page.dart';
import 'pages/files_page.dart';
import 'pages/file_detail_page.dart';
import 'pages/symbol_check_page.dart';
import 'pages/artifacts_page.dart';
import 'pages/artifact_preview_page.dart';
import 'pages/artifact_versions_page.dart';
import 'pages/settings_page.dart';
import 'pages/providers_page.dart';
import 'pages/provider_edit_page.dart';
import 'pages/provider_models_page.dart';
import 'pages/model_config_detail_page.dart';
import 'pages/skills_page.dart';
import 'pages/skill_detail_page.dart';
import 'pages/import_skill_page.dart';
import 'pages/memories_page.dart';
import 'pages/memory_detail_page.dart';
import 'pages/memory_edit_page.dart';
import 'pages/doc_service_config_page.dart';
import 'pages/doc_tasks_page.dart';
import 'pages/doc_task_detail_page.dart';
import 'pages/backup_page.dart';
import 'pages/restore_backup_page.dart';
import 'pages/diagnostics_page.dart';
import 'pages/appearance_page.dart';
import 'providers/profile_resource.dart';

final GlobalKey<NavigatorState> _rootNavigatorKey = GlobalKey<NavigatorState>();

final router = GoRouter(
  navigatorKey: _rootNavigatorKey,
  initialLocation: '/chat',
  routes: [
    // Root and Main Chat Page (ChatGPT style primary interface)
    GoRoute(path: '/', builder: (context, state) => const ChatPage()),
    GoRoute(path: '/chat', builder: (context, state) => const ChatPage()),
    GoRoute(
      path: '/images',
      builder: (context, state) => const ImageCreationPage(),
    ),
    // Search in conversations
    GoRoute(
      path: '/search-chat',
      builder: (context, state) => const SearchChatPage(),
    ),
    GoRoute(
      path: '/chat/search',
      builder: (context, state) => const SearchChatPage(),
    ),
    GoRoute(path: '/materials', redirect: (context, state) => '/files'),

    // Fullscreen interactive analysis pages
    GoRoute(
      path: '/chart-fullscreen',
      builder: (context, state) => const ChartFullscreenPage(),
    ),
    GoRoute(
      path: '/table-fullscreen',
      builder: (context, state) => const TableFullscreenPage(),
    ),
    GoRoute(
      path: '/params-fullscreen',
      builder: (context, state) => const ParamsFullscreenPage(),
    ),

    // File Library & File Details
    GoRoute(
      path: '/files',
      builder: (context, state) => const FilesPage(),
      routes: [
        GoRoute(
          path: 'detail',
          builder: (context, state) {
            return FileDetailPage(
              resource: state.extra is ProfileResourceRoute
                  ? state.extra as ProfileResourceRoute
                  : null,
            );
          },
        ),
        GoRoute(
          path: 'symbol-check',
          builder: (context, state) {
            return SymbolCheckPage(
              resource: state.extra is ProfileResourceRoute
                  ? state.extra as ProfileResourceRoute
                  : null,
            );
          },
        ),
      ],
    ),

    // Generated Artifacts & Versions
    GoRoute(
      path: '/artifacts',
      builder: (context, state) => const ArtifactsPage(),
      routes: [
        GoRoute(
          path: 'preview',
          builder: (context, state) {
            return ArtifactPreviewPage(
              resource: state.extra is ProfileResourceRoute
                  ? state.extra as ProfileResourceRoute
                  : null,
            );
          },
        ),
        GoRoute(
          path: 'versions',
          builder: (context, state) {
            return ArtifactVersionsPage(
              resource: state.extra is ProfileResourceRoute
                  ? state.extra as ProfileResourceRoute
                  : null,
            );
          },
        ),
      ],
    ),

    // Settings & Configuration hierarchy
    GoRoute(
      path: '/settings',
      builder: (context, state) => const SettingsPage(),
      routes: [
        // Providers & Models
        GoRoute(
          path: 'providers',
          builder: (context, state) => const ProvidersPage(),
          routes: [
            GoRoute(
              path: 'edit',
              builder: (context, state) {
                final provider = state.extra is ProviderDto
                    ? state.extra as ProviderDto
                    : null;
                return ProviderEditPage(provider: provider);
              },
            ),
            GoRoute(
              path: 'models',
              builder: (context, state) {
                final provider = state.extra is ProviderDto
                    ? state.extra as ProviderDto
                    : null;
                return ProviderModelsPage(provider: provider);
              },
            ),
            GoRoute(
              path: 'model-detail',
              builder: (context, state) {
                final extra = state.extra is Map<String, dynamic>
                    ? state.extra as Map<String, dynamic>
                    : null;
                return ModelConfigDetailPage(
                  provider: extra?['provider'] as ProviderDto?,
                  model: extra?['model'] as ModelDto?,
                );
              },
            ),
          ],
        ),

        // Skills
        GoRoute(
          path: 'skills',
          builder: (context, state) => const SkillsPage(),
          routes: [
            GoRoute(
              path: 'detail',
              builder: (context, state) {
                final skill = state.extra is SkillDto
                    ? state.extra as SkillDto
                    : null;
                return SkillDetailPage(skill: skill);
              },
            ),
            GoRoute(
              path: 'import',
              builder: (context, state) => const ImportSkillPage(),
            ),
          ],
        ),

        // Memories
        GoRoute(
          path: 'memories',
          builder: (context, state) => const MemoriesPage(),
          routes: [
            GoRoute(
              path: 'detail',
              builder: (context, state) {
                return MemoryDetailPage(
                  resource: state.extra is ProfileResourceRoute
                      ? state.extra as ProfileResourceRoute
                      : null,
                );
              },
            ),
            GoRoute(
              path: 'edit',
              builder: (context, state) {
                return MemoryEditPage(
                  resource: state.extra is ProfileResourceRoute
                      ? state.extra as ProfileResourceRoute
                      : null,
                );
              },
            ),
          ],
        ),

        // Document Services
        GoRoute(
          path: 'doc-service',
          builder: (context, state) => const DocServiceConfigPage(),
          routes: [
            GoRoute(
              path: 'tasks',
              builder: (context, state) => const DocTasksPage(),
            ),
            GoRoute(
              path: 'task-detail',
              builder: (context, state) {
                return DocTaskDetailPage(
                  resource: state.extra is ProfileResourceRoute
                      ? state.extra as ProfileResourceRoute
                      : null,
                );
              },
            ),
          ],
        ),

        // Appearance & Accessibility
        GoRoute(
          path: 'appearance',
          builder: (context, state) => const AppearancePage(),
        ),

        // Backup & Restore
        GoRoute(
          path: 'backup',
          builder: (context, state) => const BackupPage(),
          routes: [
            GoRoute(
              path: 'restore',
              builder: (context, state) {
                final fileName = state.extra is String
                    ? state.extra as String
                    : null;
                return RestoreBackupPage(backupFileName: fileName);
              },
            ),
          ],
        ),

        // Diagnostics
        GoRoute(
          path: 'diagnostics',
          builder: (context, state) => const DiagnosticsPage(),
        ),
      ],
    ),
  ],
);
