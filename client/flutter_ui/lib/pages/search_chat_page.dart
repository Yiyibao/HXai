import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../theme/study_theme.dart';

/// P02 · 搜索聊天
class SearchChatPage extends ConsumerStatefulWidget {
  const SearchChatPage({super.key});

  @override
  ConsumerState<SearchChatPage> createState() => _SearchChatPageState();
}

class _SearchChatPageState extends ConsumerState<SearchChatPage> {
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  List<ConversationSummaryDto> _results = [];
  bool _searching = false;
  String _currentQuery = '';

  @override
  void initState() {
    super.initState();
    _focusNode.requestFocus();
  }

  @override
  void dispose() {
    _searchController.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  Future<void> _performSearch(String query) async {
    final trimmed = query.trim();
    if (trimmed == _currentQuery) return;
    _currentQuery = trimmed;

    if (trimmed.isEmpty) {
      setState(() {
        _results = [];
        _searching = false;
      });
      return;
    }

    setState(() => _searching = true);
    try {
      final profile = ref.read(activeProfileIdProvider);
      final list = await bridgeApi.searchConversations(
        profile, trimmed, 50, 0, ref.read(historyArchivedProvider));
      if (mounted && trimmed == _currentQuery) {
        setState(() {
          _results = list;
          _searching = false;
        });
      }
    } catch (_) {
      if (mounted && trimmed == _currentQuery) {
        setState(() => _searching = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: StudyPalette.of(context).bgWhite,
      appBar: AppBar(
        backgroundColor: StudyPalette.of(context).bgWhite,
        elevation: 0,
        leading: IconButton(
          icon: Icon(
            Icons.arrow_back,
            color: StudyPalette.of(context).textBlack,
          ),
          onPressed: () => context.pop(),
        ),
        titleSpacing: 0,
        title: Container(
          height: 40,
          margin: const EdgeInsets.only(right: 16),
          decoration: BoxDecoration(
            color: StudyPalette.of(context).bubbleGray,
            borderRadius: BorderRadius.circular(20),
          ),
          child: TextField(
            controller: _searchController,
            focusNode: _focusNode,
            style: TextStyle(
              fontSize: 14,
              color: StudyPalette.of(context).textBlack,
            ),
            decoration: InputDecoration(
              hintText: '搜索聊天...',
              hintStyle: TextStyle(
                fontSize: 14,
                color: StudyPalette.of(context).textGray,
              ),
              prefixIcon: Icon(
                Icons.search,
                size: 18,
                color: StudyPalette.of(context).textGray,
              ),
              suffixIcon: _searchController.text.isNotEmpty
                  ? IconButton(
                      icon: Icon(
                        Icons.close,
                        size: 16,
                        color: StudyPalette.of(context).textGray,
                      ),
                      onPressed: () {
                        _searchController.clear();
                        _performSearch('');
                      },
                    )
                  : null,
              border: InputBorder.none,
              enabledBorder: InputBorder.none,
              focusedBorder: InputBorder.none,
              filled: false,
              contentPadding: const EdgeInsets.symmetric(vertical: 10),
            ),
            onChanged: (val) {
              setState(() {});
              _performSearch(val);
            },
          ),
        ),
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_searching) {
      return Center(
        child: CircularProgressIndicator(
          color: StudyPalette.of(context).textBlack,
          strokeWidth: 2,
        ),
      );
    }

    if (_currentQuery.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.search_rounded,
              size: 48,
              color: StudyPalette.of(context).borderGray,
            ),
            const SizedBox(height: 12),
            Text(
              '输入关键词搜索对话标题与正文',
              style: TextStyle(
                fontSize: 14,
                color: StudyPalette.of(context).textGray,
              ),
            ),
          ],
        ),
      );
    }

    if (_results.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.sentiment_dissatisfied_outlined,
              size: 48,
              color: StudyPalette.of(context).textGray,
            ),
            const SizedBox(height: 12),
            Text(
              '未找到与 "$_currentQuery" 相关的聊天',
              style: TextStyle(
                fontSize: 14,
                color: StudyPalette.of(context).textGray,
              ),
            ),
          ],
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
          child: Text(
            '${_results.length} 条相关聊天',
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w500,
              color: StudyPalette.of(context).textGray,
            ),
          ),
        ),
        Expanded(
          child: ListView.separated(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            itemCount: _results.length,
            separatorBuilder: (_, _) =>
                Divider(color: StudyPalette.of(context).borderGray, height: 1),
            itemBuilder: (context, index) {
              final item = _results[index];
              return InkWell(
                borderRadius: BorderRadius.circular(12),
                onTap: () {
                  ref.read(activeConversationIdProvider.notifier).state =
                      item.id;
                  ref.read(activeStudyModeProvider.notifier).state =
                      item.studyMode;
                  context.go('/chat');
                },
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 14,
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Expanded(
                            child: Text(
                              item.title,
                              style: TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w600,
                                color: StudyPalette.of(context).textBlack,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          Text(
                            _formatDate(item.updatedAt),
                            style: TextStyle(
                              fontSize: 11,
                              color: StudyPalette.of(context).textGray,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text(
                        item.previewText.isNotEmpty
                            ? item.previewText
                            : '无预览内容',
                        style: TextStyle(
                          fontSize: 13,
                          color: StudyPalette.of(context).textGray,
                          height: 1.4,
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  String _formatDate(String dateStr) {
    if (dateStr.isEmpty) return '';
    DateTime? date;
    final millis = int.tryParse(dateStr);
    if (millis != null) {
      date = DateTime.fromMillisecondsSinceEpoch(millis);
    } else {
      date = DateTime.tryParse(dateStr);
    }
    if (date == null) return dateStr;
    final now = DateTime.now();
    if (date.year == now.year &&
        date.month == now.month &&
        date.day == now.day) {
      return '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
    }
    return '${date.month}月${date.day}日';
  }
}
