import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../theme/study_theme.dart';

final accentColorProvider = NotifierProvider<AccentColorNotifier, AccentColor>(
  AccentColorNotifier.new,
);

class AccentColorNotifier extends Notifier<AccentColor> {
  @override
  AccentColor build() =>
      AccentColor.parse(AppearanceStorage.values['accentColor']);
  void setColor(AccentColor color) {
    state = color;
    AppearanceStorage.save('accentColor', color.name);
  }
}

/// Only appearance preferences are stored here; no credentials or user content.
class AppearanceStorage {
  static const channel = MethodChannel('me.rerere.rikkahub/appearance');
  static Map<String, dynamic> values = {};
  static Future<void> load() async {
    try {
      values = Map<String, dynamic>.from(
        await channel.invokeMapMethod<String, dynamic>('load') ?? {},
      );
    } on MissingPluginException {
      values = {};
    } on PlatformException {
      values = {};
    }
  }

  static void save(String key, Object value) {
    values[key] = value;
    unawaited(
      channel
          .invokeMethod<void>('save', {key: value})
          .catchError((Object _) {}),
    );
  }
}

final followSystemFontProvider = NotifierProvider<SystemFontNotifier, bool>(
  SystemFontNotifier.new,
);

class SystemFontNotifier extends Notifier<bool> {
  @override
  bool build() => AppearanceStorage.values['systemFont'] as bool? ?? true;
  void setFollow(bool value) {
    state = value;
    AppearanceStorage.save('systemFont', value);
  }
}

/// Theme mode provider: system, light, dark
final themeModeProvider = NotifierProvider<ThemeModeNotifier, ThemeMode>(
  ThemeModeNotifier.new,
);

class ThemeModeNotifier extends Notifier<ThemeMode> {
  @override
  ThemeMode build() => switch (AppearanceStorage.values['theme']) {
    'dark' => ThemeMode.dark,
    'system' => ThemeMode.system,
    _ => ThemeMode.light,
  };

  void setMode(ThemeMode mode) {
    state = mode;
    AppearanceStorage.save('theme', mode.name);
  }

  void setThemeMode(ThemeMode mode) => setMode(mode);
}

/// Font scale multiplier: 1.0 (default) or 1.25 (large font for P44)
final fontScaleProvider = NotifierProvider<FontScaleNotifier, double>(
  FontScaleNotifier.new,
);

class FontScaleNotifier extends Notifier<double> {
  @override
  double build() =>
      (AppearanceStorage.values['fontScale'] as num?)?.toDouble() ?? 1.0;

  void setScale(double scale) {
    state = scale.clamp(1.0, 1.5);
    AppearanceStorage.save('fontScale', state);
  }

  void setFontScale(double scale) => setScale(scale);
}

/// Auto follow scrolling during chat generation
final autoFollowScrollProvider =
    NotifierProvider<AutoFollowScrollNotifier, bool>(
      AutoFollowScrollNotifier.new,
    );

class AutoFollowScrollNotifier extends Notifier<bool> {
  @override
  bool build() => AppearanceStorage.values['autoFollow'] as bool? ?? true;

  void setAutoFollow(bool value) {
    state = value;
    AppearanceStorage.save('autoFollow', value);
  }

  void toggle() => state = !state;
}

/// Reduce motion preference
final reduceMotionSettingProvider =
    NotifierProvider<ReduceMotionSettingNotifier, bool>(
      ReduceMotionSettingNotifier.new,
    );

class ReduceMotionSettingNotifier extends Notifier<bool> {
  @override
  bool build() => AppearanceStorage.values['reduceMotion'] as bool? ?? false;

  void setReduceMotion(bool value) {
    state = value;
    AppearanceStorage.save('reduceMotion', value);
  }

  void toggle() => state = !state;
}

/// Document service configuration model
class DocServiceConfig {
  final String endpoint;
  final String token;
  final bool enabled;
  final String testStatus; // 'untested', 'testing', 'connected', 'failed'
  final String? testMessage;

  String get serviceUrl => endpoint;
  String get credentials => token;

