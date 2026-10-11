import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

/// Keeps streaming content in view until the reader scrolls into history.
/// Layout changes (images, expanded cards, keyboard) use the same follow policy.
class ChatTimeline extends StatefulWidget {
  const ChatTimeline({
    super.key,
    required this.itemCount,
    required this.itemBuilder,
    required this.autoFollow,
  });

  final int itemCount;
  final IndexedWidgetBuilder itemBuilder;
  final bool autoFollow;

  @override
  State<ChatTimeline> createState() => _ChatTimelineState();
}

class _ChatTimelineState extends State<ChatTimeline> {
  final _controller = ScrollController();
  bool _following = true;
  bool _dragging = false;
  bool _scheduled = false;
  bool _showLatest = false;
  bool _explicitFollow = false;

  bool get _atBottom =>
      !_controller.hasClients || _controller.position.extentAfter < 60;

  void _scheduleLayoutCheck() {
    if (_scheduled) return;
    _scheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scheduled = false;
      if (!mounted || !_controller.hasClients) return;
      if (_following && !_dragging && (widget.autoFollow || _explicitFollow)) {
        final position = _controller.position;
        if (position.extentAfter > 0.5) {
          // A jump cannot fight a previous streaming animation. The next frame
          // also checks the extent as lazy items may refine its estimate.
          _controller.jumpTo(position.maxScrollExtent);
          _scheduleLayoutCheck();
        } else {
          _explicitFollow = false;
        }
      }
      final show = !_atBottom;
      if (_showLatest != show) setState(() => _showLatest = show);
    });
  }

  bool _onScroll(ScrollNotification notification) {
    if (notification.depth != 0) return false;
    if (notification is ScrollStartNotification &&
        notification.dragDetails != null) {
      _dragging = true;
      _explicitFollow = false;
    }
    if (notification is UserScrollNotification &&
        notification.direction == ScrollDirection.forward) {
      _following = false;
      _explicitFollow = false;
    }
    if (notification is ScrollEndNotification) {
      _dragging = false;
      _following = _atBottom;
    }
    _scheduleLayoutCheck();
    return false;
  }

  void _returnToLatest() {
    _following = true;
    _explicitFollow = true;
    _scheduleLayoutCheck();
    WidgetsBinding.instance.ensureVisualUpdate();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    _scheduleLayoutCheck();
    final colors = Theme.of(context).colorScheme;
    return Stack(
      children: [
        NotificationListener<ScrollMetricsNotification>(
          onNotification: (notification) {
            if (notification.depth == 0) _scheduleLayoutCheck();
            return false;
          },
          child: NotificationListener<ScrollNotification>(
            onNotification: _onScroll,
            child: ListView.builder(
              controller: _controller,
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
              itemCount: widget.itemCount,
              itemBuilder: widget.itemBuilder,
            ),
          ),
        ),
        if (_showLatest)
          Positioned(
            right: 16,
            bottom: 16,
            child: Material(
              color: colors.surface,
              elevation: 3,
              shape: CircleBorder(
                side: BorderSide(color: colors.outlineVariant),
              ),
              child: IconButton(
                tooltip: '回到最新消息',
                onPressed: _returnToLatest,
                icon: const Icon(Icons.keyboard_arrow_down),
              ),
            ),
          ),
      ],
    );
  }
}
