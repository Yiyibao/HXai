import 'package:flutter/material.dart';

import '../theme/study_theme.dart';

enum MascotPose {
  idle,
  thinking,
  reading,
  explaining,
  celebrating,
  confused,
  listening,
  sleeping,
  wave,
}

/// "页芽" Mascot Widget.
/// Rendered using smooth vector graphics with stylized details corresponding to accepted-mascot.png.
/// Respects MediaQuery.disableAnimations.
class StudyMascot extends StatefulWidget {
  final MascotPose pose;
  final double size;
  final String? message;
  final VoidCallback? onTap;

  const StudyMascot({
    super.key,
    this.pose = MascotPose.idle,
    this.size = 80,
    this.message,
    this.onTap,
  });

  @override
  State<StudyMascot> createState() => _StudyMascotState();
}

class _StudyMascotState extends State<StudyMascot>
    with SingleTickerProviderStateMixin {
  late AnimationController _animController;
  late Animation<double> _bounceAnimation;

  @override
  void initState() {
    super.initState();
    _animController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1400),
    );
    _bounceAnimation = Tween<double>(begin: 0, end: -4).animate(
      CurvedAnimation(parent: _animController, curve: Curves.easeInOut),
    );

    // Only loop gentle breathing animation if not disabled by accessibility
    _animController.repeat(reverse: true);
  }

  @override
  void dispose() {
    _animController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final disableAnim = MediaQuery.of(context).disableAnimations;
    if (disableAnim && _animController.isAnimating) {
      _animController.stop();
    }

    final mascotWidget = CustomPaint(
      size: Size(widget.size, widget.size),
      painter: _MascotPainter(pose: widget.pose),
    );

    Widget content = AnimatedBuilder(
      animation: _animController,
      builder: (context, child) {
        final offset = disableAnim ? 0.0 : _bounceAnimation.value;
        return Transform.translate(
          offset: Offset(0, offset),
          child: mascotWidget,
        );
      },
    );

    if (widget.message != null && widget.message!.isNotEmpty) {
      content = Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
            margin: const EdgeInsets.only(bottom: 8),
            decoration: BoxDecoration(
              color: StudyColors.palePinkBg,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: StudyColors.palePinkCard, width: 1.2),
            ),
            child: Text(
              widget.message!,
              style: const TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: StudyColors.deepRose,
              ),
            ),
          ),
          content,
        ],
      );
    }

    if (widget.onTap != null) {
      return GestureDetector(onTap: widget.onTap, child: content);
    }

    return content;
  }
}

class _MascotPainter extends CustomPainter {
  final MascotPose pose;

  _MascotPainter({required this.pose});

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width;
    final h = size.height;
    final center = Offset(w / 2, h / 2 + h * 0.05);

    // Main body paint (soft marshmallow white-pink)
    final bodyPaint = Paint()
      ..color = const Color(0xFFFFF7F9)
      ..style = PaintingStyle.fill;

    final outlinePaint = Paint()
      ..color = StudyColors.deepRose.withValues(alpha: 0.8)
      ..style = PaintingStyle.stroke
      ..strokeWidth = w * 0.035
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;

    final blushPaint = Paint()
      ..color = StudyColors.palePinkCard.withValues(alpha: 0.7)
      ..style = PaintingStyle.fill;

    final eyePaint = Paint()
      ..color = StudyColors.deepRose
      ..style = PaintingStyle.fill;

    final sproutPaint = Paint()
      ..color =
          const Color(0xFF86B86C) // cute green sprout
      ..style = PaintingStyle.fill;

    final sproutOutline = Paint()
      ..color = const Color(0xFF5A8744)
      ..style = PaintingStyle.stroke
      ..strokeWidth = w * 0.025;

    // 1. Draw Bookmark Sprout on head
    final sproutPath = Path();
    sproutPath.moveTo(center.dx, center.dy - h * 0.38);
    sproutPath.cubicTo(
      center.dx + w * 0.12,
      center.dy - h * 0.52,
      center.dx + w * 0.22,
      center.dy - h * 0.45,
      center.dx + w * 0.06,
      center.dy - h * 0.36,
    );
    sproutPath.close();
    canvas.drawPath(sproutPath, sproutPaint);
    canvas.drawPath(sproutPath, sproutOutline);

    // Second small leaf
    final leaf2 = Path();
    leaf2.moveTo(center.dx, center.dy - h * 0.38);
    leaf2.cubicTo(
      center.dx - w * 0.12,
      center.dy - h * 0.48,
      center.dx - w * 0.18,
      center.dy - h * 0.42,
      center.dx - w * 0.05,
      center.dy - h * 0.36,
    );
    leaf2.close();
    canvas.drawPath(leaf2, sproutPaint);
    canvas.drawPath(leaf2, sproutOutline);

    // 2. Draw Rounded Bookmark Body
    final bodyRect = RRect.fromRectAndCorners(
      Rect.fromCenter(center: center, width: w * 0.65, height: h * 0.68),
      topLeft: Radius.circular(w * 0.3),
      topRight: Radius.circular(w * 0.3),
      bottomLeft: Radius.circular(w * 0.18),
      bottomRight: Radius.circular(w * 0.18),
    );
    canvas.drawRRect(bodyRect, bodyPaint);
    canvas.drawRRect(bodyRect, outlinePaint);

    // 3. Draw Cheeks (Blush)
    canvas.drawCircle(
      Offset(center.dx - w * 0.18, center.dy + h * 0.04),
      w * 0.06,
      blushPaint,
    );
    canvas.drawCircle(
      Offset(center.dx + w * 0.18, center.dy + h * 0.04),
      w * 0.06,
      blushPaint,
    );

