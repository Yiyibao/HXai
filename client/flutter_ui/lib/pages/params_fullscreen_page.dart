import 'package:flutter/material.dart';

import '../widgets/study_ui.dart';

/// Fullscreen analysis opens from the live surface in Chat to preserve its state.
class ParamsFullscreenPage extends StatelessWidget {
  const ParamsFullscreenPage({super.key});
  @override
  Widget build(BuildContext context) => const Scaffold(
    appBar: StudyHeader('参数探索'),
    body: StudyEmptyState(
      icon: Icons.open_in_full,
      title: '从聊天中展开参数',
      description: '打开回答中的交互组件，点击展开，即可查看全屏内容并保留当前筛选与参数。',
    ),
  );
}
