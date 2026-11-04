import 'dart:io';

/// Resolves a Flutter material font from the SDK that launched the test.
///
/// The SDK stays outside the worktree. `flutter.bat` sets `FLUTTER_ROOT`.
/// The old in-repo `.local/flutter` path remains only as a fallback.
String materialFont(String fileName) {
  final root = Platform.environment['FLUTTER_ROOT'];
  final candidates = <String>[
    if (root != null && root.isNotEmpty)
      '$root/bin/cache/artifacts/material_fonts/$fileName',
    '../../.local/flutter/bin/cache/artifacts/material_fonts/$fileName',
  ];
  for (final path in candidates) {
    if (File(path).existsSync()) return path;
  }
  throw StateError(
    'Material font $fileName not found. FLUTTER_ROOT=${root ?? 'unset'}',
  );
}
