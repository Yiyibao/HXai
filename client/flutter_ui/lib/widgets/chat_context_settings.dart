import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/frontend_feature_providers.dart';
import '../providers/study_providers.dart';

final chatContextSettingsProvider = FutureProvider.autoDispose<Map>((
  ref,
) async {
  ref.watch(activeProfileIdProvider);
  ref.watch(contextEpochProvider);
  return await FrontendFeatures.call<Map>(ref, 'chatContextSettings') ?? {};
});
final chatRuntimeStatusProvider = FutureProvider.autoDispose
    .family<Map, String>((ref, id) async {
      final profile = ref.watch(activeProfileIdProvider);
      final epoch = ref.watch(contextEpochProvider);
      ref.listen(bridgeEventsProvider, (_, next) {
        final e = next.value;
        if (const ['chat_status', 'conversation_changed'].contains(e?['type']) &&
            e?['profileId'] == profile &&
            e?['contextEpoch'] == epoch &&
            (e?['payload'] as Map?)?['conversationId'] == id)
          ref.invalidateSelf();
      });
      return await FrontendFeatures.call<Map>(ref, 'chatStatus', {
            'conversationId': id,
          }) ??
          {};
    });

class ChatContextSettings extends ConsumerWidget {
  const ChatContextSettings({super.key});
  static Future<void> show(BuildContext context) => showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    builder: (_) => const ChatContextSettings(),
  );
  @override
  Widget build(BuildContext context, WidgetRef ref) => SafeArea(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: ref
          .watch(chatContextSettingsProvider)
          .when(
            loading: () => const SizedBox(
              height: 100,
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (_, _) => Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('上下文设置加载失败'),
                TextButton(
                  onPressed: () => ref.invalidate(chatContextSettingsProvider),
                  child: const Text('重试'),
                ),
              ],
            ),
            data: (settings) => Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('上下文预算', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 12),
                const Text(
                  '达到预算时整理旧对话，原始聊天保留。数值是输入 Token 估算预算，请为模型输出、图片和工具预留空间；摘要会使用当前模型产生额外请求。',
                ),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 8,
                  children: [
                    for (final budget in [4000, 8000, 16000, 32000, 64000])
                      ChoiceChip(
                        label: Text('${budget ~/ 1000}k'),
                        selected: settings['budget'] == budget,
                        onSelected: (_) async {
                          try {
                            await FrontendFeatures.fromWidget<void>(
                              ref,
                              'saveChatContextSettings',
                              {'budget': budget},
                            );
                            if (context.mounted)
                              ref.invalidate(chatContextSettingsProvider);
                          } catch (error) {
                            if (context.mounted)
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(content: Text('设置未保存：$error')),
                              );
                          }
                        },
                      ),
                  ],
                ),
              ],
            ),
          ),
    ),
  );
}
