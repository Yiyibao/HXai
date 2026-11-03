import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../providers/image_creation_providers.dart';
import '../providers/frontend_feature_providers.dart';
import '../providers/study_providers.dart';
import '../theme/study_theme.dart';
import 'image_creation_page.dart';

// Keep form controllers alive until the dialog's exit animation releases fields.
Future<T?> _formDialog<T>({
  required BuildContext context,
  required WidgetBuilder builder,
}) async {
  final route = DialogRoute<T>(context: context, builder: builder);
  final result = await Navigator.of(context, rootNavigator: true).push(route);
  await route.completed;
  return result;
}

const imageStyles = [
  ('photography', '写实摄影', '自然光、真实材质、杂志摄影构图', '象牙白陶瓷花瓶与一枝杏花，洞石桌面，晨光与长影，温暖中性色'),
  ('cinema', '电影感', '电影构图、氛围光、细腻胶片颗粒', '蓝调时刻雨后的街道，一位骑车的人，街角咖啡馆透出琥珀色光，远处薄雾'),
  ('flat', '扁平插画', '简洁几何形、清晰留白、珊瑚黄与深蓝', '窗边读书的人，大叶植物与熟睡的猫'),
  ('watercolor', '水彩', '透明叠色、湿画边缘、冷压纸纹理', '木质窗台上的白猫，绿植，午后阳光与浅蓝天空'),
  ('3d', '3D 微缩', '哑光材质、柔和棚光、精致微缩场景', '漂浮的植物小岛，奶油色小屋、陶土屋顶、树木与阶梯'),
  ('paper', '纸艺', '多层手工剪纸、纸纤维、层间真实投影', '日出时的山间湖泊，森林绿与赭黄层次，水波与一叶小舟'),
];

Future<void> openImageStudio(
  BuildContext context,
  WidgetRef ref, {
  String prompt = '',
  String? conversationId,
}) async {
  final owner = ref.read(activeProfileIdProvider),
      epoch = ref.read(contextEpochProvider);
  await Navigator.push(
    context,
    MaterialPageRoute(
      builder: (_) => ImageCreateStudio(
        owner: owner,
        epoch: epoch,
        initialPrompt: prompt,
        conversationId: conversationId,
      ),
    ),
  );
}

class ImageCreateStudio extends ConsumerStatefulWidget {
  final String owner, initialPrompt;
  final int epoch;
  final String? conversationId;
  const ImageCreateStudio({
    super.key,
    required this.owner,
    required this.epoch,
    this.initialPrompt = '',
    this.conversationId,
  });
  @override
  ConsumerState<ImageCreateStudio> createState() => _StudioState();
}

