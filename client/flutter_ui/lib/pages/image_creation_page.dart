import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../theme/study_theme.dart';
import '../widgets/image_generation_loading.dart';
import '../providers/image_creation_providers.dart';
import '../providers/frontend_feature_providers.dart';
import '../providers/study_providers.dart';
import 'image_create_studio.dart';

void imageNotice(BuildContext context, Object message) =>
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text('$message')));

String reusableImageRecipe(WidgetRef ref, ImageCreationTask task) {
  final versions =
      (ref.read(imageTasksProvider).value ?? [])
          .where(
            (t) =>
                t.rootId == task.rootId &&
                t.revision <= task.revision &&
                t.completed,
          )
          .toList()
        ..sort((a, b) => a.revision.compareTo(b.revision));
  final source = versions.isEmpty
      ? task.prompt
      : versions.map((t) => t.prompt).join('\n');
  return '主题：{主题}。\n沿用以下描述的视觉风格、配色和构图约束，将其中原有主体、短文字和具体内容全部替换为本次主题。\n参考描述：\n$source';
}

class ImageCreationPage extends ConsumerStatefulWidget {
  const ImageCreationPage({super.key});
  @override
  ConsumerState<ImageCreationPage> createState() => _ImageCreationPageState();
}

class _ImageCreationPageState extends ConsumerState<ImageCreationPage> {
  String query = '';
  @override
  Widget build(BuildContext context) {
    final c = StudyPalette.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('图片'),
        actions: [
          TextButton.icon(
            onPressed: () {
              openImageStudio(context, ref);
            },
            icon: const Icon(Icons.add),
            label: const Text('创建图片'),
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              decoration: const InputDecoration(
                hintText: '搜索图片',
                prefixIcon: Icon(Icons.search),
              ),
              onChanged: (s) => setState(() => query = s.trim()),
            ),
          ),
          Expanded(
            child: ref
                .watch(imageTasksProvider)
                .when(
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (e, _) => Center(
                    child: TextButton(
                      onPressed: () => ref.invalidate(imageTasksProvider),
                      child: const Text('图片加载失败，点击重试'),
                    ),
                  ),
                  data: (tasks) {
                    final items = tasks
                        .where((t) => t.prompt.contains(query))
                        .toList();
                    if (items.isEmpty)
                      return Center(
                        child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Text(
                            query.isEmpty
                                ? '描述你的想法，创建第一张图片。\n插画、海报、照片风格，都可以从一句话开始。'
                                : '没有找到相关图片',
                            textAlign: TextAlign.center,
                            style: TextStyle(color: c.textGray, height: 1.8),
                          ),
                        ),
                      );
                    final rows = <List<ImageCreationTask>>[];
                    for (final item in items) {
                      final day = DateUtils.dateOnly(
                        DateTime.fromMillisecondsSinceEpoch(item.createdAt),
                      );
                      if (rows.isEmpty ||
                          rows.last.length == 2 ||
                          DateUtils.dateOnly(
                                DateTime.fromMillisecondsSinceEpoch(
                                  rows.last.first.createdAt,
                                ),
                              ) !=
                              day) {
                        rows.add([]);
                      }
                      rows.last.add(item);
                    }
                    return RefreshIndicator(
                      onRefresh: () async {
                        ref.invalidate(imageTasksProvider);
                        await ref.read(imageTasksProvider.future);
                      },
                      child: ListView.builder(
                        padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                        itemCount: rows.length,
                        itemBuilder: (context, row) {
                          final rowItems = rows[row];
                          final date = DateTime.fromMillisecondsSinceEpoch(
                            rowItems.first.createdAt,
                          );
                          final previous = row > 0
                              ? DateTime.fromMillisecondsSinceEpoch(
                                  rows[row - 1].first.createdAt,
                                )
                              : null;
                          return Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              if (previous == null ||
                                  previous.day != date.day ||
                                  previous.month != date.month ||
                                  previous.year != date.year)
                                Padding(
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 12,
                                  ),
                                  child: Text(
                                    DateUtils.isSameDay(date, DateTime.now())
                                        ? '今天'
                                        : '${date.month}月${date.day}日',
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                ),
                              Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Expanded(child: _tile(rowItems.first)),
                                  const SizedBox(width: 12),
                                  Expanded(
                                    child: rowItems.length > 1
                                        ? _tile(rowItems[1])
                                        : const SizedBox(),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 16),
                            ],
                          );
                        },
                      ),
                    );
                  },
                ),
          ),
        ],
      ),
    );
  }

  Widget _tile(ImageCreationTask task) => InkWell(
    onTap: () => showImageCreationViewer(context, ref, task),
    borderRadius: BorderRadius.circular(16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        AspectRatio(
          aspectRatio: 1,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: task.completed
                ? Image.file(
                    File(task.path!),
                    fit: BoxFit.cover,
                    cacheWidth: 480,
                    errorBuilder: (_, _, _) =>
                        const Center(child: Text('图片文件不可用')),
                  )
                : task.running
                ? const ImageGenerationLoading(compact: true)
                : Container(
                    color: StudyPalette.of(context).bubbleGray,
                    child: Center(
                      child: const Icon(Icons.broken_image_outlined),
                    ),
                  ),
          ),
        ),
        const SizedBox(height: 8),
        Text(task.prompt, maxLines: 2, overflow: TextOverflow.ellipsis),
        Text(
          task.running
              ? '正在生成'
              : task.completed
              ? 'AI 生成 · v${task.revision}'
              : '未完成 · 点击查看',
          style: TextStyle(
            fontSize: 12,
            color: StudyPalette.of(context).textGray,
          ),
        ),
      ],
    ),
  );
}

