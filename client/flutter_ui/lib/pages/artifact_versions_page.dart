import 'package:flutter/material.dart';

import '../widgets/artifact_versions_content.dart';
import '../providers/profile_resource.dart';

class ArtifactVersionsPage extends StatelessWidget {
  final ProfileResourceRoute? resource;
  const ArtifactVersionsPage({super.key, this.resource});
  @override
  Widget build(BuildContext context) =>
      ArtifactVersionsContent(resource: resource);
}
