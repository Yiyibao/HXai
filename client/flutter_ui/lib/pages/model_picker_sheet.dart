import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/study_providers.dart';
import '../theme/study_theme.dart';

void showModelPickerSheet(BuildContext context) {
  showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (_) => const _ModelPickerSheetContent(),
  );
}

class _ModelPickerSheetContent extends ConsumerStatefulWidget {
  const _ModelPickerSheetContent();
  @override
  ConsumerState<_ModelPickerSheetContent> createState() =>
      _ModelPickerSheetContentState();
}

class _ModelPickerSheetContentState
    extends ConsumerState<_ModelPickerSheetContent> {
  String query = '';
  bool selecting = false;
  @override
  Widget build(BuildContext context) {
    final c = StudyPalette.of(context);
    final providers = ref.watch(providersConfigProvider);
    final current = ref.watch(bootstrapProvider).value?.activeModelId;
    return SafeArea(
      top: false,
      child: Container(
        padding: EdgeInsets.only(
          bottom: MediaQuery.viewInsetsOf(context).bottom,
        ),
        decoration: BoxDecoration(
          color: c.bgWhite,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        ),
        child: DraggableScrollableSheet(
          initialChildSize: .75,
          minChildSize: .5,
          maxChildSize: .95,
          expand: false,
          builder: (context, scroll) => Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
            child: Column(
              children: [
                Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(
                    color: c.borderGray,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Text(
                      '选择模型',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                        color: c.textBlack,
                      ),
                    ),
                    const Spacer(),
                    TextButton(
                      onPressed: () {
                        Navigator.pop(context);
                        context.push('/settings/providers');
                      },
                      child: const Text('管理'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                TextField(
                  onChanged: (s) =>
                      setState(() => query = s.trim().toLowerCase()),
                  decoration: const InputDecoration(
                    hintText: '搜索模型',
                    prefixIcon: Icon(Icons.search, size: 20),
                  ),
                ),
                const SizedBox(height: 14),
                Expanded(
                  child: providers.when(
                    loading: () =>
                        const Center(child: CircularProgressIndicator()),
                    error: (e, _) => Center(child: Text('模型加载失败：$e')),
                    data: (all) {
                      final matches = all
                          .where(
                            (p) =>
                                p.enabled &&
                                p.models.any(
                                  (m) =>
                                      m.enabled &&
                                      (query.isEmpty ||
                                          '${m.name} ${m.id} ${p.name}'
                                              .toLowerCase()
                                              .contains(query)),
                                ),
                          )
                          .toList();
                      if (matches.isEmpty)
                        return Center(
                          child: Text(
                            query.isEmpty ? '暂无启用的模型，请先在管理中配置。' : '没有找到匹配的模型',
                            style: TextStyle(color: c.textGray),
                          ),
                        );
                      return ListView(
                        controller: scroll,
                        children: [
                          for (final p in matches) ...[
                            Padding(
                              padding: const EdgeInsets.fromLTRB(4, 12, 4, 8),
                              child: Text(
                                p.name,
                                style: TextStyle(
                                  fontSize: 14,
                                  fontWeight: FontWeight.w600,
                                  color: c.textBlack,
                                ),
                              ),
                            ),
                            for (final m in p.models.where(
                              (m) =>
                                  m.enabled &&
                                  (query.isEmpty ||
                                      '${m.name} ${m.id} ${p.name}'
                                          .toLowerCase()
                                          .contains(query)),
                            ))
                              ListTile(
                                contentPadding: const EdgeInsets.symmetric(
                                  horizontal: 4,
                                  vertical: 6,
                                ),
                                leading: Icon(
                                  Icons.smart_toy_outlined,
                                  size: 24,
                                  color: c.textBlack,
                                ),
                                title: Text(
                                  m.name,
                                  style: TextStyle(
                                    fontSize: 15,
                                    fontWeight: m.id == current
                                        ? FontWeight.w600
                                        : FontWeight.w400,
                                  ),
                                ),
                                subtitle: Padding(
                                  padding: const EdgeInsets.only(top: 6),
                                  child: Wrap(
                                    spacing: 6,
                                    runSpacing: 4,
                                    children: [
                                      for (final label in [
                                        if (m.supportImage) '图片',
                                        if (m.supportTool) '工具',
                                        if (!m.supportImage && !m.supportTool)
                                          '仅文本',
                                      ])
                                        Container(
                                          padding: const EdgeInsets.symmetric(
                                            horizontal: 6,
                                            vertical: 2,
                                          ),
                                          decoration: BoxDecoration(
                                            color: c.bubbleGray,
                                            borderRadius: BorderRadius.circular(
                                              5,
                                            ),
                                          ),
                                          child: Text(
                                            label,
                                            style: TextStyle(
                                              fontSize: 11,
                                              color: c.textGray,
                                            ),
                                          ),
                                        ),
                                    ],
                                  ),
                                ),
                                trailing: m.id == current
                                    ? Icon(
                                        Icons.check_circle,
                                        size: 20,
                                        color: c.textBlack,
                                      )
                                    : null,
                                onTap: selecting
                                    ? null
                                    : () async {
                                        setState(() => selecting = true);
                                        try {
                                          await ref
                                              .read(
                                                providersConfigProvider
                                                    .notifier,
                                              )
                                              .selectActiveModel(p.id, m.id);
                                          if (context.mounted)
                                            Navigator.pop(context);
                                        } catch (e) {
                                          if (context.mounted)
                                            ScaffoldMessenger.of(context)
                                                .showSnackBar(
                                                  SnackBar(content: Text('$e')),
                                                );
                                        } finally {
                                          if (mounted)
                                            setState(() => selecting = false);
                                        }
                                      },
                              ),
                          ],
                        ],
                      );
                    },
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(top: 10),
                  child: Text(
                    '模型名称与能力以实际配置为准',
                    textAlign: TextAlign.center,
                    style: TextStyle(fontSize: 12, color: c.textGray),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
