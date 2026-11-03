import 'dart:async';

import '../bridge/chat_display_patch.dart';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../bridge/bridge_client.dart';
import '../bridge/study_host_api.g.dart';

final bridgeApi = StudyBridgeClient.instance.api;
final bridgeEventsProvider = StreamProvider<Map<String, dynamic>>(
  (ref) => StudyBridgeClient.instance.eventStream,
);
void checkCommand(SimpleCommandResult result) {
  if (!result.success) throw StateError(result.error?.message ?? '操作失败');
}

/// 1. Bootstrap Provider
final bootstrapProvider = FutureProvider<StudyBootstrapDto>(
  retry: (_, _) => null,
  (ref) async {
    return await bridgeApi.getBootstrap('', 1);
  },
);

/// 2. Active Profile State
final activeProfileIdProvider =
    NotifierProvider<ActiveProfileIdNotifier, String>(
      ActiveProfileIdNotifier.new,
    );

class ActiveProfileIdNotifier extends Notifier<String> {
  @override
  String build() {
    ref.listen(bootstrapProvider, (_, next) {
      if (next.value != null) state = next.value!.currentProfileId;
    });
    final bootstrap = ref.read(bootstrapProvider).value;
    return bootstrap?.currentProfileId ?? 'default-learner';
  }

  @override
  set state(String val) => super.state = val;
}

final contextEpochProvider = NotifierProvider<ContextEpochNotifier, int>(
  ContextEpochNotifier.new,
);

class ContextEpochNotifier extends Notifier<int> {
  @override
  int build() {
    ref.listen(bootstrapProvider, (_, next) {
      if (next.value != null) state = next.value!.contextEpoch;
    });
    final bootstrap = ref.read(bootstrapProvider).value;
    return bootstrap?.contextEpoch ?? 1;
  }

  @override
  set state(int val) => super.state = val;
}

final profilesListProvider =
    NotifierProvider<ProfilesListNotifier, List<LearnerProfileDto>>(
      ProfilesListNotifier.new,
    );

class ProfilesListNotifier extends Notifier<List<LearnerProfileDto>> {
  @override
  List<LearnerProfileDto> build() {
    final bootstrap = ref.watch(bootstrapProvider).value;
    return bootstrap?.profiles ?? [];
  }

  @override
  set state(List<LearnerProfileDto> val) => super.state = val;
}

/// 3. Active Study Mode (GUIDED, SOCRATIC, EXAM, FREE)
final activeStudyModeProvider =
    NotifierProvider<ActiveStudyModeNotifier, String>(
      ActiveStudyModeNotifier.new,
    );

class ActiveStudyModeNotifier extends Notifier<String> {
  String? _profile;
  int? _epoch;
  @override
  String build() {
    final profile = ref.watch(activeProfileIdProvider);
    final epoch = ref.watch(contextEpochProvider);
    ref.listen(bootstrapProvider, (_, next) {
      final bootstrap = next.value;
      if (bootstrap != null &&
          bootstrap.currentProfileId == profile &&
          bootstrap.contextEpoch == epoch &&
          (_profile != bootstrap.currentProfileId ||
              _epoch != bootstrap.contextEpoch)) {
        _profile = bootstrap.currentProfileId;
        _epoch = bootstrap.contextEpoch;
        state = bootstrap.studyMode;
      }
    });
    final loaded = ref.read(bootstrapProvider).value;
    final bootstrap =
        loaded?.currentProfileId == profile && loaded?.contextEpoch == epoch
        ? loaded
        : null;
    _profile = bootstrap?.currentProfileId;
    _epoch = bootstrap?.contextEpoch;
    return bootstrap?.studyMode ?? 'CHAT';
  }

  @override
  set state(String val) => super.state = val;
}

/// 4. Conversations List for Active Profile
final conversationsProvider =
    AsyncNotifierProvider<ConversationsNotifier, List<ConversationSummaryDto>>(
      ConversationsNotifier.new,
    );

