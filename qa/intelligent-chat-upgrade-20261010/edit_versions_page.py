from pathlib import Path
p=Path(__file__).resolve().parents[2]/'client/flutter_ui/lib/pages/artifact_versions_page.dart'
p.write_text('''import 'package:flutter/material.dart';
import '../widgets/artifact_versions_content.dart';

class ArtifactVersionsPage extends StatelessWidget {
  final String? artifactId, fileName;
  const ArtifactVersionsPage({super.key, this.artifactId, this.fileName});
  @override
  Widget build(BuildContext context) => ArtifactVersionsContent(artifactId: artifactId);
}
''',encoding='utf-8')
