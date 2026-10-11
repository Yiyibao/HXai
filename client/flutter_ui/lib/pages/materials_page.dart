import 'package:flutter/material.dart';

import 'files_page.dart';

/// Compatibility wrapper forwarding legacy MaterialsPage to FilesPage (P18)
class MaterialsPage extends StatelessWidget {
  const MaterialsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return const FilesPage();
  }
}