final historyQueryProvider = NotifierProvider<HistoryQueryNotifier, String>(
  HistoryQueryNotifier.new,
);

class HistoryQueryNotifier extends Notifier<String> {
  @override
  String build() {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    return '';
  }

  void update(String query) => state = query.trim();
}

final historyArchivedProvider = NotifierProvider<HistoryArchivedNotifier, bool>(
  HistoryArchivedNotifier.new,
);

class HistoryArchivedNotifier extends Notifier<bool> {
  @override
  bool build() {
    ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    return false;
  }

  void setArchived(bool value) => state = value;
}

/// Explicit refreshes obey the same context lifetime as provider builds.
abstract class ProfileListNotifier<T> extends AsyncNotifier<List<T>> {
  int _revision = 0;
  Future<List<T>> fetch(String profileId);
  @override
  Future<List<T>> build() {
    final profileId = ref.watch(activeProfileIdProvider);
    ref.watch(contextEpochProvider);
    _revision++;
    return fetch(profileId);
  }

  Future<void> refresh() async {
    final requestRef = ref;
    final profileId = requestRef.read(activeProfileIdProvider);
    final epoch = requestRef.read(contextEpochProvider);
    final revision = ++_revision;
    state = const AsyncValue.loading();
    final result = await AsyncValue.guard(() => fetch(profileId));
    if (requestRef.mounted &&
        revision == _revision &&
        requestRef.read(activeProfileIdProvider) == profileId &&
        requestRef.read(contextEpochProvider) == epoch) {
      state = result;
    }
  }
}

class ConversationsNotifier
    extends ProfileListNotifier<ConversationSummaryDto> {
  int? _refreshRevision;
  int? _pageRevision;
  bool hasMore = true;
  @override
  Future<List<ConversationSummaryDto>> build() {
    hasMore = true;
    ref.watch(historyQueryProvider);
    ref.watch(historyArchivedProvider);
    return super.build();
  }

  @override
  Future<void> refresh() async {
    final revision = _revision + 1;
    _refreshRevision = revision;
    hasMore = true;
    try {
      await super.refresh();
    } finally {
      if (_refreshRevision == revision) _refreshRevision = null;
    }
  }

  @override
  Future<List<ConversationSummaryDto>> fetch(String profileId) async {
    final requestRef = ref;
    final revision = _revision;
    final epoch = requestRef.read(contextEpochProvider);
    final query = requestRef.read(historyQueryProvider);
    final archived = requestRef.read(historyArchivedProvider);
    final result = await (query.isEmpty
        ? bridgeApi.getConversations(profileId, 50, 0, archived)
        : bridgeApi.searchConversations(profileId, query, 50, 0, archived));
    if (requestRef.mounted && revision == _revision &&
        requestRef.read(activeProfileIdProvider) == profileId &&
        requestRef.read(contextEpochProvider) == epoch) {
      hasMore = result.length == 50;
    }
    return result;
  }

  Future<bool> loadMore() async {
    if (_refreshRevision == _revision ||
        _pageRevision == _revision ||
        !state.hasValue ||
        state.isLoading)
      return false;
    final requestRef = ref;
    final profile = requestRef.read(activeProfileIdProvider);
    final epoch = requestRef.read(contextEpochProvider);
    final revision = ++_revision;
    _pageRevision = revision;
    final current = state.value ?? [];
    try {
      final query = requestRef.read(historyQueryProvider);
      final archived = requestRef.read(historyArchivedProvider);
      final next = await (query.isEmpty
          ? bridgeApi.getConversations(profile, 50, current.length, archived)
          : bridgeApi.searchConversations(profile, query, 50, current.length, archived));
      if (requestRef.mounted &&
          revision == _revision &&
          requestRef.read(activeProfileIdProvider) == profile &&
          requestRef.read(contextEpochProvider) == epoch) {
        hasMore = next.length == 50;
        state = AsyncValue.data([
          ...current,
          ...next.where((item) => !current.any((old) => old.id == item.id)),
        ]);
        return true;
      }
      return false;
    } catch (_) {
      if (!requestRef.mounted ||
          revision != _revision ||
          requestRef.read(activeProfileIdProvider) != profile ||
          requestRef.read(contextEpochProvider) != epoch) {
        return false;
      }
      rethrow;
    } finally {
      if (_pageRevision == revision) _pageRevision = null;
    }
  }
}