class ImageCreationCard extends ConsumerWidget {
  final ImageCreationTask task;
  const ImageCreationCard({super.key, required this.task});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = StudyPalette.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            task.completed
                ? '已生成这张图片。'
                : task.running
                ? '正在生成图片'
                : task.error ?? '图片生成未完成',
          ),
          const SizedBox(height: 12),
          ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: task.completed
                ? InkWell(
                    onTap: () => showImageCreationViewer(context, ref, task),
                    child: AspectRatio(
                      aspectRatio: switch (task.size) {
                        '1536x1024' => 1.5,
                        '1024x1536' => 2 / 3,
                        _ => 1,
                      },
                      child: Image.file(
                        File(task.path!),
                        fit: BoxFit.contain,
                        semanticLabel: '生成图片预览 v${task.revision}',
                        frameBuilder: (context, child, frame, synchronous) =>
                            synchronous || frame != null
                            ? revealGeneratedImage(context, child)
                            : const ImageGenerationLoading(),
                        errorBuilder: (_, _, _) => const Padding(
                          padding: EdgeInsets.all(24),
                          child: Text('图片文件不可用'),
                        ),
                      ),
                    ),
                  )
                : AspectRatio(
                    aspectRatio: task.size == '1536x1024'
                        ? 1.5
                        : task.size == '1024x1536'
                        ? 2 / 3
                        : 1,
                    child: task.running
                        ? const ImageGenerationLoading()
                        : Container(
                            color: c.bubbleGray,
                            child: Center(
                              child: const Icon(Icons.image_outlined, size: 48),
                            ),
                          ),
                  ),
          ),
          const SizedBox(height: 8),
          Text(
            '${task.model} · ${task.running
                ? '正在生成'
                : task.completed
                ? 'AI 生成'
                : '未完成'} · v${task.revision}',
            style: TextStyle(fontSize: 12, color: c.textGray),
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            children: [
              if (task.completed) ...[
                OutlinedButton.icon(
                  onPressed: () => showImageCreationViewer(context, ref, task),
                  icon: const Icon(Icons.edit_outlined, size: 18),
                  label: const Text('修改'),
                ),
                OutlinedButton.icon(
                  onPressed: () => _save(context, ref),
                  icon: const Icon(Icons.download_outlined, size: 18),
                  label: const Text('保存'),
                ),
                TextButton.icon(
                  onPressed: () => editImageTemplate(context, ref, {
                    'prompt': reusableImageRecipe(ref, task),
                    'size': task.size,
                    'quality': task.quality,
                  }),
                  icon: const Icon(Icons.bookmark_add_outlined, size: 18),
                  label: const Text('保存为我的模板'),
                ),
                IconButton(
                  tooltip: '分享',
                  onPressed: () async {
                    try {
                      checkCommand(
                        await bridgeApi.shareArtifact(
                          ref.read(activeProfileIdProvider),
                          task.artifactId!,
                        ),
                      );
                    } catch (e) {
                      if (context.mounted) imageNotice(context, e);
                    }
                  },
                  icon: const Icon(Icons.share_outlined),
                ),
              ] else if (task.running)
                TextButton(
                  onPressed: () async {
                    try {
                      await FrontendFeatures.fromWidget(ref, 'imageCancel', {
                        'id': task.id,
                      });
                      ref.invalidate(imageTasksProvider);
                    } catch (e) {
                      if (context.mounted) imageNotice(context, e);
                    }
                  },
                  child: const Text('停止生成'),
                )
              else
                TextButton(
                  onPressed: () => showImageCreationViewer(context, ref, task),
                  child: const Text('查看并重试'),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _save(BuildContext context, WidgetRef ref) async {
    try {
      checkCommand(
        await bridgeApi.saveArtifact(
          ref.read(activeProfileIdProvider),
          task.artifactId!,
        ),
      );
    } catch (e) {
      if (context.mounted) imageNotice(context, e);
    }
  }
}

Future<void> showImageCreationViewer(
  BuildContext context,
  WidgetRef ref,
  ImageCreationTask task,
) async {
  final owner = ref.read(activeProfileIdProvider),
      epoch = ref.read(contextEpochProvider);
  await Navigator.of(context).push(
    MaterialPageRoute(
      builder: (_) =>
          ImageCreationViewer(taskId: task.id, owner: owner, epoch: epoch),
    ),
  );
}

class ImageCreationViewer extends ConsumerStatefulWidget {
  final String taskId, owner;
  final int epoch;
  const ImageCreationViewer({
    super.key,
    required this.taskId,
    required this.owner,
    required this.epoch,
  });
  @override
  ConsumerState<ImageCreationViewer> createState() =>
      _ImageCreationViewerState();
}

class _ImageCreationViewerState extends ConsumerState<ImageCreationViewer> {
  late String selected = widget.taskId;
  @override
  Widget build(BuildContext context) {
    if (ref.watch(activeProfileIdProvider) != widget.owner ||
        ref.watch(contextEpochProvider) != widget.epoch) {
      return Scaffold(
        appBar: AppBar(),
        body: const Center(child: Text('档案已改变，请重新打开图片')),
      );
    }
    final tasks = ref.watch(imageTasksProvider);
    return tasks.when(
      loading: () =>
          const Scaffold(body: Center(child: CircularProgressIndicator())),
      error: (_, _) => Scaffold(
        appBar: AppBar(),
        body: Center(
          child: TextButton(
            onPressed: () => ref.invalidate(imageTasksProvider),
            child: const Text('加载失败，重试'),
          ),
        ),
      ),
      data: (items) {
        final task = items.where((t) => t.id == selected).firstOrNull;
        if (task == null)
          return Scaffold(
            appBar: AppBar(),
            body: const Center(child: Text('图片已不可用')),
          );
        final versions = items.where((t) => t.rootId == task.rootId).toList()
          ..sort((a, b) => a.revision.compareTo(b.revision));
        final c = StudyPalette.of(context);
        return Scaffold(
          appBar: AppBar(
            title: Text('当前版本 v${task.revision}'),
            actions: [
              if (task.completed)
                IconButton(
                  tooltip: '保存为我的模板',
                  icon: const Icon(Icons.bookmark_add_outlined),
                  onPressed: () => editImageTemplate(context, ref, {
                    'prompt': reusableImageRecipe(ref, task),
                    'size': task.size,
                    'quality': task.quality,
                  }),
                ),
              IconButton(
                tooltip: '回到来源聊天',
                icon: const Icon(Icons.chat_bubble_outline),
                onPressed: () {
                  ref.read(activeConversationIdProvider.notifier).state =
                      task.conversationId;
                  Navigator.pop(context);
                  context.go('/chat');
                },
              ),
            ],
          ),
          body: Column(
            children: [
              Expanded(
                child: task.completed
                    ? InteractiveViewer(
                        minScale: 0.5,
                        maxScale: 5,
                        child: Center(
                          child: Image.file(
                            File(task.path!),
                            fit: BoxFit.contain,
                            errorBuilder: (_, _, _) => const Text('图片文件不可用'),
                          ),
                        ),
                      )
                    : SingleChildScrollView(
                        padding: const EdgeInsets.all(16),
                        child: ImageCreationCard(task: task),
                      ),
              ),
              if (versions.length > 1 &&
                  MediaQuery.viewInsetsOf(context).bottom == 0)
                SizedBox(
                  height: 100,
                  child: ListView(
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.all(8),
                    children: versions
                        .map(
                          (v) => InkWell(
                            onTap: () => setState(() => selected = v.id),
                            child: Container(
                              width: 80,
                              margin: const EdgeInsets.symmetric(horizontal: 4),
                              padding: const EdgeInsets.all(3),
                              decoration: BoxDecoration(
                                color: selected == v.id
                                    ? c.selectionFill
                                    : c.card,
                                borderRadius: BorderRadius.circular(10),
                                border: Border.all(
                                  color: selected == v.id
                                      ? c.accent
                                      : c.borderGray,
                                  width: selected == v.id ? 2 : 1,
                                ),
                              ),
                              child: Column(
                                children: [
                                  Expanded(
                                    child: v.completed
                                        ? Image.file(
                                            File(v.path!),
                                            fit: BoxFit.cover,
                                            cacheWidth: 160,
                                          )
                                        : const Icon(Icons.image_outlined),
                                  ),
                                  Text(
                                    'v${v.revision}',
                                    style: const TextStyle(fontSize: 12),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        )
                        .toList(),
                  ),
                ),
              if (task.completed &&
                  MediaQuery.viewInsetsOf(context).bottom == 0)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: Row(
                    children: [
                      Text(
                        'AI 生成',
                        style: TextStyle(color: c.textGray, fontSize: 12),
                      ),
                      const Spacer(),
                      TextButton.icon(
                        onPressed: () async {
                          try {
                            checkCommand(
                              await bridgeApi.saveArtifact(
                                widget.owner,
                                task.artifactId!,
                              ),
                            );
                          } catch (e) {
                            if (context.mounted) imageNotice(context, e);
                          }
                        },
                        icon: const Icon(Icons.download_outlined),
                        label: const Text('保存'),
                      ),
                    ],
                  ),
                ),
              ImageCreationInput(
                key: ValueKey((
                  task.id,
                  task.state,
                  widget.owner,
                  widget.epoch,
                )),
                parent: task.completed
                    ? task
                    : items
                          .where((t) => t.id == task.parentId && t.completed)
                          .firstOrNull,
                initialPrompt: task.completed ? '' : task.prompt,
                conversationId: task.conversationId,
                initialReferences: task.completed ? const [] : task.references,
                initialSize: task.size,
                initialQuality: task.quality,
                onSubmitted: (next) => setState(() => selected = next.id),
              ),
            ],
          ),
        );
      },
    );
  }
}

class ImageCreationInput extends ConsumerStatefulWidget {
  final ImageCreationTask? parent;
  final String? conversationId;
  final VoidCallback? onExit;
  final ValueChanged<ImageCreationTask>? onSubmitted;
  final ValueChanged<String>? onDraftChanged;
  final String initialPrompt, initialSize, initialQuality;
  final List<String> initialReferences;
  const ImageCreationInput({
    super.key,
    this.parent,
    this.conversationId,
    this.onExit,
    this.onSubmitted,
    this.onDraftChanged,
    this.initialPrompt = '',
    this.initialSize = '1024x1024',
    this.initialQuality = 'low',
    this.initialReferences = const [],
  });
  @override
  ConsumerState<ImageCreationInput> createState() => _ImageCreationInputState();
}

class _ImageCreationInputState extends ConsumerState<ImageCreationInput> {
  late final controller = TextEditingController(text: widget.initialPrompt);
  late String size = widget.initialSize, quality = widget.initialQuality;
  late List<String> references = List.of(widget.initialReferences);
  final Map<String, String> referencePaths = {};
  bool submitting = false;
  String? requestId;
  late String lastDraft = widget.initialPrompt;
  @override
  void initState() {
    super.initState();
    controller.addListener(_draftChanged);
  }

  @override
  void didUpdateWidget(covariant ImageCreationInput oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (submitting) return;
    if (widget.initialPrompt != oldWidget.initialPrompt &&
        controller.text != widget.initialPrompt) {
      controller.text = widget.initialPrompt;
    }
    if (widget.initialSize != oldWidget.initialSize) size = widget.initialSize;
    if (widget.initialQuality != oldWidget.initialQuality)
      quality = widget.initialQuality;
    if (widget.initialPrompt != oldWidget.initialPrompt ||
        widget.initialSize != oldWidget.initialSize ||
        widget.initialQuality != oldWidget.initialQuality)
      requestId = null;
  }

  void _draftChanged() {
    if (controller.text == lastDraft) return;
    lastDraft = controller.text;
    widget.onDraftChanged?.call(lastDraft);
    setState(() => requestId = null);
  }

  @override
  void dispose() {
    controller.removeListener(_draftChanged);
    controller.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (controller.text.trim().isEmpty || submitting) return;
    final owner = ref.read(activeProfileIdProvider),
        epoch = ref.read(contextEpochProvider);
    setState(() => submitting = true);
    requestId ??= '${DateTime.now().microsecondsSinceEpoch}';
    try {
      final m = await FrontendFeatures.fromWidget<Map<dynamic, dynamic>>(
        ref,
        'imageStart',
        {
          'requestId': requestId,
          'conversationId': widget.conversationId,
          'prompt': controller.text.trim(),
          'references': references,
          'parentId': widget.parent?.id,
          'size': widget.parent?.size ?? size,
          'quality': quality,
        },
      );
      if (!mounted ||
          ref.read(activeProfileIdProvider) != owner ||
          ref.read(contextEpochProvider) != epoch)
        return;
      final next = ImageCreationTask(m!);
      ref.invalidate(imageTasksProvider);
      ref.invalidate(conversationsProvider);
      ref.invalidate(activeConversationDetailProvider);
      controller.clear();
      requestId = null;
      widget.onSubmitted?.call(next);
    } catch (e) {
      if (mounted &&
          ref.read(activeProfileIdProvider) == owner &&
          ref.read(contextEpochProvider) == epoch)
        imageNotice(context, e);
    } finally {
      if (mounted) setState(() => submitting = false);
    }
  }

  Future<void> _pick() async {
    final owner = ref.read(activeProfileIdProvider),
        epoch = ref.read(contextEpochProvider);
    try {
      final items =
          await FrontendFeatures.fromWidget<List<dynamic>>(ref, 'imagePick') ??
          [];
      if (!mounted ||
          ref.read(activeProfileIdProvider) != owner ||
          ref.read(contextEpochProvider) != epoch)
        return;
      final ids = items.map((e) => e['id'] as String).toList();
      if ({...references, ...ids}.length + (widget.parent == null ? 0 : 1) >
          4) {
        imageNotice(context, '最多使用四张参考图（含当前图片）');
        return;
      }
      setState(() {
        references = {...references, ...ids}.toList();
        for (final item in items) {
          referencePaths[item['id']] = item['path'];
        }
        requestId = null;
      });
    } catch (e) {
      if (mounted) imageNotice(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = StudyPalette.of(context);
    final tasks = ref.watch(imageTasksProvider).value ?? [];
    final running = tasks.where((t) => t.running).firstOrNull;
    final config = ref.watch(imageConfigProvider).value;
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (config?['ready'] != true)
              TextButton(onPressed: _chooseModel, child: const Text('选择图片创作模型'))
            else
              InkWell(
                onTap: _chooseModel,
                child: Text(
                  '${config!['model']} · ${config['protocol'] == 'images' ? '图像生成' : '低推理等级'}',
                  style: TextStyle(color: c.textGray, fontSize: 12),
                ),
              ),
            const SizedBox(height: 6),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: c.bubbleGray,
                borderRadius: BorderRadius.circular(26),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (widget.parent != null || references.isNotEmpty)
                    Wrap(
                      spacing: 6,
                      children: [
                        if (widget.parent != null)
                          Chip(
                            avatar: const Icon(Icons.image_outlined, size: 18),
                            label: Text('基于 v${widget.parent!.revision}'),
                          ),
                        ...references.map(
                          (id) => InputChip(
                            avatar: referencePaths[id] != null
                                ? Image.file(
                                    File(referencePaths[id]!),
                                    width: 24,
                                    height: 24,
                                    fit: BoxFit.cover,
                                  )
                                : const Icon(Icons.image_outlined, size: 18),
                            label: const Text('参考图'),
                            onDeleted: submitting
                                ? null
                                : () => setState(() {
                                    references.remove(id);
                                    requestId = null;
                                  }),
                          ),
                        ),
                      ],
                    ),
                  TextField(
                    controller: controller,
                    readOnly: submitting,
                    minLines: 1,
                    maxLines: 4,
                    decoration: InputDecoration(
                      hintText: widget.parent == null
                          ? '描述你想创作的图片…'
                          : '描述你想修改的地方…',
                      filled: false,
                      border: InputBorder.none,
                      enabledBorder: InputBorder.none,
                      focusedBorder: InputBorder.none,
                    ),
                  ),
                  Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: 4,
                    children: [
                      IconButton(
                        tooltip: '添加参考图',
                        onPressed: submitting || running != null ? null : _pick,
                        icon: const Icon(Icons.add),
                      ),
                      InputChip(
                        label: const Text('图片创作'),
                        onDeleted: widget.onExit,
                        selected: true,
                        selectedColor: c.selectionFill,
                      ),
                      TextButton(
                        onPressed: submitting || running != null
                            ? null
                            : _options,
                        child: Text(
                          '${_ratio(widget.parent?.size ?? size)} · ${_quality(quality)}',
                        ),
                      ),
                      IconButton(
                        tooltip: running != null ? '停止生成' : '生成图片',
                        style: IconButton.styleFrom(
                          backgroundColor: c.accent,
                          foregroundColor: c.onAccent,
                        ),
                        onPressed: submitting
                            ? null
                            : running != null
                            ? () async {
                                try {
                                  await FrontendFeatures.fromWidget(
                                    ref,
                                    'imageCancel',
                                    {'id': running.id},
                                  );
                                  ref.invalidate(imageTasksProvider);
                                } catch (e) {
                                  if (context.mounted) imageNotice(context, e);
                                }
                              }
                            : controller.text.trim().isEmpty ||
                                  config?['ready'] != true
                            ? null
                            : _submit,
                        icon: Icon(
                          running != null ? Icons.stop : Icons.arrow_upward,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            if (running != null)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text(
                  '正在生成，可离开页面后查看',
                  style: TextStyle(fontSize: 12, color: c.textGray),
                ),
              ),
          ],
        ),
      ),
    );
  }

  String _ratio(String value) => switch (value) {
    '1536x1024' => '3:2',
    '1024x1536' => '2:3',
    _ => '1:1',
  };
  String _quality(String value) => switch (value) {
    'medium' => '标准',
    'high' => '精细',
    _ => '经济',
  };
  Future<void> _options() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => SafeArea(
        child: StatefulBuilder(
          builder: (context, update) => Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  '图片设置',
                  style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 16),
                const Text('比例'),
                Wrap(
                  spacing: 8,
                  children: ['1024x1024', '1536x1024', '1024x1536']
                      .map(
                        (s) => ChoiceChip(
                          label: Text(_ratio(s)),
                          selected: (widget.parent?.size ?? size) == s,
                          onSelected: widget.parent != null
                              ? null
                              : (_) {
                                  setState(() {
                                    size = s;
                                    requestId = null;
                                  });
                                  update(() {});
                                },
                        ),
                      )
                      .toList(),
                ),
                if (widget.parent != null) const Text('修改时保留原图比例。'),
                const SizedBox(height: 16),
                const Text('质量'),
                Wrap(
                  spacing: 8,
                  children: ['low', 'medium', 'high']
                      .map(
                        (s) => ChoiceChip(
                          label: Text(_quality(s)),
                          selected: quality == s,
                          onSelected: (_) {
                            setState(() {
                              quality = s;
                              requestId = null;
                            });
                            update(() {});
                          },
                        ),
                      )
                      .toList(),
                ),
                const SizedBox(height: 12),
                const Text('每次生成一张。经济质量用于快速草稿；实际费用由提供商计费。'),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _chooseModel() async {
    final owner = ref.read(activeProfileIdProvider),
        epoch = ref.read(contextEpochProvider);
    try {
      final providers = await bridgeApi.getProviders();
      if (!mounted ||
          ref.read(activeProfileIdProvider) != owner ||
          ref.read(contextEpochProvider) != epoch)
        return;
      final id = await showModalBottomSheet<String>(
        context: context,
        isScrollControlled: true,
        builder: (context) => SafeArea(
          child: SizedBox(
            height: 420,
            child: ListView(
              children: [
                const ListTile(
                  title: Text('图片创作模型'),
                  subtitle: Text('选择图像模型，或支持生图工具的 Responses 模型。'),
                ),
                ...providers
                    .where(
                      (p) =>
                          p.enabled && p.type.toLowerCase().contains('openai'),
                    )
                    .expand(
                      (p) => p.models
                          .where((m) => m.enabled)
                          .map(
                            (m) => ListTile(
                              title: Text(m.name),
                              subtitle: Text(p.name),
                              onTap: () => Navigator.pop(context, m.id),
                            ),
                          ),
                    ),
                ListTile(
                  title: const Text('管理提供商与凭据'),
                  trailing: const Icon(Icons.open_in_new),
                  onTap: () {
                    Navigator.pop(context);
                    bridgeApi.openNativePage('providers');
                  },
                ),
              ],
            ),
          ),
        ),
      );
      if (id == null ||
          !mounted ||
          ref.read(activeProfileIdProvider) != owner ||
          ref.read(contextEpochProvider) != epoch)
        return;
      await FrontendFeatures.fromWidget(ref, 'selectImageModel', {
        'modelId': id,
      });
      ref.invalidate(imageConfigProvider);
    } catch (e) {
      if (mounted) imageNotice(context, e);
    }
  }
}
