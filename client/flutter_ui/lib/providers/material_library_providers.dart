import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'frontend_feature_providers.dart';
import 'study_providers.dart';

class LibraryAccess {
  final String profile;
  final int epoch;
  const LibraryAccess(this.profile, this.epoch);
  bool current(WidgetRef ref) => profile == ref.read(activeProfileIdProvider) && epoch == ref.read(contextEpochProvider);
  Future<T?> call<T>(String method, [Map<String, Object?> fields = const {}]) => FrontendFeatures.channel.invokeMethod<T>(method, {
    ...fields, 'profileId': profile, 'epoch': epoch,
  });
}

class LibraryEntry {
  final Map<dynamic, dynamic> data;
  LibraryEntry(this.data);
  String get id => data['id'] as String;
  String get name => data['name'] as String;
  String get hash => data['sha256'] as String;
  String get extension => data['extension'] as String;
  String get folder => data['folder'] as String? ?? '';
  List<String> get tags => (data['tags'] as List? ?? []).cast<String>();
  int get createdAt => data['createdAt'] as int;
  int get size => data['size'] as int;
  String get state => data['state'] as String;
  String get detail => data['detail'] as String;
  String? get parentId => data['sourceId'] as String?;
  bool get deleted => data['deletedAt'] != null;
  bool get recoverable => data['recoverable'] == true;
  Map<String, Object?> get identity => {'id': id, 'sha256': hash};
}

class LibrarySnapshot {
  final LibraryAccess access;
  final List<LibraryEntry> files, trash;
  LibrarySnapshot(this.access, Map<dynamic, dynamic> data)
    : files = (data['files'] as List).map((e) => LibraryEntry(e as Map)).toList(),
      trash = (data['trash'] as List).map((e) => LibraryEntry(e as Map)).toList();
}

final materialLibraryProvider = FutureProvider<LibrarySnapshot>((ref) async {
  final access = LibraryAccess(ref.watch(activeProfileIdProvider), ref.watch(contextEpochProvider));
  await ref.watch(materialsProvider.future);
  final data = await access.call<Map<dynamic, dynamic>>('library.snapshot');
  if (data == null || data['contractVersion'] != 1) throw StateError('资料库协议不可用');
  return LibrarySnapshot(access, data);
});