/// 5. Active Conversation Detail
final activeConversationIdProvider =
    NotifierProvider<ActiveConversationIdNotifier, String?>(
      ActiveConversationIdNotifier.new,
    );
final conversationSelectionRevisionProvider =
    NotifierProvider<ConversationSelectionRevisionNotifier, int>(
      ConversationSelectionRevisionNotifier.new,
    );

class ConversationSelectionRevisionNotifier extends Notifier<int> {
  @override
  int build() => 0;
  void bump() => state++;
}

class ActiveConversationIdNotifier extends Notifier<String?> {
  bool _initialized = false;
  int selectionRevision = 0;
  @override
  String? build() {
    ref.listen(bootstrapProvider, (_, next) {
      if (!_initialized && next.value != null) {
        _initialized = true;
        state = next.value!.defaultConversationId;
      }
    });
    final bootstrap = ref.read(bootstrapProvider).value;
    _initialized = bootstrap != null;
    return bootstrap?.defaultConversationId;
  }

  @override
  set state(String? val) {
    selectionRevision++;
    ref.read(conversationSelectionRevisionProvider.notifier).bump();
    super.state = val;
  }
}

final activeConversationDetailProvider =
    AsyncNotifierProvider<ActiveConversationNotifier, ConversationDetailDto?>(
      ActiveConversationNotifier.new,
      retry: (_, _) => null,
    );

class ActiveConversationNotifier extends AsyncNotifier<ConversationDetailDto?> {
  bool _refreshing = false;
  bool _pendingRefresh = false;
  SendMessageRequest? _pendingRequest;
  Timer? _displayTimer;
  final List<Map> _displayPatches = [];
  int _lastSequence = 0;
  int _lifetime = 0;

  void _flushDisplay() {
    _displayTimer?.cancel();
    _displayTimer = null;
    if (_refreshing || !ref.mounted) return;
    var detail = state.value;
    if (detail == null) {
      return; // Initial snapshot may still be loading; keep cumulative replacements.
    }
    for (final patch in _displayPatches) {
      detail = applyChatDisplayPatch(detail!, patch);
      if (detail == null) break;
    }
    _displayPatches.clear();
    if (detail == null) {
      unawaited(refresh());
    } else {
      state = AsyncValue.data(detail);
    }
  }

  @override
  Future<ConversationDetailDto?> build() async {
    _lifetime++;
    listenSelf((_, next) {
      if (next.hasValue && _displayPatches.isNotEmpty && !_refreshing)
        scheduleMicrotask(_flushDisplay);
    });
    _pendingRequest = null;
    _displayTimer?.cancel();
    _displayPatches.clear();
    _lastSequence = 0;
    ref.onDispose(() {
      _displayTimer?.cancel();
      _displayPatches.clear();
    });
    _refreshing = false;
    _pendingRefresh = false;
    final convId = ref.watch(activeConversationIdProvider);
    final profileId = ref.watch(activeProfileIdProvider);
    final epoch = ref.watch(contextEpochProvider);
    ref.listen(bridgeEventsProvider, (_, next) {
      final event = next.value;
      if (event == null ||
          event['profileId'] != profileId ||
          event['contextEpoch'] != epoch)
        return;
      final sequence = event['sequence'] as int?;
      if (sequence != null && sequence <= _lastSequence) return;
      final gap =
          sequence != null &&
          _lastSequence > 0 &&
          sequence != _lastSequence + 1;
      if (sequence != null) _lastSequence = sequence;
      if (!gap && (event['payload'] as Map?)?['conversationId'] != convId) return;
      if (gap || event['type'] == 'conversation_changed') {
        _displayTimer?.cancel();
        _displayTimer = null;
        _displayPatches.clear();
        unawaited(refresh());
      } else if (event['type'] == 'message_patch') {
        _displayPatches.add(event['payload'] as Map);
        _displayTimer ??= Timer(
          const Duration(milliseconds: 40),
          _flushDisplay,
        );
      }
    });
    if (!ref.read(bootstrapProvider).hasValue) return null;
    checkCommand(await bridgeApi.watchConversation(profileId, convId, epoch));
    if (convId == null || convId.isEmpty) return null;
    return await bridgeApi.getConversationDetails(profileId, convId);
  }

