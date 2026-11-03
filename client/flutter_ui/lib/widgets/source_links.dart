import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

bool isWebSource(String value) {
  final uri = Uri.tryParse(value);
  return uri != null &&
      const ['https', 'http'].contains(uri.scheme) &&
      uri.host.isNotEmpty &&
      uri.userInfo.isEmpty;
}

Future<void> openSourceLink(BuildContext context, String value) async {
  if (!isWebSource(value)) return;
  try {
    await const MethodChannel('me.rerere.rikkahub/external_links')
        .invokeMethod<void>('open', value);
  } catch (_) {
    if (context.mounted)
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('无法打开来源链接')));
  }
}
