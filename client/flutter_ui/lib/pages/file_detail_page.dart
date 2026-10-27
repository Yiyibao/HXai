import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/material_library_providers.dart';
import '../providers/profile_resource.dart';
import '../providers/study_providers.dart';
import '../widgets/study_ui.dart';
import 'library_detail_page.dart';

/// The routed source entry retains the existing live resource/epoch guard.
class FileDetailPage extends ConsumerWidget {
  final ProfileResourceRoute? resource;
  const FileDetailPage({super.key, this.resource});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final current = watchResource(ref, resource, materialsProvider, (m) => m.id);
    if (current != null) {
      final library = ref.watch(materialLibraryProvider);
      if (library.isLoading) return const Scaffold(appBar: StudyHeader('文件详情'), body: Center(child: CircularProgressIndicator()));
      final snapshot = library.value;
      final entry = snapshot?.files.where((entry) => entry.id == current.id && entry.hash == current.sha256).firstOrNull;
      if (entry != null && snapshot!.access.current(ref)) {
        return LibraryDetailPage(key: ValueKey('${resource!.profileId}:${resource!.epoch}:${entry.id}'), access: snapshot.access, entry: entry);
      }
    }
    final loading = resource?.isCurrent(ref) == true && ref.watch(materialsProvider).isLoading;
    return Scaffold(appBar: const StudyHeader('文件详情'), body: loading ? const Center(child: CircularProgressIndicator()) :
      const StudyEmptyState(icon: Icons.description_outlined, title: '文件不可用', description: '文件已删除、加载失败或档案已改变，请从当前资料库重新打开。'));
  }
}