  Future<void> refresh() async {
    final requestRef = ref;
    final lifetime = _lifetime;
    final convId = ref.read(activeConversationIdProvider);
    final profileId = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    if (convId == null) return;
    if (_refreshing) {
      _pendingRefresh = true;
      return;
    }
    _refreshing = true;
    try {
      do {
        _pendingRefresh = false;
        final result = await AsyncValue.guard(
          () => bridgeApi.getConversationDetails(profileId, convId),
        );
        if (!requestRef.mounted ||
            lifetime != _lifetime ||
            requestRef.read(activeProfileIdProvider) != profileId ||
            requestRef.read(contextEpochProvider) != epoch ||
            requestRef.read(activeConversationIdProvider) != convId)
          return;
        state = result;
      } while (_pendingRefresh);
    } finally {
      if (requestRef.mounted && lifetime == _lifetime) {
        _refreshing = false;
        if (_displayPatches.isNotEmpty) _flushDisplay();
      }
    }
  }

  Future<void> sendMessage(String text, {List<String>? attachmentPaths}) async {
    final requestRef = ref;
    final profileId = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    final studyMode = ref.read(activeStudyModeProvider);
    final convId = ref.read(activeConversationIdProvider);
    final selectionRevision = ref
        .read(activeConversationIdProvider.notifier)
        .selectionRevision;

    final previous = _pendingRequest;
    final req =
        previous != null &&
            previous.profileId == profileId &&
            previous.contextEpoch == epoch &&
            previous.conversationId == convId &&
            previous.text == text &&
            previous.studyMode == studyMode &&
            previous.attachmentPaths.toString() == attachmentPaths.toString()
        ? previous
        : SendMessageRequest(
            requestId: 'req_${DateTime.now().microsecondsSinceEpoch}',
            profileId: profileId,
            contextEpoch: epoch,
            text: text,
            studyMode: studyMode,
            conversationId: convId,
            attachmentPaths: attachmentPaths,
          );
    _pendingRequest = req;
    final res = await bridgeApi.sendMessage(req);
    checkCommand(res);
    if (identical(_pendingRequest, req)) _pendingRequest = null;
    if (!requestRef.mounted ||
        requestRef.read(activeProfileIdProvider) != profileId ||
        requestRef.read(contextEpochProvider) != epoch ||
        requestRef.read(activeConversationIdProvider) != convId ||
        requestRef
                .read(activeConversationIdProvider.notifier)
                .selectionRevision !=
            selectionRevision) {
      return;
    }
    if (res.idResult != null) {
      final conversations = requestRef.read(conversationsProvider.notifier);
      if (convId == null || convId != res.idResult) {
        ref.read(activeConversationIdProvider.notifier).state = res.idResult;
      } else {
        await refresh();
      }
      unawaited(conversations.refresh());
    }
  }

  Future<void> stopGeneration() async {
    final convId = ref.read(activeConversationIdProvider);
    final profileId = ref.read(activeProfileIdProvider);
    if (convId == null) return;

    checkCommand(
      await bridgeApi.stopGeneration(
        profileId,
        convId,
        'stop_${DateTime.now().millisecondsSinceEpoch}',
      ),
    );
    await refresh();
  }

