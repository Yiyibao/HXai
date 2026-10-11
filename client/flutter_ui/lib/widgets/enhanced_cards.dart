import 'package:flutter/material.dart';

import 'dart:convert';

import '../theme/study_theme.dart';
import '../bridge/study_host_api.g.dart';
import 'source_links.dart';
import 'chat_activity.dart';

/// Enhanced file card with neutral design
class EnhancedFileCard extends StatelessWidget {
  final MessagePartDto part;
  final VoidCallback? onOpen;
  final VoidCallback? onSave;
  final VoidCallback? onShare;

  const EnhancedFileCard({
    super.key,
    required this.part,
    this.onOpen,
    this.onSave,
    this.onShare,
  });

  String get fileName => part.fileName ?? '附件';

  String get fileType {
    final ext = fileName.split('.').last.toLowerCase();
    return switch (ext) {
      'pdf' => 'PDF 文档',
      'docx' || 'doc' => 'Word 文档',
      'xlsx' || 'xls' => 'Excel 表格',
      'pptx' || 'ppt' => 'PowerPoint 演示',
      'txt' => '文本文件',
      'md' => 'Markdown',
      'png' || 'jpg' || 'jpeg' => '图片',
      _ => '文件',
    };
  }

  @override
  Widget build(BuildContext context) {
    final artifact = part.artifactId != null;
    final color = StudyPalette.of(context).textBlack;
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 10),
      decoration: BoxDecoration(
        color: StudyPalette.of(context).card,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: StudyPalette.of(context).borderGray),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              children: [
                Container(
                  width: 42,
                  height: 46,
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: 0.08),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(
                    Icons.description_outlined,
                    color: color,
                    size: 26,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        fileName,
                        maxLines: 4,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          color: StudyPalette.of(context).textBlack,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        fileType,
                        style: TextStyle(
                          fontSize: 12,
                          color: StudyPalette.of(context).textGray,
                        ),
                      ),
                    ],
                  ),
                ),
                if (artifact && onShare != null)
                  PopupMenuButton<String>(
                    tooltip: '文件操作',
                    icon: const Icon(Icons.more_horiz, size: 20),
                    onSelected: (_) => onShare!(),
                    itemBuilder: (_) => [
                      const PopupMenuItem(value: 'share', child: Text('分享文件')),
                    ],
                  ),
              ],
            ),
          ),
          if (artifact)
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 0, 14, 14),
              child: Column(
                children: [
                  Divider(
                    height: 1,
                    color: StudyPalette.of(context).borderGray,
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton(
                          onPressed: onOpen,
                          child: const Text('预览'),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: ElevatedButton(
                          onPressed: onSave,
                          child: const Text('保存'),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// Enhanced tool activity card with neutral design
class EnhancedToolCard extends StatefulWidget {
  final MessagePartDto part;
  final String messageId;
  final VoidCallback? onRetry;
  final VoidCallback? onCancel;
  final VoidCallback? onApprove;
  final VoidCallback? onDeny;
  final bool reduceMotion;

  const EnhancedToolCard({
    super.key,
    required this.part,
    required this.messageId,
    this.onRetry,
    this.onCancel,
    this.onApprove,
    this.onDeny,
    this.reduceMotion = false,
  });

  @override
  State<EnhancedToolCard> createState() => _EnhancedToolCardState();
}

class _EnhancedToolCardState extends State<EnhancedToolCard> {
  bool _expanded = false;

  bool get isSearch => widget.part.toolName == 'search_web';
  bool get isWebRead => isSearch || widget.part.toolName == 'scrape_web';

  Map<String, dynamic> get searchOutput {
    try {
      final value = jsonDecode(widget.part.toolOutput ?? '{}');
      return value is Map<String, dynamic> ? value : {};
    } catch (_) {
      return {};
    }
  }

  String? get searchQuery {
    if (!isSearch) return null;
    try {
      final value = jsonDecode(widget.part.toolInput ?? '{}')['query'];
      return value is String ? value : null;
    } catch (_) {
      return null;
    }
  }

  String get toolLabel {
    return switch (widget.part.toolName) {
      'search_web' => '搜索资料',
      'scrape_web' => '核对网页',
      'search_images' => '搜索图片',
      'extract_web_images' => '提取网页图片',
      'create_material_file' => '生成文件',
      'edit_material_file' => '修改文件',
      'convert_material_to_pdf' => '转换 PDF',
      'read_material_file' => '读取文件',
      'use_skill' => '加载技能资料',
      'render_interactive_ui' || 'create_interactive_surface' => '生成交互界面',
      'render_math_plot' => '绘制数学图形',
      'revise_interactive_surface' => '修改交互成果',
      'display_image' => '展示图片',
      _ => '处理步骤',
    };
  }

  String get statusLabel {
    final isPending = widget.part.approvalState?.toLowerCase() == 'pending';
    if (isPending) return '等待确认';
    if (!isWebRead &&
        widget.part.executionState == 'COMPLETED' &&
        const ['FAILED', 'error'].contains(searchOutput['status']))
      return '处理未完成';
    if (isWebRead &&
        widget.part.executionState == 'COMPLETED' &&
        searchOutput['status'] == 'error') {
      return isSearch ? '搜索暂不可用' : '网页暂不可用';
    }
    if (isSearch && widget.part.executionState == 'COMPLETED') {
      final data = searchOutput;
      if (data['status'] == 'error') return '搜索暂不可用';
      if (data['status'] == 'empty') return '未找到来源';
      final items = data['items'];
      if (items is List) return '找到 ${items.length} 个来源';
    }

    return switch (widget.part.executionState) {
      'RUNNING' => isSearch ? '正在搜索' : '正在$toolLabel',
      'COMPLETED' => switch (widget.part.toolName) {
        'render_interactive_ui' ||
        'create_interactive_surface' ||
        'render_math_plot' => '已生成，可展开查看',
        'create_material_file' || 'edit_material_file' => '文件处理完成',
        'read_material_file' => '已读取文件',
        _ => '已完成',
      },
      'FAILED' => '处理失败',
      'SKIPPED_UNKNOWN' || 'INTERRUPTED' => '已中断',
      'NOT_STARTED' => '等待执行',
      _ => '未知状态',
    };
  }

  Color get statusColor {
    final isPending = widget.part.approvalState?.toLowerCase() == 'pending';
    if (isPending) return const Color(0xFFE65100);
    if (const ['FAILED', 'error'].contains(searchOutput['status']))
      return StudyPalette.of(context).errorRed;

    return switch (widget.part.executionState) {
      'RUNNING' => StudyPalette.of(context).chartBlue,
      'COMPLETED' => StudyPalette.of(context).textGray,
      'FAILED' => StudyPalette.of(context).errorRed,
      _ => StudyPalette.of(context).textGray,
    };
  }

  IconData get statusIcon {
    final isPending = widget.part.approvalState?.toLowerCase() == 'pending';
    if (isPending) return Icons.hourglass_empty;
    if (const ['FAILED', 'error'].contains(searchOutput['status'])) {
      return Icons.info_outline;
    }

    return switch (widget.part.executionState) {
      'RUNNING' => Icons.refresh,
      'COMPLETED' => Icons.check_circle_outline,
      'FAILED' => Icons.error_outline,
      _ => Icons.help_outline,
    };
  }

  Widget _buildWebEvidence(BuildContext context) {
    final data = searchOutput;
    final values = data['items'] ?? data['urls'];
    final items = values is List ? values.whereType<Map>().toList() : <Map>[];
    final error = data['error'];
    final warning = data['warning'];
    return Material(
      type: MaterialType.transparency,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (warning is String && warning.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(warning),
            ),
          if (error is Map && error['message'] is String)
            Text(error['message'] as String),
          if (items.isEmpty && error == null)
            Text(
              widget.part.executionState == 'RUNNING'
                  ? '正在查找可核对的来源…'
                  : '暂无可核对的来源',
            ),
          for (final item in items)
            if (item['url'] is String && isWebSource(item['url'] as String))
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(
                  (item['title'] ??
                          (item['metadata'] is Map
                              ? item['metadata']['title']
                              : null) ??
                          Uri.parse(item['url'] as String).host)
                      .toString(),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: Text(
                  [
                    Uri.parse(item['url'] as String).host,
                    if (item['publishedDate'] != null)
                      item['publishedDate'].toString(),
                    if ((item['text'] ?? item['content']) is String)
                      (item['text'] ?? item['content']) as String,
                  ].join(' · '),
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                ),
                trailing: const Icon(Icons.open_in_new, size: 16),
                onTap: () => openSourceLink(context, item['url'] as String),
              ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isPending = widget.part.approvalState?.toLowerCase() == 'pending';

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 8),
      decoration: BoxDecoration(
        color: StudyPalette.of(context).bgWhite,
        borderRadius: BorderRadius.circular(12),
        border: widget.part.executionState == 'COMPLETED' && !isPending
            ? null
            : Border.all(color: StudyPalette.of(context).borderGray, width: 1),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          InkWell(
            onTap: () => setState(() => _expanded = !_expanded),
            borderRadius: BorderRadius.circular(12),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 2),
              child: Row(
                children: [
                  if (widget.part.executionState == 'RUNNING' &&
                      widget.part.approvalState?.toLowerCase() != 'pending')
                    ChatActivityDots(
                      color: statusColor,
                      reduceMotion: widget.reduceMotion,
                    )
                  else
                    Icon(statusIcon, size: 18, color: statusColor),
                  const SizedBox(width: 10),
                  Expanded(
                    child: AnimatedSwitcher(
                      duration: Duration(
                        milliseconds: MediaQuery.disableAnimationsOf(context)
                            ? 0
                            : 180,
                      ),
                      child: Text(
                        '$statusLabel · $toolLabel${searchQuery == null ? '' : ' · $searchQuery'}',
                        key: ValueKey('$statusLabel:$toolLabel'),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 13,
                          color: StudyPalette.of(context).textGray,
                        ),
                      ),
                    ),
                  ),
                  Icon(
                    _expanded
                        ? Icons.keyboard_arrow_up
                        : Icons.keyboard_arrow_down,
                    size: 20,
                    color: StudyPalette.of(context).textGray,
                  ),
                ],
              ),
            ),
          ),

          if (_expanded) ...[
            Container(
              decoration: BoxDecoration(
                border: Border(
                  top: BorderSide(
                    color: StudyPalette.of(context).borderGray,
                    width: 1,
                  ),
                ),
              ),
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (isWebRead) _buildWebEvidence(context),
                  if (!isWebRead &&
                      widget.part.toolInput != null &&
                      widget.part.toolInput!.isNotEmpty) ...[
                    Text(
                      '参数',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                        color: StudyPalette.of(context).textGray,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _formatJson(widget.part.toolInput!),
                      style: TextStyle(
                        fontSize: 11,
                        color: StudyPalette.of(context).textBlack,
                        fontFamily: 'monospace',
                      ),
                    ),
                    const SizedBox(height: 12),
                  ],

                  if (!isWebRead &&
                      widget.part.toolOutput != null &&
                      widget.part.toolOutput!.isNotEmpty) ...[
                    Text(
                      '结果',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                        color: StudyPalette.of(context).textGray,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _formatJson(widget.part.toolOutput!),
                      style: TextStyle(
                        fontSize: 11,
                        color: StudyPalette.of(context).textBlack,
                        fontFamily: 'monospace',
                      ),
                    ),
                  ],

                  // Actions
                  if (isPending &&
                      widget.onApprove != null &&
                      widget.onDeny != null) ...[
                    const SizedBox(height: 12),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        OutlinedButton(
                          onPressed: widget.onDeny,
                          style: OutlinedButton.styleFrom(
                            foregroundColor: StudyPalette.of(context).errorRed,
                            side: BorderSide(
                              color: StudyPalette.of(context).errorRed,
                            ),
                            padding: const EdgeInsets.symmetric(
                              horizontal: 16,
                              vertical: 8,
                            ),
                          ),
                          child: const Text(
                            '拒绝',
                            style: TextStyle(fontSize: 12),
                          ),
                        ),
                        const SizedBox(width: 8),
                        ElevatedButton(
                          onPressed: widget.onApprove,
                          style: ElevatedButton.styleFrom(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 16,
                              vertical: 8,
                            ),
                          ),
                          child: const Text(
                            '允许',
                            style: TextStyle(fontSize: 12),
                          ),
                        ),
                      ],
                    ),
                  ] else if (widget.onRetry != null ||
                      widget.onCancel != null) ...[
                    const SizedBox(height: 12),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        if (widget.onCancel != null)
                          TextButton(
                            onPressed: widget.onCancel,
                            child: const Text(
                              '取消',
                              style: TextStyle(fontSize: 12),
                            ),
                          ),
                        if (widget.onRetry != null) ...[
                          const SizedBox(width: 8),
                          TextButton(
                            onPressed: widget.onRetry,
                            child: const Text(
                              '重试',
                              style: TextStyle(fontSize: 12),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ],
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  String _formatJson(String text) {
    try {
      final json = jsonDecode(text);
      return const JsonEncoder.withIndent('  ').convert(json);
    } catch (_) {
      return text;
    }
  }
}

/// P13: Task clarification card with single-select options
class ClarificationCard extends StatefulWidget {
  final String title;
  final List<String> options;
  final String? initialOption;
  final String? extraFieldLabel;
  final String? extraFieldValue;
  final ValueChanged<String>? onConfirm;
  final VoidCallback? onSkip;

  const ClarificationCard({
    super.key,
    this.title = '选择受众',
    this.options = const ['组会汇报', '课堂讲解', '非专业听众'],
    this.initialOption = '组会汇报',
    this.extraFieldLabel = '预估页数',
    this.extraFieldValue = '8–10 页',
    this.onConfirm,
    this.onSkip,
  });

  @override
  State<ClarificationCard> createState() => _ClarificationCardState();
}

class _ClarificationCardState extends State<ClarificationCard> {
  late String _selected;

  @override
  void initState() {
    super.initState();
    _selected =
        widget.initialOption ??
        (widget.options.isNotEmpty ? widget.options.first : '');
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 10),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: StudyPalette.of(context).bgWhite,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: StudyPalette.of(context).borderGray,
          width: 0.8,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            widget.title,
            style: TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w600,
              color: StudyPalette.of(context).textBlack,
            ),
          ),
          const SizedBox(height: 12),

          // Options
          ...widget.options.map((opt) {
            final isChosen = opt == _selected;
            return InkWell(
              onTap: () => setState(() => _selected = opt),
              borderRadius: BorderRadius.circular(8),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Row(
                  children: [
                    Container(
                      width: 18,
                      height: 18,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        border: Border.all(
                          color: isChosen
                              ? StudyPalette.of(context).textBlack
                              : StudyPalette.of(context).borderGray,
                          width: isChosen ? 5 : 1.5,
                        ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Text(
                      opt,
                      style: TextStyle(
                        fontSize: 14,
                        color: isChosen
                            ? StudyPalette.of(context).textBlack
                            : StudyPalette.of(context).textGray,
                        fontWeight: isChosen
                            ? FontWeight.w500
                            : FontWeight.normal,
                      ),
                    ),
                  ],
                ),
              ),
            );
          }),

          if (widget.extraFieldLabel != null &&
              widget.extraFieldValue != null) ...[
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: StudyPalette.of(context).surfaceBackground,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    widget.extraFieldLabel!,
                    style: TextStyle(
                      fontSize: 12,
                      color: StudyPalette.of(context).textGray,
                    ),
                  ),
                  Text(
                    widget.extraFieldValue!,
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: StudyPalette.of(context).textBlack,
                    ),
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 16),

          // Action buttons
          Row(
            children: [
              Expanded(
                child: ElevatedButton(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: StudyPalette.of(context).textBlack,
                    foregroundColor: StudyPalette.of(context).onAccent,
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(10),
                    ),
                  ),
                  onPressed: () => widget.onConfirm?.call(_selected),
                  child: const Text('按此生成', style: TextStyle(fontSize: 13)),
                ),
              ),
              const SizedBox(width: 10),
              OutlinedButton(
                style: OutlinedButton.styleFrom(
                  foregroundColor: StudyPalette.of(context).textBlack,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 12,
                  ),
                  side: BorderSide(color: StudyPalette.of(context).borderGray),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(10),
                  ),
                ),
                onPressed: widget.onSkip,
                child: const Text('暂时跳过', style: TextStyle(fontSize: 13)),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Center(
            child: Text(
              '也可以直接告诉我你的要求',
              style: TextStyle(
                fontSize: 11,
                color: StudyPalette.of(context).textGray,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// P14: Multi-step file processing card in progress
class FileTaskProgressCard extends StatelessWidget {
  final String title;
  final List<String> completedSteps;
  final String currentStep;
  final VoidCallback? onCancel;

  const FileTaskProgressCard({
    super.key,
    this.title = '正在生成文档',
    this.completedSteps = const ['读取报告已完成', '整理摘要已完成'],
    this.currentStep = '生成文档进行中',
    this.onCancel,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 10),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: StudyPalette.of(context).bgWhite,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: StudyPalette.of(context).borderGray,
          width: 0.8,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: StudyPalette.of(context).textBlack,
                ),
              ),
              const SizedBox(width: 10),
              Text(
                title,
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: StudyPalette.of(context).textBlack,
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),

          // Completed steps
          ...completedSteps.map(
            (step) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                children: [
                  Icon(
                    Icons.check,
                    size: 16,
                    color: StudyPalette.of(context).textGray,
                  ),
                  const SizedBox(width: 8),
                  Text(
                    step,
                    style: TextStyle(
                      fontSize: 13,
                      color: StudyPalette.of(context).textGray,
                    ),
                  ),
                ],
              ),
            ),
          ),

          // Current in-progress step
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(
              children: [
                Container(
                  width: 14,
                  height: 14,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: StudyPalette.of(context).textBlack,
                      width: 2,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Text(
                  currentStep,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: StudyPalette.of(context).textBlack,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),

          // Cancel button
          if (onCancel != null)
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: onCancel,
                style: TextButton.styleFrom(
                  foregroundColor: StudyPalette.of(context).textGray,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 6,
                  ),
                ),
                child: const Text('取消处理', style: TextStyle(fontSize: 12)),
              ),
            ),
        ],
      ),
    );
  }
}

/// P15: Task interrupted with retained results card
class TaskInterruptedCard extends StatelessWidget {
  final String title;
  final String description;
  final VoidCallback? onRetry;
  final VoidCallback? onUseRetained;

  const TaskInterruptedCard({
    super.key,
    this.title = '文档生成中断',
    this.description = '摘要已保留，当前没有可下载文件。',
    this.onRetry,
    this.onUseRetained,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 10),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: StudyPalette.of(context).bgWhite,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: StudyPalette.of(context).borderGray,
          width: 0.8,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                Icons.error_outline,
                size: 18,
                color: StudyPalette.of(context).errorRed,
              ),
              const SizedBox(width: 8),
              Text(
                title,
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: StudyPalette.of(context).textBlack,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            description,
            style: TextStyle(
              fontSize: 13,
              color: StudyPalette.of(context).textGray,
              height: 1.4,
            ),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              if (onRetry != null)
                ElevatedButton(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: StudyPalette.of(context).textBlack,
                    foregroundColor: StudyPalette.of(context).onAccent,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 10,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(10),
                    ),
                  ),
                  onPressed: onRetry,
                  child: const Text('重试生成', style: TextStyle(fontSize: 12)),
                ),
              if (onUseRetained != null) ...[
                const SizedBox(width: 10),
                OutlinedButton(
                  style: OutlinedButton.styleFrom(
                    foregroundColor: StudyPalette.of(context).textBlack,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 10,
                    ),
                    side: BorderSide(
                      color: StudyPalette.of(context).borderGray,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(10),
                    ),
                  ),
                  onPressed: onUseRetained,
                  child: const Text('使用已有摘要', style: TextStyle(fontSize: 12)),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }
}
