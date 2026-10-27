"""Deterministic guarded local source edit; preserve inherited working-tree bytes in before/."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def edit(path,changes):
    p=ROOT/path
    s=p.read_text(encoding='utf-8')
    for a,b in changes:
        if s.count(a)!=1: raise RuntimeError(f'{path}: anchor count {s.count(a)} for {a[:100]}')
        s=s.replace(a,b)
    p.write_text(s,encoding='utf-8',newline='\n')

p='client/flutter_ui/pigeons/study_host_api.dart'
s=(ROOT/p).read_text(encoding='utf-8')
for name in ['AgentUsageEntryDto','AgentUsagePageDto','AgentUsageGroupDto','AgentUsageGroupsPageDto']:
    start=s.index('class '+name+' {')
    end=s.index('\n}',start)
    part=s[start:end]
    ctor=part.index('});')
    part=part[:ctor]+', this.costJson'+part[ctor:]
    part+='\n  final String? costJson;'
    s=s[:start]+part+s[end:]
(ROOT/p).write_text(s,encoding='utf-8',newline='\n')
edit('client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt',[
 ('cachedTokens=item.cachedTokens,totalTokens=item.totalTokens) },nextCursor=page.nextCursor)',
  'cachedTokens=item.cachedTokens,totalTokens=item.totalTokens,costJson=item.costJson) },nextCursor=page.nextCursor,costJson=page.costJson)'),
 ('input=metric(item.input),output=metric(item.output),cached=metric(item.cached),total=metric(item.total)) },\n                nextCursor=page.nextCursor)',
  'input=metric(item.input),output=metric(item.output),cached=metric(item.cached),total=metric(item.total),costJson=item.costJson) },\n                nextCursor=page.nextCursor,costJson=page.costJson)')])
edit('client/flutter_ui/lib/widgets/agent_usage_view.dart',[
 ("import 'dart:async';","import 'dart:async';\nimport 'dart:convert';"),
 ("  String _newExportId() {",'''  String _cost(String? encoded) {
    if (encoded == null) return '费用未知';
    try {
      final data = jsonDecode(encoded) as Map;
      if (data['amount'] is String && data['currency'] is String) {
        return "费用：${data['currency']} ${data['amount']} · ${data['status'] == 'reported' ? '供应商报告' : '价格估算'}";
      }
      final totals = (data['totals'] as List? ?? []).cast<Map>();
      final parts = totals.map((v) => "${v['currency']} ${v['amount']}（${v['provenance'] == 'reported' ? '报告' : '估算'}）").toList();
      if (data['unknownRequests'] != '0') parts.add("${data['unknownRequests'] ?? '?'} 次费用未知");
      return parts.isEmpty ? '暂无可计金额' : '费用：${parts.join(' · ')}';
    } catch (_) { return '费用读取失败'; }
  }

  String _newExportId() {'''),
 ("周期按设备时区；展示实际请求账本。已知小计不能当完整账单。费用未知：当前账本没有供应商费用来源。","周期按设备时区；展示实际请求账本。费用区分供应商报告、价格估算与未知，已知小计不能当完整账单。"),
 ("可在预览页另存，费用保持未知。","可在预览页另存，包含当时的费用来源与未知覆盖。"),
 ("            if (page.requestCount == 0) const Text('此范围没有请求记录。'),", "            Text(_cost(page.costJson)),\n            if (page.requestCount == 0) const Text('此范围没有请求记录。'),"),
 ("'请求：${entry.requestId}\\n任务：${entry.taskId}',", "'请求：${entry.requestId}\\n任务：${entry.taskId}',"),
 ("            if (grouped.groupCount == 0) const Text('此范围没有分组记录。'),", "            Text(_cost(grouped.costJson)),\n            if (grouped.groupCount == 0) const Text('此范围没有分组记录。'),"),
 ("                      Text(_metric('总 Token', item.total)),", "                      Text(_metric('总 Token', item.total)),\n                      Text(_cost(item.costJson)),")])
p=ROOT/'client/flutter_ui/lib/widgets/agent_usage_view.dart'
s=p.read_text(encoding='utf-8')
anchor="'请求：${entry.requestId}\\n任务：${entry.taskId}',\n                      ),"
assert s.count(anchor)==1
s=s.replace(anchor,anchor+'\n                      Text(_cost(entry.costJson)),\n                      if (entry.costJson != null) ExpansionTile(title: const Text(\'费用来源\'), children: [SelectableText(const JsonEncoder.withIndent(\'  \').convert(jsonDecode(entry.costJson!)))]),')
p.write_text(s,encoding='utf-8',newline='\n')