class _StudioState extends ConsumerState<ImageCreateStudio> {
  late String prompt = widget.initialPrompt;
  String size = '1024x1024', quality = 'low';
  int revision = 0;
  String? style;
  void apply(String text, {String? ratio, String? fidelity}) => setState(() {
    prompt = text;
    size = ratio ?? size;
    quality = fidelity ?? quality;
    revision++;
  });
  @override
  Widget build(BuildContext context) {
    final c = StudyPalette.of(context);
    if (ref.watch(activeProfileIdProvider) != widget.owner ||
        ref.watch(contextEpochProvider) != widget.epoch) {
      return Scaffold(
        appBar: AppBar(),
        body: const Center(child: Text('档案已改变，请重新打开创建页')),
      );
    }
    return Scaffold(
      appBar: AppBar(title: const Text('创建图片')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.only(bottom: 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 12, 20, 4),
              child: Text(
                '从一个想法开始',
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.w600),
              ),
            ),
            ImageCreationInput(
              initialPrompt: prompt,
              initialSize: size,
              initialQuality: quality,
              conversationId: widget.conversationId,
              onDraftChanged: (value) => prompt = value,
              onSubmitted: (task) {
                ref.read(activeConversationIdProvider.notifier).state =
                    task.conversationId;
                Navigator.pop(context);
                context.go('/chat');
              },
            ),
            _heading('选择视觉样式', '选择只会填写草稿，你可以继续修改。'),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final width = (constraints.maxWidth - 12) / 2;
                  return Wrap(
                    spacing: 12,
                    runSpacing: 16,
                    children: imageStyles
                        .map(
                          (s) => SizedBox(
                            width: width,
                            child: InkWell(
                              borderRadius: BorderRadius.circular(16),
                              onTap: () {
                                style = s.$1;
                                final subject = prompt.trim().isEmpty
                                    ? '主题：${s.$4}。\n构图精致，画面完整，无水印。'
                                    : prompt.replaceAll(
                                        RegExp(r'\n视觉风格：[^\n]*'),
                                        '',
                                      );
                                apply('$subject\n视觉风格：${s.$3}。');
                              },
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Container(
                                    decoration: BoxDecoration(
                                      borderRadius: BorderRadius.circular(16),
                                      border: Border.all(
                                        color: style == s.$1
                                            ? c.accent
                                            : c.borderGray,
                                        width: style == s.$1 ? 2 : 1,
                                      ),
                                    ),
                                    child: ClipRRect(
                                      borderRadius: BorderRadius.circular(14),
                                      child: AspectRatio(
                                        aspectRatio: 1,
                                        child: Image.asset(
                                          'assets/image_styles/${s.$1}.png',
                                          fit: BoxFit.cover,
                                          semanticLabel: '${s.$2}原创示例',
                                        ),
                                      ),
                                    ),
                                  ),
                                  const SizedBox(height: 8),
                                  Text(
                                    s.$2,
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                  Text(
                                    s.$3,
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: c.textGray,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        )
                        .toList(),
                  );
                },
              ),
            ),
            _heading('常用用途模板', '先填写内容与布局，再预览可编辑提示词。'),
            ...['海报', '头像', '文章配图 / 封面', '产品展示'].map(
              (name) => ListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 20),
                leading: Icon(switch (name) {
                  '海报' => Icons.dashboard_outlined,
                  '头像' => Icons.account_circle_outlined,
                  '产品展示' => Icons.inventory_2_outlined,
                  _ => Icons.article_outlined,
                }),
                title: Text(name),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _purpose(name),
              ),
            ),
            _heading('我的模板', '保存你的风格与构图约束，替换主题后重复使用。'),
            ref
                .watch(imageTemplatesProvider)
                .when(
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (_, _) => TextButton(
                    onPressed: () => ref.invalidate(imageTemplatesProvider),
                    child: const Text('模板加载失败，重试'),
                  ),
                  data: (items) => items.isEmpty
                      ? Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 20),
                          child: Text(
                            '生成满意的作品后，选择“保存为我的模板”。',
                            style: TextStyle(color: c.textGray),
                          ),
                        )
                      : Column(
                          children: items
                              .map(
                                (item) => ListTile(
                                  title: Text(item['name']),
                                  subtitle: Text(
                                    item['prompt'],
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                  onTap: () => _reuse(item),
                                  trailing: PopupMenuButton<String>(
                                    onSelected: (action) async {
                                      if (action == 'edit') {
                                        await editImageTemplate(
                                          context,
                                          ref,
                                          item,
                                        );
                                      } else {
                                        final confirmed =
                                            await _formDialog<bool>(
                                              context: context,
                                              builder: (ctx) => AlertDialog(
                                                title: const Text('删除这个模板？'),
                                                content: Text(item['name']),
                                                actions: [
                                                  TextButton(
                                                    onPressed: () =>
                                                        Navigator.pop(
                                                          ctx,
                                                          false,
                                                        ),
                                                    child: const Text('取消'),
                                                  ),
                                                  TextButton(
                                                    onPressed: () =>
                                                        Navigator.pop(
                                                          ctx,
                                                          true,
                                                        ),
                                                    child: const Text('删除'),
                                                  ),
                                                ],
                                              ),
                                            );
                                        if (confirmed != true || !mounted)
                                          return;
                                        try {
                                          await FrontendFeatures.fromWidget(
                                            ref,
                                            'imageTemplateDelete',
                                            {'id': item['id']},
                                          );
                                          ref.invalidate(
                                            imageTemplatesProvider,
                                          );
                                        } catch (e) {
                                          if (mounted) imageNotice(context, e);
                                        }
                                      }
                                    },
                                    itemBuilder: (_) => const [
                                      PopupMenuItem(
                                        value: 'edit',
                                        child: Text('编辑'),
                                      ),
                                      PopupMenuItem(
                                        value: 'delete',
                                        child: Text('删除'),
                                      ),
                                    ],
                                  ),
                                ),
                              )
                              .toList(),
                        ),
                ),
          ],
        ),
      ),
    );
  }

  Widget _heading(String title, String subtitle) => Padding(
    padding: const EdgeInsets.fromLTRB(20, 28, 20, 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 5),
        Text(
          subtitle,
          style: TextStyle(
            color: StudyPalette.of(context).textGray,
            fontSize: 13,
          ),
        ),
      ],
    ),
  );
  Future<void> _reuse(Map item) async {
    final content = TextEditingController();
    final result = await _formDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('套用 ${item['name']}'),
        content: TextField(
          controller: content,
          maxLines: 3,
          decoration: const InputDecoration(
            labelText: '这次的新主题',
            hintText: '例如：秋日书店',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () {
              if (content.text.trim().isNotEmpty)
                Navigator.pop(ctx, content.text.trim());
            },
            child: const Text('填入草稿'),
          ),
        ],
      ),
    );
    content.dispose();
    if (result != null && mounted)
      apply(
        (item['prompt'] as String).replaceAll('{主题}', result),
        ratio: item['size'],
        fidelity: item['quality'],
      );
  }

  Future<void> _purpose(String name) async {
    final topic = TextEditingController(), text = TextEditingController();
    String ratio = name == '海报'
        ? '1024x1536'
        : name == '头像'
        ? '1024x1024'
        : '1536x1024';
    final result = await _formDialog<String>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, update) => AlertDialog(
          title: Text(name),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: topic,
                  decoration: InputDecoration(
                    labelText: name == '产品展示' ? '产品与特点（必填）' : '主题（必填）',
                  ),
                ),
                TextField(
                  controller: text,
                  decoration: const InputDecoration(labelText: '画面短文字（可选）'),
                ),
                DropdownButton<String>(
                  value: ratio,
                  isExpanded: true,
                  items: const [
                    DropdownMenuItem(
                      value: '1024x1024',
                      child: Text('1:1 正方形'),
                    ),
                    DropdownMenuItem(value: '1536x1024', child: Text('3:2 横版')),
                    DropdownMenuItem(value: '1024x1536', child: Text('2:3 竖版')),
                  ],
                  onChanged: (v) => update(() => ratio = v!),
                ),
                const Text('参考图可在草稿输入框中添加。'),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('取消'),
            ),
            TextButton(
              onPressed: () {
                if (topic.text.trim().isNotEmpty)
                  Navigator.pop(ctx, topic.text.trim());
              },
              child: const Text('预览提示词'),
            ),
          ],
        ),
      ),
    );
    if (result != null && mounted) {
      final layout = switch (name) {
        '海报' => '主体突出，标题层级清晰，边缘留安全区，文字与主体分区',
        '头像' => '主体居中，面部清晰，背景简洁，适合圆形裁切',
        '产品展示' => '产品为视觉中心，准确保留参考图产品外观，干净棚拍背景，突出材质',
        _ => '用一个清晰视觉隐喻表达主题，横向构图，预留标题空间',
      };
      apply(
        '制作$name。\n主题：$result。\n布局：$layout。\n${text.text.trim().isEmpty ? '不添加文字。' : '仅呈现以下文字：${text.text.trim()}。'}\n${style == null ? '' : '视觉风格：${imageStyles.firstWhere((s) => s.$1 == style).$3}。'}\n无水印，画面精致。',
        ratio: ratio,
      );
    }
    topic.dispose();
    text.dispose();
  }
}

