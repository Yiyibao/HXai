import 'study_host_api.g.dart';

/// Cumulative replacements: duplicates never append text a second time.
ConversationDetailDto? applyChatDisplayPatch(
  ConversationDetailDto detail,
  Map payload,
) {
  if (payload['conversationId'] != detail.id || payload['changes'] is! List)
    return null;
  final nodes = List<MessageNodeDto>.of(detail.nodes);
  for (final raw in payload['changes'] as List) {
    if (raw is! Map) return null;
    final index = nodes.indexWhere((n) => n.nodeId == raw['nodeId']);
    if (index < 0) return null;
    final node = nodes[index];
    if (node.selectIndex < 0 || node.selectIndex >= node.branches.length)
      return null;
    final message = node.branches[node.selectIndex];
    if (message.id != raw['messageId'] || raw['parts'] is! List) return null;
    final parts = List<MessagePartDto>.of(message.parts);
    var changed = false;
    for (final update in raw['parts'] as List) {
      if (update is! Map || update['index'] is! int) return null;
      final p = update['index'] as int;
      if (p < 0 || p >= parts.length) return null;
      final old = parts[p];
      final field = old.type == 'text'
          ? 'text'
          : old.type == 'reasoning'
          ? 'reasoning'
          : old.type == 'tool'
          ? 'toolInput'
          : null;
      final current = old.text ?? old.reasoning ?? old.toolInput ?? '';
      final incoming = field == null ? null : update[field];
      if (incoming is! String) return null;
      // A snapshot can already include queued patches. Never regress its tail.
      if (current.startsWith(incoming)) continue;
      if (!incoming.startsWith(current)) return null;
      // Codec roundtrip retains every optional field as the protocol evolves.
      final copy = MessagePartDto.decode(old.encode());
      if (update.containsKey('text') &&
          old.type == 'text' &&
          update['text'] is String) {
        copy.text = update['text'] as String;
      } else if (update.containsKey('reasoning') &&
          old.type == 'reasoning' &&
          update['reasoning'] is String) {
        copy.reasoning = update['reasoning'] as String;
      } else if (update.containsKey('toolInput') &&
          old.type == 'tool' &&
          update['toolInput'] is String) {
        copy.toolInput = update['toolInput'] as String;
      } else {
        return null;
      }
      parts[p] = copy;
      changed = true;
    }
    if (!changed) continue;
    final branches = List<MessageDto>.of(node.branches);
    branches[node.selectIndex] = MessageDto(
      id: message.id,
      nodeId: message.nodeId,
      role: message.role,
      parts: parts,
      createdAt: message.createdAt,
      revision: message.revision + 1,
      generationState: message.generationState,
    );
    nodes[index] = MessageNodeDto(
      nodeId: node.nodeId,
      selectIndex: node.selectIndex,
      branches: branches,
    );
  }
  return ConversationDetailDto(
    id: detail.id,
    title: detail.title,
    profileId: detail.profileId,
    studyMode: detail.studyMode,
    nodes: nodes,
    isGenerating: detail.isGenerating,
    lifecycleRevision: detail.lifecycleRevision,
    draftText: detail.draftText,
    activeModelId: detail.activeModelId,
    activeModelName: detail.activeModelName,
  );
}

/// Equality excludes growing message content; each visible row subscribes separately.
class ChatLayoutSnapshot {
  ChatLayoutSnapshot(this.detail, this.loading, this.error);
  final ConversationDetailDto? detail;
  final bool loading;
  final Object? error;
  String get signature =>
      '${detail?.id}:${detail?.title}:${detail?.isGenerating}:${detail?.lifecycleRevision}:${detail?.activeModelId}:${detail?.activeModelName}:'
      '${detail?.nodes.map((n) => '${n.nodeId}/${n.selectIndex}/${n.branches.map((m) => m.id).join(',')}').join(';')}';
  @override
  bool operator ==(Object other) =>
      other is ChatLayoutSnapshot &&
      loading == other.loading &&
      error == other.error &&
      signature == other.signature;
  @override
  int get hashCode => Object.hash(signature, loading, error);
}
