import 'package:flutter/material.dart';

import '../theme/study_theme.dart';
import 'source_links.dart';

import 'dart:convert';

import '../bridge/study_host_api.g.dart';

class CitationItem {
  final int index;
  final String title;
  final String publisher;
  final String summary;
  final String? date;
  final String? url;

  const CitationItem({
    required this.index,
    required this.title,
    required this.publisher,
    required this.summary,
    this.date,
    this.url,
  });
}

class CitationsSheet extends StatelessWidget {
  const CitationsSheet({super.key, required this.citations, this.onOpenUrl});

  final List<CitationItem> citations;
  final ValueChanged<String>? onOpenUrl;

  static Future<void> show(
    BuildContext context, {
    List<CitationItem>? citations,
    ValueChanged<String>? onOpenUrl,
  }) {
    final defaultList = citations ?? const <CitationItem>[];

    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) =>
          CitationsSheet(citations: defaultList, onOpenUrl: onOpenUrl),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: BoxConstraints(
        maxHeight: MediaQuery.of(context).size.height * 0.68,
      ),
      decoration: BoxDecoration(
        color: StudyPalette.of(context).bgWhite,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 12,
        bottom: MediaQuery.of(context).padding.bottom + 16,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Drag handle
          Center(
            child: Container(
              width: 36,
              height: 4,
              margin: const EdgeInsets.only(bottom: 16),
              decoration: BoxDecoration(
                color: StudyPalette.of(context).borderGray,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),

          // Header
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Row(
                children: [
                  Text(
                    '来源与引用',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w600,
                      color: StudyPalette.of(context).textBlack,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 2,
                    ),
                    decoration: BoxDecoration(
                      color: StudyPalette.of(context).surfaceBackground,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Text(
                      '${citations.length} 条来源',
                      style: TextStyle(
                        fontSize: 12,
                        color: StudyPalette.of(context).textGray,
                      ),
                    ),
                  ),
                ],
              ),
              IconButton(
                icon: Icon(
                  Icons.close,
                  size: 20,
                  color: StudyPalette.of(context).textGray,
                ),
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
                onPressed: () => Navigator.of(context).pop(),
              ),
            ],
          ),
          const SizedBox(height: 16),

          // Citations List
          if (citations.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 32),
              child: Text(
                '这条回答未提供可核对的来源。',
                style: TextStyle(color: StudyPalette.of(context).textGray),
              ),
            ),
          Flexible(
            child: ListView.separated(
              shrinkWrap: true,
              itemCount: citations.length,
              separatorBuilder: (_, _) => const SizedBox(height: 12),
              itemBuilder: (context, index) {
                final item = citations[index];
                return _buildCitationCard(context, item);
              },
            ),
          ),
          const SizedBox(height: 16),

          // Footnote
          Center(
            child: Text(
              '打开原出处可查看完整内容',
              style: TextStyle(
                fontSize: 12,
                color: StudyPalette.of(context).textGray,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildCitationCard(BuildContext context, CitationItem item) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: StudyPalette.of(context).bgWhite,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: StudyPalette.of(context).borderGray,
          width: 0.8,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Badge
              Container(
                width: 22,
                height: 22,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: StudyPalette.of(context).surfaceBackground,
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(
                    color: StudyPalette.of(context).borderGray,
                    width: 0.8,
                  ),
                ),
                child: Text(
                  '${item.index}',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    color: StudyPalette.of(context).textBlack,
                  ),
                ),
              ),
              const SizedBox(width: 10),
              // Title & Publisher
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      item.title,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: StudyPalette.of(context).textBlack,
                        height: 1.3,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Wrap(
                      spacing: 8,
                      runSpacing: 4,
                      children: [
                        Text(
                          item.publisher,
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w500,
                            color: StudyPalette.of(context).textGray,
                          ),
                        ),
                        if (item.date != null) ...[
                          const SizedBox(width: 8),
                          Text(
                            item.date!,
                            style: TextStyle(
                              fontSize: 11,
                              color: StudyPalette.of(context).textGray,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ],
                ),
              ),
              if (item.url != null && isWebSource(item.url!))
                IconButton(
                  icon: Icon(
                    Icons.open_in_new,
                    size: 16,
                    color: StudyPalette.of(context).textGray,
                  ),
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(
                    minWidth: 28,
                    minHeight: 28,
                  ),
                  onPressed: () {
                    if (onOpenUrl != null) {
                      onOpenUrl!(item.url!);
                    } else {
                      openSourceLink(context, item.url!);
                    }
                  },
                ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            item.summary,
            style: TextStyle(
              fontSize: 13,
              color: StudyPalette.of(context).textBlack,
              height: 1.4,
            ),
          ),
        ],
      ),
    );
  }
}

/// Collect only actual URL metadata from this answer; no sample bibliography.
List<CitationItem> messageCitations(MessageDto message) {
  final result = <CitationItem>[];
  final seen = <String>{};
  void add(
    String url,
    String title, [
    String summary = '',
    String publisher = '',
    String? date,
  ]) {
    if (!isWebSource(url) || !seen.add(url)) return;
    result.add(
      CitationItem(
        index: result.length + 1,
        title: title.isEmpty ? Uri.parse(url).host : title,
        publisher: publisher.isEmpty ? Uri.parse(url).host : publisher,
        summary: summary,
        url: url,
        date: date,
      ),
    );
  }

  String excerpt(dynamic value) {
    final text = (value ?? '').toString();
    return text.length <= 600 ? text : '${text.substring(0, 600)}…';
  }

  void visit(dynamic value) {
    if (value is Map) {
      final url = value['sourceUrl'] ?? value['url'] ?? value['link'];
      if (url is String)
        add(
          url,
          (value['title'] ?? value['sourceTitle'] ?? value['caption'] ?? '')
              .toString(),
          excerpt(
            value['snippet'] ??
                value['description'] ??
                value['text'] ??
                value['content'],
          ),
          (value['publisher'] ?? value['author'] ?? '').toString(),
          (value['publishedDate'] ??
                  (value['metadata'] is Map
                      ? value['metadata']['publishedDate']
                      : null))
              ?.toString(),
        );
      for (final child in value.values) {
        if (child is Map || child is List) visit(child);
      }
    } else if (value is List) {
      for (final child in value) {
        visit(child);
      }
    }
  }

  for (final part in message.parts) {
    if (part.type == 'image' ||
        part.type == 'tool' ||
        part.type == 'server_tool') {
      final raw = part.type == 'image' ? part.text : part.toolOutput;
      if (raw != null) {
        try {
          visit(jsonDecode(raw));
        } catch (_) {}
      }
    }
    if (part.type == 'text') {
      for (final match in RegExp(
        r'\[([^\]]+)\]\((https?://[^\s)]+)\)',
      ).allMatches(part.text ?? '')) {
        add(match.group(2)!, match.group(1)!);
      }
    }
  }
  return result;
}