  Future<void> retryMessage(String nodeId) async {
    final convId = ref.read(activeConversationIdProvider);
    final profileId = ref.read(activeProfileIdProvider);
    if (convId == null) return;

    checkCommand(
      await bridgeApi.retryMessage(
        profileId,
        convId,
        nodeId,
        'retry_${DateTime.now().millisecondsSinceEpoch}',
      ),
    );
    await refresh();
  }

  Future<void> switchBranch(String nodeId, int branchIndex) async {
    final convId = ref.read(activeConversationIdProvider);
    final profileId = ref.read(activeProfileIdProvider);
    if (convId == null) return;

    checkCommand(
      await bridgeApi.switchBranch(profileId, convId, nodeId, branchIndex),
    );
    await refresh();
  }

  Future<void> approveTool(String toolCallId, bool approved) async {
    final convId = ref.read(activeConversationIdProvider);
    final profileId = ref.read(activeProfileIdProvider);
    if (convId == null) return;

    checkCommand(
      await bridgeApi.approveToolCall(
        profileId,
        convId,
        toolCallId,
        approved,
        'approve_${DateTime.now().millisecondsSinceEpoch}',
      ),
    );
    await refresh();
  }
}

/// 6. Materials Provider
final materialsProvider =
    AsyncNotifierProvider<MaterialsNotifier, List<MaterialFileDto>>(
      MaterialsNotifier.new,
    );

class MaterialsNotifier extends ProfileListNotifier<MaterialFileDto> {
  @override
  Future<List<MaterialFileDto>> fetch(String profileId) =>
      bridgeApi.getMaterials(profileId, '');

  Future<bool> checkSymbol(String materialId, String chosenSymbol) async {
    final profileId = ref.read(activeProfileIdProvider);
    final res = await bridgeApi.checkSymbol(
      profileId,
      materialId,
      chosenSymbol,
      'sym_${DateTime.now().millisecondsSinceEpoch}',
    );
    if (res.success) {
      await refresh();
    }
    return res.success;
  }
}

/// 7. Memories Provider
final memoriesProvider =
    AsyncNotifierProvider<MemoriesNotifier, List<MemoryItemDto>>(
      MemoriesNotifier.new,
    );

class MemoriesNotifier extends ProfileListNotifier<MemoryItemDto> {
  @override
  Future<List<MemoryItemDto>> fetch(String profileId) =>
      bridgeApi.getMemories(profileId, '');

  Future<bool> updateMemory(
    String memoryId,
    String newContent,
    int revision,
  ) async {
    final profileId = ref.read(activeProfileIdProvider);
    final res = await bridgeApi.updateMemory(
      profileId,
      memoryId,
      newContent,
      revision,
      'mem_${DateTime.now().millisecondsSinceEpoch}',
    );
    if (res.success) {
      await refresh();
    }
    return res.success;
  }

  Future<bool> deleteMemory(String memoryId) async {
    final profileId = ref.read(activeProfileIdProvider);
    final res = await bridgeApi.deleteMemory(
      profileId,
      memoryId,
      'del_${DateTime.now().millisecondsSinceEpoch}',
    );
    if (res.success) {
      await refresh();
    }
    return res.success;
  }

  Future<void> toggleAutoMemory(bool enabled) async {
    final profileId = ref.read(activeProfileIdProvider);
    final requestRef = ref;
    checkCommand(await bridgeApi.toggleAutoMemory(profileId, enabled));
    if (requestRef.mounted &&
        requestRef.read(activeProfileIdProvider) == profileId)
      requestRef.invalidate(bootstrapProvider);
  }
}

/// 8. Artifacts Provider
final artifactsProvider =
    AsyncNotifierProvider<ArtifactsNotifier, List<ArtifactDto>>(
      ArtifactsNotifier.new,
    );