  const DocServiceConfig({
    this.endpoint = '',
    this.token = '',
    this.enabled = false,
    this.testStatus = 'untested',
    this.testMessage,
  });

  DocServiceConfig copyWith({
    String? endpoint,
    String? token,
    bool? enabled,
    String? testStatus,
    String? testMessage,
  }) {
    return DocServiceConfig(
      endpoint: endpoint ?? this.endpoint,
      token: token ?? this.token,
      enabled: enabled ?? this.enabled,
      testStatus: testStatus ?? this.testStatus,
      testMessage: testMessage ?? this.testMessage,
    );
  }
}

final docServiceConfigProvider =
    NotifierProvider<DocServiceConfigNotifier, DocServiceConfig>(
      DocServiceConfigNotifier.new,
    );

class DocServiceConfigNotifier extends Notifier<DocServiceConfig> {
  @override
  DocServiceConfig build() => const DocServiceConfig();

  void update(DocServiceConfig Function(DocServiceConfig) updater) {
    state = updater(state);
  }

  void updateConfig({
    String? serviceUrl,
    String? credentials,
    String? endpoint,
    String? token,
    bool? enabled,
  }) {
    state = state.copyWith(
      endpoint: endpoint ?? serviceUrl,
      token: token ?? credentials,
      enabled: enabled,
    );
  }

  void setConfig(DocServiceConfig config) {
    state = config;
  }
}

/// Document task model for P35 and P36
class DocTaskItem {
  final String id;
  final String fileName;
  final String sourceConversation;
  final String processingRange;
  final String state; // 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'
  final String statusText;
  final DateTime submittedAt;
  final List<DocTaskStep> steps;

  String get status => state;
  String get conversationTitle => sourceConversation;
  String get scopeText => processingRange;

  const DocTaskItem({
    required this.id,
    required this.fileName,
    required this.sourceConversation,
    required this.processingRange,
    required this.state,
    required this.statusText,
    required this.submittedAt,
    required this.steps,
  });
}

typedef DocTaskRecord = DocTaskItem;

class DocTaskStep {
  final String name;
  final String state; // 'DONE', 'RUNNING', 'FAILED', 'PENDING'

  const DocTaskStep({required this.name, required this.state});
}

final docTasksProvider = NotifierProvider<DocTasksNotifier, List<DocTaskItem>>(
  DocTasksNotifier.new,
);

class DocTasksNotifier extends Notifier<List<DocTaskItem>> {
  @override
  List<DocTaskItem> build() => [];

  void retryTask(String taskId) {
    state = state.map((t) {
      if (t.id == taskId) {
        return DocTaskItem(
          id: t.id,
          fileName: t.fileName,
          sourceConversation: t.sourceConversation,
          processingRange: t.processingRange,
          state: 'RUNNING',
          statusText: '正在重新下载结果...',
          submittedAt: t.submittedAt,
          steps: t.steps
              .map(
                (s) => s.name == '下载结果'
                    ? const DocTaskStep(name: '下载结果', state: 'RUNNING')
                    : s,
              )
              .toList(),
        );
      }
      return t;
    }).toList();
  }
}

/// Backup record model for P37 & P38
class BackupRecord {
  final String id;
  final String fileName;
  final String size;
  final DateTime createdAt;
  final int conversationCount;
  final int fileCount;
  final int memoryCount;
  final int skillCount;
  final bool isEncrypted;

  const BackupRecord({
    required this.id,
    required this.fileName,
    required this.size,
    required this.createdAt,
    this.conversationCount = 0,
    this.fileCount = 0,
    this.memoryCount = 0,
    this.skillCount = 0,
    this.isEncrypted = false,
  });
}

final backupRecordsProvider =
    NotifierProvider<BackupRecordsNotifier, List<BackupRecord>>(
      BackupRecordsNotifier.new,
    );

class BackupRecordsNotifier extends Notifier<List<BackupRecord>> {
  @override
  List<BackupRecord> build() => [];

  void addRecord(BackupRecord record) {
    state = [record, ...state];
  }
}
