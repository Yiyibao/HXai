import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../theme/study_theme.dart';

/// Decorative waiting state, independent of service progress or image previews.
class ImageGenerationLoading extends StatefulWidget {
  final bool compact;

  /// Only a measured service progress value, never elapsed-time estimation.
  final double? progress;
  const ImageGenerationLoading({super.key, this.compact = false, this.progress})
    : assert(progress == null || (progress >= 0 && progress <= 1));

  @override
  State<ImageGenerationLoading> createState() => _ImageGenerationLoadingState();
}

class _ImageGenerationLoadingState extends State<ImageGenerationLoading>
    with SingleTickerProviderStateMixin {
  late final motion = AnimationController(
    vsync: this,
    duration: const Duration(seconds: 7),
  );

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (MediaQuery.disableAnimationsOf(context) ||
        !TickerMode.valuesOf(context).enabled) {
      motion.stop();
    } else if (!motion.isAnimating) {
      motion.repeat();
    }
  }

  @override
  void dispose() {
    motion.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = StudyPalette.of(context);
    return Semantics(
      label: widget.progress == null
          ? '正在生成图片'
          : '正在生成图片，${(widget.progress! * 100).round()}%',
      child: RepaintBoundary(
        child: Stack(
          fit: StackFit.expand,
          children: [
            CustomPaint(painter: _DotWavePainter(motion, c)),
            if (!widget.compact)
              Positioned(
                right: 18,
                bottom: 18,
                child: ExcludeSemantics(
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 10,
                    ),
                    decoration: BoxDecoration(
                      color: c.bubbleGray,
                      borderRadius: BorderRadius.circular(30),
                    ),
                    child: Text(
                      widget.progress == null
                          ? '生成中'
                          : '${(widget.progress! * 100).round()}%',
                      style: TextStyle(
                        color: c.textBlack,
                        fontSize: 15,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _DotWavePainter extends CustomPainter {
  final Animation<double> motion;
  final StudyPalette palette;
  _DotWavePainter(this.motion, this.palette) : super(repaint: motion);

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = palette.bgWhite);
    if (size.isEmpty) return;
    final step = size.shortestSide / 23;
    final inset = step * 0.9;
    final columns = ((size.width - 2 * inset) / step).floor();
    final rows = ((size.height - 2 * inset) / step).floor();
    final t = motion.value * math.pi * 2;
    final centerX = 0.5 + 0.34 * math.sin(t);
    final centerY = 0.55 + 0.22 * math.cos(t);
    final paint = Paint();
    for (var row = 0; row <= rows; row++) {
      for (var column = 0; column <= columns; column++) {
        final x = columns == 0 ? 0.5 : column / columns;
        final y = rows == 0 ? 0.5 : row / rows;
        final dx = (x - centerX) / 0.36;
        final dy = (y - centerY) / 0.31;
        final cloud = math.exp(-(dx * dx + dy * dy) / 2);
        final wave = 0.5 + 0.5 * math.sin(5 * x - 3 * y - t);
        final strength = (cloud * (0.72 + 0.28 * wave)).clamp(0.0, 1.0);
        // Position stays fixed; diameter and opacity form the travelling wave.
        paint.color = palette.accent.withValues(alpha: 0.13 + 0.65 * strength);
        canvas.drawCircle(
          Offset(inset + column * step, inset + row * step),
          step * (0.065 + 0.17 * strength),
          paint,
        );
      }
    }
  }

  @override
  bool shouldRepaint(covariant _DotWavePainter oldDelegate) =>
      oldDelegate.palette.isDark != palette.isDark ||
      oldDelegate.palette.accentColor != palette.accentColor;
}

/// Reveals a decoded final image; no simulated intermediate generation frames.
Widget revealGeneratedImage(BuildContext context, Widget child) {
  if (MediaQuery.disableAnimationsOf(context)) return child;
  return TweenAnimationBuilder<double>(
    tween: Tween(begin: 0, end: 1),
    duration: const Duration(milliseconds: 650),
    curve: Curves.easeOutCubic,
    child: child,
    builder: (_, value, image) => Opacity(
      opacity: value,
      child: ImageFiltered(
        enabled: value < 0.99,
        imageFilter: ui.ImageFilter.blur(
          sigmaX: 8 * (1 - value),
          sigmaY: 8 * (1 - value),
        ),
        child: image,
      ),
    ),
  );
}
