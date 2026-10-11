import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show ProviderListenable;

import 'study_providers.dart';

/// Navigation carries identity only, never a private-content snapshot.
class ProfileResourceRoute {
  final String id, profileId;
  final int epoch;
  const ProfileResourceRoute({
    required this.id,
    required this.profileId,
    required this.epoch,
  });

  factory ProfileResourceRoute.capture(WidgetRef ref, String id) =>
      ProfileResourceRoute(
        id: id,
        profileId: ref.read(activeProfileIdProvider),
        epoch: ref.read(contextEpochProvider),
      );

  bool isCurrent(WidgetRef ref) {
    final bootstrap = ref.read(bootstrapProvider);
    return bootstrap.hasValue && !bootstrap.isLoading && !bootstrap.hasError &&
        profileId == ref.read(activeProfileIdProvider) &&
        epoch == ref.read(contextEpochProvider);
  }

  T? resolve<T>(
    AsyncValue<List<T>> list,
    String activeProfile,
    int activeEpoch,
    String Function(T) idOf,
  ) {
    if (profileId != activeProfile || epoch != activeEpoch ||
        list.isLoading || list.hasError) return null;
    return list.value?.where((item) => idOf(item) == id).firstOrNull;
  }
}

T? watchResource<T>(
  WidgetRef ref,
  ProfileResourceRoute? resource,
  ProviderListenable<AsyncValue<List<T>>> provider,
  String Function(T) idOf,
) {
  ref.watch(bootstrapProvider);
  final list = ref.watch(provider);
  final profile = ref.watch(activeProfileIdProvider);
  final epoch = ref.watch(contextEpochProvider);
  if (resource?.isCurrent(ref) != true) return null;
  return resource!.resolve(list, profile, epoch, idOf);
}

T? readResource<T>(
  WidgetRef ref,
  ProfileResourceRoute? resource,
  ProviderListenable<AsyncValue<List<T>>> provider,
  String Function(T) idOf,
) {
  if (resource?.isCurrent(ref) != true) return null;
  return resource!.resolve(
    ref.read(provider),
    ref.read(activeProfileIdProvider),
    ref.read(contextEpochProvider),
    idOf,
  );
}