/// Extract only the selected artifact, never every file in the list.
final artifactPreviewProvider =
    FutureProvider.autoDispose.family<ArtifactDto?, String>((ref, id) async {
      final profile = ref.watch(activeProfileIdProvider);
      final epoch = ref.watch(contextEpochProvider);
      final list = ref.watch(artifactsProvider);
      if (list.isLoading || list.hasError) return null;
      final item = list.value?.where((item) => item.id == id).firstOrNull;
      if (item == null) return null;
      final preview = await bridgeApi.getArtifactPreview(profile, id, epoch);
      if (!ref.mounted ||
          ref.read(activeProfileIdProvider) != profile ||
          ref.read(contextEpochProvider) != epoch) return null;
      final current = ref.read(artifactsProvider);
      if (current.isLoading || current.hasError ||
          current.value?.any((candidate) => candidate == item) != true) {
        return null;
      }
      return preview;
    });

class ArtifactsNotifier extends ProfileListNotifier<ArtifactDto> {
  @override
  Future<List<ArtifactDto>> fetch(String profileId) =>
      bridgeApi.getArtifacts(profileId);

  Future<void> openArtifact(String artifactId) async {
    final profileId = ref.read(activeProfileIdProvider);
    checkCommand(await bridgeApi.openArtifact(profileId, artifactId));
  }

  Future<void> saveArtifact(String artifactId) async {
    checkCommand(
      await bridgeApi.saveArtifact(
        ref.read(activeProfileIdProvider),
        artifactId,
      ),
    );
  }

  Future<void> shareArtifact(String artifactId) async {
    final profileId = ref.read(activeProfileIdProvider);
    checkCommand(await bridgeApi.shareArtifact(profileId, artifactId));
  }
}

/// 9. Providers & Models Configuration
final providersConfigProvider =
    AsyncNotifierProvider<ProvidersConfigNotifier, List<ProviderDto>>(
      ProvidersConfigNotifier.new,
    );

class ProvidersConfigNotifier extends AsyncNotifier<List<ProviderDto>> {
  @override
  Future<List<ProviderDto>> build() async {
    return await bridgeApi.getProviders();
  }

  Future<void> refresh() async {
    state = await AsyncValue.guard(() => bridgeApi.getProviders());
  }

  Future<SimpleCommandResult> testConnection(String providerId) async {
    return await bridgeApi.testProvider(providerId);
  }

  Future<void> selectActiveModel(String providerId, String modelId) async {
    checkCommand(await bridgeApi.selectActiveModel(providerId, modelId));
    ref.invalidate(bootstrapProvider);
    await refresh();
  }
}

/// 10. Diagnostics Provider
final diagnosticsProvider = FutureProvider<DiagnosticsDto>((ref) async {
  return await bridgeApi.getDiagnostics();
});

final skillsProvider = AsyncNotifierProvider<SkillsNotifier, List<SkillDto>>(
  SkillsNotifier.new,
  retry: (_, _) => null,
);

class SkillsNotifier extends AsyncNotifier<List<SkillDto>> {
  @override
  Future<List<SkillDto>> build() =>
      bridgeApi.getSkills(ref.watch(activeProfileIdProvider));
  Future<void> refresh() async {
    final owner = ref.read(activeProfileIdProvider);
    final epoch = ref.read(contextEpochProvider);
    final result = await AsyncValue.guard(() => bridgeApi.getSkills(owner));
    if (ref.mounted &&
        owner == ref.read(activeProfileIdProvider) &&
        epoch == ref.read(contextEpochProvider))
      state = result;
  }

  Future<void> setEnabled(String name, bool enabled) async {
    checkCommand(
      await bridgeApi.setSkillEnabled(
        ref.read(activeProfileIdProvider),
        name,
        enabled,
        ref.read(contextEpochProvider),
      ),
    );
    await refresh();
  }
}