Future<void> editImageTemplate(
  BuildContext context,
  WidgetRef ref,
  Map initial,
) async {
  final name = TextEditingController(text: initial['name'] ?? '我的图片风格');
  final recipe = TextEditingController(text: initial['prompt']);
  String size = initial['size'] ?? '1024x1024',
      quality = initial['quality'] ?? 'low';
  bool saving = false;
  final owner = ref.read(activeProfileIdProvider),
      epoch = ref.read(contextEpochProvider);
  await _formDialog<void>(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, update) => AlertDialog(
        title: const Text('保存为我的模板'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: name,
                maxLength: 80,
                decoration: const InputDecoration(labelText: '模板名称'),
              ),
              const Text('把可替换的内容写成 {主题}，保留风格、构图与限制。参考图不会保存到模板。'),
              TextField(
                controller: recipe,
                minLines: 4,
                maxLines: 8,
                maxLength: 8000,
                decoration: const InputDecoration(
                  labelText: '可复用提示词（必须含 {主题}）',
                ),
              ),
              DropdownButton<String>(
                value: size,
                isExpanded: true,
                items: const [
                  DropdownMenuItem(value: '1024x1024', child: Text('1:1')),
                  DropdownMenuItem(value: '1536x1024', child: Text('3:2')),
                  DropdownMenuItem(value: '1024x1536', child: Text('2:3')),
                ],
                onChanged: saving ? null : (v) => update(() => size = v!),
              ),
              DropdownButton<String>(
                value: quality,
                isExpanded: true,
                items: const [
                  DropdownMenuItem(value: 'low', child: Text('经济')),
                  DropdownMenuItem(value: 'medium', child: Text('标准')),
                  DropdownMenuItem(value: 'high', child: Text('精细')),
                ],
                onChanged: saving ? null : (v) => update(() => quality = v!),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: saving ? null : () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: saving
                ? null
                : () async {
                    if (name.text.trim().isEmpty ||
                        !recipe.text.contains('{主题}')) {
                      imageNotice(ctx, '请填写名称，并在提示词中保留 {主题}');
                      return;
                    }
                    if (ref.read(activeProfileIdProvider) != owner ||
                        ref.read(contextEpochProvider) != epoch) {
                      Navigator.pop(ctx);
                      return;
                    }
                    update(() => saving = true);
                    try {
                      await FrontendFeatures.fromWidget(
                        ref,
                        'imageTemplateSave',
                        {
                          'id': initial['id'],
                          'name': name.text.trim(),
                          'prompt': recipe.text.trim(),
                          'size': size,
                          'quality': quality,
                        },
                      );
                      ref.invalidate(imageTemplatesProvider);
                      if (ctx.mounted) Navigator.pop(ctx);
                    } catch (e) {
                      if (ctx.mounted) {
                        imageNotice(ctx, e);
                        update(() => saving = false);
                      }
                    }
                  },
            child: Text(saving ? '保存中…' : '保存'),
          ),
        ],
      ),
    ),
  );
  name.dispose();
  recipe.dispose();
}
