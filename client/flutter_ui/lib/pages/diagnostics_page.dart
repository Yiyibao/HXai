import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../bridge/study_host_api.g.dart';
import '../providers/study_providers.dart';
import '../providers/frontend_feature_providers.dart';
import '../theme/study_theme.dart';

class DiagnosticsPage extends ConsumerStatefulWidget {
  const DiagnosticsPage({super.key});

  @override
  ConsumerState<DiagnosticsPage> createState() => _DiagnosticsPageState();
}

class _DiagnosticsPageState extends ConsumerState<DiagnosticsPage> {
  bool _exporting = false;

  Future<void> _previewDiagnostics(DiagnosticsDto diag) async {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('诊断日志预览'),
        content: SingleChildScrollView(
          child: Text(
            diag.logSummary.isNotEmpty ? diag.logSummary : '暂无日志记录。',
            style: const TextStyle(fontSize: 12, fontFamily: 'monospace'),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('关闭'),
          ),
        ],
      ),
    );
  }

  Future<void> _exportDiagnostics() async {
    setState(() => _exporting = true);
    try {
      final res = await bridgeApi.exportDiagnostics();
      checkCommand(res);
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('诊断包已脱敏并导出至本地存储')));
      }
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('导出失败: $e')));
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final diagAsync = ref.watch(diagnosticsProvider);
    final docConfig = ref.watch(documentConfigProvider).value;

    return Scaffold(
      backgroundColor: StudyPalette.of(context).bgWhite,
      appBar: AppBar(
        backgroundColor: StudyPalette.of(context).bgWhite,
        elevation: 0,
        leading: IconButton(
          icon: Icon(
            Icons.arrow_back,
            color: StudyPalette.of(context).textBlack,
          ),
          onPressed: () => context.pop(),
        ),
        title: Text(
          '运行诊断',
          style: TextStyle(
            color: StudyPalette.of(context).textBlack,
            fontSize: 18,
            fontWeight: FontWeight.w600,
          ),
        ),
        centerTitle: true,
      ),
      body: SafeArea(
        child: diagAsync.when(
          loading: () => Center(
            child: CircularProgressIndicator(
              strokeWidth: 2,
              color: StudyPalette.of(context).textBlack,
            ),
          ),
          error: (err, _) => Center(
            child: Text(
              '加载诊断信息失败: $err',
              style: TextStyle(color: StudyPalette.of(context).textGray),
            ),
          ),
          data: (diag) {
            final appVer = diag.appVersion.isNotEmpty
                ? diag.appVersion
                : 'v1.0.0';
            final osEnv = '${diag.deviceModel} (${diag.osVersion})';
            final serviceStatus = docConfig == null
                ? '配置状态未读取'
                : docConfig['enabled'] == true
                ? '已启用 · 尚未测试'
                : '尚未启用';
            final recentError = diag.recentCrashCount > 0
                ? '近 24 小时内有 ${diag.recentCrashCount} 次异常'
                : '暂无异常记录';

            return Column(
              children: [
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 12,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Diagnostic items card
                        Text(
                          '系统与运行环境',
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: StudyPalette.of(context).textBlack,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Container(
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: StudyPalette.of(context).bgWhite,
                            borderRadius: BorderRadius.circular(14),
                            border: Border.all(
                              color: StudyPalette.of(context).borderGray,
                              width: 0.8,
                            ),
                          ),
                          child: Column(
                            children: [
                              _buildDiagRow('客户端版本', appVer),
                              Divider(
                                height: 18,
                                color: StudyPalette.of(context).borderGray,
                              ),
                              _buildDiagRow('系统环境', osEnv),
                              Divider(
                                height: 18,
                                color: StudyPalette.of(context).borderGray,
                              ),
                              _buildDiagRow('文档服务配置状态', serviceStatus),
                              Divider(
                                height: 18,
                                color: StudyPalette.of(context).borderGray,
                              ),
                              _buildDiagRow(
                                '数据库版本',
                                'Revision ${diag.databaseRevision}',
                              ),
                              Divider(
                                height: 18,
                                color: StudyPalette.of(context).borderGray,
                              ),
                              _buildDiagRow('最近错误', recentError),
                            ],
                          ),
                        ),
                        const SizedBox(height: 28),

                        // Export Diagnostic Section
                        Text(
                          '导出诊断信息',
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: StudyPalette.of(context).textBlack,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Container(
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: StudyPalette.of(context).surfaceBackground,
                            borderRadius: BorderRadius.circular(14),
                            border: Border.all(
                              color: StudyPalette.of(context).borderGray,
                              width: 0.8,
                            ),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                '导出前自动移除密钥与敏感内容',
                                style: TextStyle(
                                  fontSize: 13,
                                  fontWeight: FontWeight.w500,
                                  color: StudyPalette.of(context).textBlack,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                '诊断包仅包含脱敏后的客户端运行日志与环境元数据，可用于问题排查与技术支持。',
                                style: TextStyle(
                                  fontSize: 11,
                                  color: StudyPalette.of(context).textGray,
                                  height: 1.4,
                                ),
                              ),
                              const SizedBox(height: 16),
                              Row(
                                children: [
                                  Expanded(
                                    child: OutlinedButton(
                                      style: OutlinedButton.styleFrom(
                                        foregroundColor: StudyPalette.of(
                                          context,
                                        ).textBlack,
                                        padding: const EdgeInsets.symmetric(
                                          vertical: 12,
                                        ),
                                        side: BorderSide(
                                          color: StudyPalette.of(context)
                                              .borderGray,
                                        ),
                                        shape: RoundedRectangleBorder(
                                          borderRadius: BorderRadius.circular(
                                            10,
                                          ),
                                        ),
                                      ),
                                      onPressed: () =>
                                          _previewDiagnostics(diag),
                                      child: const Text('预览诊断内容'),
                                    ),
                                  ),
                                  const SizedBox(width: 12),
                                  Expanded(
                                    child: ElevatedButton(
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor: StudyPalette.of(
                                          context,
                                        ).textBlack,
                                        foregroundColor: StudyPalette.of(
                                          context,
                                        ).onAccent,
                                        padding: const EdgeInsets.symmetric(
                                          vertical: 12,
                                        ),
                                        shape: RoundedRectangleBorder(
                                          borderRadius: BorderRadius.circular(
                                            10,
                                          ),
                                        ),
                                      ),
                                      onPressed: _exporting
                                          ? null
                                          : _exportDiagnostics,
                                      child: _exporting
                                          ? const SizedBox(
                                              width: 16,
                                              height: 16,
                                              child: CircularProgressIndicator(
                                                strokeWidth: 2,
                                                color: Colors.white,
                                              ),
                                            )
                                          : const Text('导出诊断包'),
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 24),
                      ],
                    ),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _buildDiagRow(String label, String value) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          label,
          style: TextStyle(
            fontSize: 13,
            color: StudyPalette.of(context).textGray,
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            value,
            textAlign: TextAlign.end,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w500,
              color: StudyPalette.of(context).textBlack,
            ),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ],
    );
  }
}