    // 4. Facial Expressions based on Pose
    switch (pose) {
      case MascotPose.idle:
      case MascotPose.wave:
        // Round open smiling eyes
        canvas.drawCircle(
          Offset(center.dx - w * 0.12, center.dy - h * 0.04),
          w * 0.035,
          eyePaint,
        );
        canvas.drawCircle(
          Offset(center.dx + w * 0.12, center.dy - h * 0.04),
          w * 0.035,
          eyePaint,
        );
        // Gentle curved mouth
        final mouth = Path()
          ..moveTo(center.dx - w * 0.05, center.dy + h * 0.05)
          ..quadraticBezierTo(
            center.dx,
            center.dy + h * 0.09,
            center.dx + w * 0.05,
            center.dy + h * 0.05,
          );
        canvas.drawPath(mouth, outlinePaint..strokeWidth = w * 0.025);
        break;

      case MascotPose.thinking:
        // Looking up thoughtfully
        canvas.drawCircle(
          Offset(center.dx - w * 0.12, center.dy - h * 0.08),
          w * 0.035,
          eyePaint,
        );
        canvas.drawCircle(
          Offset(center.dx + w * 0.12, center.dy - h * 0.08),
          w * 0.035,
          eyePaint,
        );
        // Small round mouth "o"
        canvas.drawCircle(
          Offset(center.dx, center.dy + h * 0.06),
          w * 0.03,
          outlinePaint..style = PaintingStyle.stroke,
        );
        break;

      case MascotPose.reading:
        // Downward curved focused eyes
        final eye1 = Path()
          ..moveTo(center.dx - w * 0.16, center.dy - h * 0.03)
          ..quadraticBezierTo(
            center.dx - w * 0.12,
            center.dy - h * 0.06,
            center.dx - w * 0.08,
            center.dy - h * 0.03,
          );
        final eye2 = Path()
          ..moveTo(center.dx + w * 0.08, center.dy - h * 0.03)
          ..quadraticBezierTo(
            center.dx + w * 0.12,
            center.dy - h * 0.06,
            center.dx + w * 0.16,
            center.dy - h * 0.03,
          );
        canvas.drawPath(eye1, outlinePaint..strokeWidth = w * 0.03);
        canvas.drawPath(eye2, outlinePaint);
        // Mini book in front
        final bookPaint = Paint()
          ..color = StudyColors.palePinkCard
          ..style = PaintingStyle.fill;
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            Rect.fromCenter(
              center: Offset(center.dx, center.dy + h * 0.18),
              width: w * 0.34,
              height: h * 0.18,
            ),
            const Radius.circular(4),
          ),
          bookPaint,
        );
        break;

      case MascotPose.celebrating:
        // Closed happy curved eyes "^ ^"
        final happyEye1 = Path()
          ..moveTo(center.dx - w * 0.16, center.dy - h * 0.02)
          ..quadraticBezierTo(
            center.dx - w * 0.12,
            center.dy - h * 0.07,
            center.dx - w * 0.08,
            center.dy - h * 0.02,
          );
        final happyEye2 = Path()
          ..moveTo(center.dx + w * 0.08, center.dy - h * 0.02)
          ..quadraticBezierTo(
            center.dx + w * 0.12,
            center.dy - h * 0.07,
            center.dx + w * 0.16,
            center.dy - h * 0.02,
          );
        canvas.drawPath(happyEye1, outlinePaint..strokeWidth = w * 0.035);
        canvas.drawPath(happyEye2, outlinePaint);
        // Big cheerful mouth
        final bigMouth = Path()
          ..moveTo(center.dx - w * 0.08, center.dy + h * 0.04)
          ..quadraticBezierTo(
            center.dx,
            center.dy + h * 0.12,
            center.dx + w * 0.08,
            center.dy + h * 0.04,
          )
          ..close();
        canvas.drawPath(
          bigMouth,
          Paint()
            ..color = StudyColors.deepRose
            ..style = PaintingStyle.fill,
        );
        break;

      case MascotPose.sleeping:
        // Sleeping lines "- -"
        canvas.drawLine(
          Offset(center.dx - w * 0.16, center.dy - h * 0.03),
          Offset(center.dx - w * 0.08, center.dy - h * 0.03),
          outlinePaint..strokeWidth = w * 0.035,
        );
        canvas.drawLine(
          Offset(center.dx + w * 0.08, center.dy - h * 0.03),
          Offset(center.dx + w * 0.16, center.dy - h * 0.03),
          outlinePaint,
        );
        break;

      default:
        // Standard friendly eyes
        canvas.drawCircle(
          Offset(center.dx - w * 0.12, center.dy - h * 0.04),
          w * 0.035,
          eyePaint,
        );
        canvas.drawCircle(
          Offset(center.dx + w * 0.12, center.dy - h * 0.04),
          w * 0.035,
          eyePaint,
        );
        final defaultMouth = Path()
          ..moveTo(center.dx - w * 0.05, center.dy + h * 0.05)
          ..quadraticBezierTo(
            center.dx,
            center.dy + h * 0.09,
            center.dx + w * 0.05,
            center.dy + h * 0.05,
          );
        canvas.drawPath(defaultMouth, outlinePaint..strokeWidth = w * 0.025);
        break;
    }

    // 5. If waving pose, draw little waving hand
    if (pose == MascotPose.wave) {
      final handPaint = Paint()
        ..color = const Color(0xFFFFF7F9)
        ..style = PaintingStyle.fill;
      canvas.drawCircle(
        Offset(center.dx + w * 0.36, center.dy - h * 0.12),
        w * 0.08,
        handPaint,
      );
      canvas.drawCircle(
        Offset(center.dx + w * 0.36, center.dy - h * 0.12),
        w * 0.08,
        outlinePaint..strokeWidth = w * 0.03,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _MascotPainter oldDelegate) =>
      oldDelegate.pose != pose;
}
