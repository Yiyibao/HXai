import 'dart:math' as math;

import 'package:flutter/material.dart';

/// Small breathing dots shared by thinking and tool execution indicators.
class ChatActivityDots extends StatefulWidget {
  const ChatActivityDots({super.key, this.color, this.reduceMotion = false});

  final Color? color;
  final bool reduceMotion;

  @override
  State<ChatActivityDots> createState() => _ChatActivityDotsState();
}

class _ChatActivityDotsState extends State<ChatActivityDots>
    with SingleTickerProviderStateMixin {
  late final _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1200),
  );

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final reduced =
        widget.reduceMotion || MediaQuery.disableAnimationsOf(context);
    if (reduced) {
      _controller.stop();
    } else if (!_controller.isAnimating) {
      _controller.repeat();
    }
    final color =
        widget.color ?? Theme.of(context).colorScheme.onSurfaceVariant;
    return ExcludeSemantics(
      child: SizedBox(
        width: 22,
        height: 18,
        child: AnimatedBuilder(
          animation: _controller,
          builder: (context, _) => Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: List.generate(3, (index) {
              final pulse = reduced
                  ? 0.65
                  : (math.sin(_controller.value * 2 * math.pi - index * 0.8) +
                            1) /
                        2;
              return Transform.scale(
                scale: 0.75 + pulse * 0.25,
                child: Container(
                  width: 5,
                  height: 5,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: color.withValues(alpha: 0.3 + pulse * 0.7),
                  ),
                ),
              );
            }),
          ),
        ),
      ),
    );
  }
}

class ChatActivity extends StatelessWidget {
  const ChatActivity({
    super.key,
    required this.label,
    this.reduceMotion = false,
  });

  final String label;
  final bool reduceMotion;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Row(
      children: [
        ChatActivityDots(reduceMotion: reduceMotion),
        const SizedBox(width: 10),
        Flexible(
          child: Text(
            label,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w500,
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      ],
    ),
  );
}
