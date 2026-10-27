import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/frontend_feature_providers.dart';
import '../providers/study_providers.dart';

final extensionsProvider = FutureProvider<Map<dynamic, dynamic>>((ref) async {
  final owner = ref.watch(activeProfileIdProvider);
  final epoch = ref.watch(contextEpochProvider);
  final value =
      await FrontendFeatures.call<Map<dynamic, dynamic>>(
        ref,
        'extensions.list',
        {'version': 1},
      ) ??
      {};
  return {...value, 'owner': owner, 'epoch': epoch};
});

class ExtensionsPage extends ConsumerWidget {
  const ExtensionsPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.listen(bridgeEventsProvider, (_, next) {
      final event = next.value;
      final nextEpoch = event?['contextEpoch'];
      if (event?['type'] == 'profile_changed' &&
          nextEpoch is int &&
          nextEpoch > ref.read(contextEpochProvider) &&
          (event?['payload'] as Map?)?['newEpoch'] == nextEpoch) {
        ref.invalidate(bootstrapProvider);
        ref.invalidate(extensionsProvider);
      }
    });
    final bootstrap = ref.watch(bootstrapProvider);
    if (bootstrap.isLoading || bootstrap.hasError || !bootstrap.hasValue) {
      return Scaffold(
        appBar: AppBar(title: const Text('MCP 与 Skill 计算服务')),
        body: Center(
          child: Text(bootstrap.hasError ? '档案读取失败，请重新进入' : '正在核对当前档案…'),
        ),
      );
    }
    final identity =
        '${ref.watch(activeProfileIdProvider)}:${ref.watch(contextEpochProvider)}';
    return _ExtensionManager(key: ValueKey(identity));
  }
}

class _ExtensionManager extends ConsumerStatefulWidget {
  const _ExtensionManager({super.key});
  @override
  ConsumerState<_ExtensionManager> createState() => _ExtensionManagerState();
}

class _ExtensionManagerState extends ConsumerState<_ExtensionManager>
    with WidgetsBindingObserver {
  final endpoint = TextEditingController();
  final token = TextEditingController();
  final slot = TextEditingController();
  final config = TextEditingController();
  final issuer = TextEditingController();
  final clientId = TextEditingController();
  final oauthScope = TextEditingController();
  bool storedOAuth = false;
  bool busy = false;
  String? error;
  String protocol = '2025-11-25';
  Map<dynamic, dynamic>? discovered;
  final Set<String> selected = {};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      ref.invalidate(extensionsProvider);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    for (final c in [
      endpoint,
      token,
      slot,
      config,
      issuer,
      clientId,
      oauthScope,
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> perform(Future<void> Function() work) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await work();
    } catch (e) {
      if (mounted) setState(() => error = '$e');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<bool> confirm(String title, String body) async =>
      await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text(title),
          content: SingleChildScrollView(child: Text(body)),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('确认'),
            ),
          ],
        ),
      ) ??
      false;

  Future<void> discover() async {
    if (!await confirm(
      '连接此服务器并发现工具',
      '${endpoint.text.trim()}\n仅发送协议、应用标识和此服务专用凭据。发现不会授予工具权限。',
    )) {
      return;
    }
    if (!mounted) return;
    await perform(() async {
      final value = await FrontendFeatures.fromWidget<Map<dynamic, dynamic>>(
        ref,
        'extensions.probe',
        {
          'version': 1,
          'endpoint': endpoint.text.trim(),
          'token': token.text,
          'protocol': protocol,
          'slot': slot.text.trim(),
          'useStoredCredential': storedOAuth,
        },
      );
      if (!mounted) return;
      setState(() {
        discovered = value;
        selected.clear();
        config.clear();
      });
    });
  }

  void prepareConfig() {
    final tools = (discovered?['tools'] as List? ?? []).cast<Map>();
    config.text = const JsonEncoder.withIndent('  ').convert({
      'version': 1,
      'name': slot.text.trim(),
      'endpoint': endpoint.text.trim(),
      'serverName': discovered?['serverName'],
      'serverVersion': discovered?['serverVersion'],
      'protocol': protocol,
      'tools': tools
          .where((t) => selected.contains(t['name']))
          .map(
            (t) => {
              'name': t['name'],
              'schema': jsonDecode(t['schema'] as String),
              'scopes': [],
              if (t['name'] == 'run_python' &&
                  discovered?['serverName'] == 'hxai-skill-sandbox')
                'imageSha256': discovered?['sandboxImage'],
              'verification':
                  t['name'] == 'run_python' &&
                      discovered?['serverName'] == 'hxai-skill-sandbox'
                  ? 'sandbox_receipt'
                  : 'consistent_read',
            },
          )
          .toList(),
    });
    setState(() {});
  }

  Future<void> install(List<Map> items) async {
    final parsed = jsonDecode(config.text) as Map;
    final previous = items
        .where((p) => p['slot'] == slot.text.trim())
        .firstOrNull;
    if (!await confirm(
      '安装并保存逐工具许可',
      '${parsed['endpoint']}\n工具：${(parsed['tools'] as List).map((t) => t['name']).join(', ')}\n'
          '配置中的参数范围为许可边界。只读工具将多次独立复读，可能产生额外请求；不能选发送、删除或其他写入工具。'
          'Skill 计算仅由所选独立服务执行。安装后保持停用，启用和每个任务的实际外发仍需确认。',
    )) {
      return;
    }
    if (!mounted) return;
    await perform(() async {
      await FrontendFeatures.fromWidget(ref, 'extensions.install', {
        'version': 1,
        'slot': slot.text.trim(),
        'revision': previous?['revision'] ?? 0,
        'config': config.text,
        'token': token.text,
        'useStoredCredential': storedOAuth,
      });
      if (!mounted) return;
      token.clear();
      ref.invalidate(extensionsProvider);
    });
  }

  Future<void> connectOAuth(List<Map> items) async {
    if (!await confirm(
      '发现 OAuth 授权服务',
      '${endpoint.text.trim()}\n发行者：${issuer.text.trim()}\n只获取公开元数据。随后将展示实际授权端点和范围，尚不启用工具。',
    )) {
      return;
    }
    if (!mounted) return;
    await perform(() async {
      final result = await FrontendFeatures.fromWidget<Map>(
        ref,
        'extensions.oauth.discover',
        {
          'version': 1,
          'endpoint': endpoint.text.trim(),
          'issuer': issuer.text.trim(),
        },
      );
      if (!mounted) return;
      final metadata = jsonDecode(result!['metadata'] as String) as Map;
      if (!await confirm(
        '在系统浏览器授权',
        '资源：${metadata['resource']}\n发行者：${metadata['issuer']}\n授权：${metadata['authorizationEndpoint']}\n凭据兑换：${metadata['tokenEndpoint']}\n回调：${metadata['redirectUri']}\n范围：${oauthScope.text}\n此为公开客户端 PKCE 授权。凭据只用于此服务；工具仍保持停用。',
      )) {
        return;
      }
      if (!mounted) return;
      final previous = items
          .where((p) => p['slot'] == slot.text.trim())
          .firstOrNull;
      final flow = await FrontendFeatures.fromWidget<Map>(
        ref,
        'extensions.oauth.begin',
        {
          'version': 1,
          'slot': slot.text.trim(),
          'revision': previous?['revision'] ?? 0,
          'metadata': result['metadata'],
          'clientId': clientId.text.trim(),
          'scope': oauthScope.text.trim(),
        },
      );
      if (!mounted) return;
      ref.invalidate(extensionsProvider);
      await FrontendFeatures.fromWidget(ref, 'extensions.oauth.open', {
        'version': 1,
        'flow': flow!['flow'],
      });
    });
  }

  Future<void> finishOAuth(Map flow) async {
    await perform(() async {
      final value = await FrontendFeatures.fromWidget<Map>(
        ref,
        'extensions.oauth.complete',
        {'version': 1, 'flow': flow['flow']},
      );
      if (!mounted) return;
      final c = jsonDecode(value!['config'] as String) as Map;
      slot.text = value['slot'] as String;
      endpoint.text = c['endpoint'] as String;
      token.clear();
      setState(() {
        storedOAuth = true;
        discovered = null;
        selected.clear();
        config.clear();
      });
      ref.invalidate(extensionsProvider);
    });
  }

  Future<void> command(Map item, String action) async {
    if (!await confirm(
      '确认${{'enable': '启用', 'disable': '停用', 'revoke': '撤权', 'uninstall': '卸载'}[action]}',
      '${item['slot']}\n${action == 'enable' ? '允许发现配置中的工具；实际外发仍需任务审批。' : '阻止后续请求，保留任务、成果和未知结果记录。已发送的请求无法撤回。'}',
    )) {
      return;
    }
    if (!mounted) return;
    await perform(() async {
      await FrontendFeatures.fromWidget(ref, 'extensions.command', {
        'version': 1,
        'slot': item['slot'],
        'id': item['id'],
        'revision': item['revision'],
        'action': action,
      });
      if (mounted) ref.invalidate(extensionsProvider);
    });
  }

  @override
  Widget build(BuildContext context) {
    final data = ref.watch(extensionsProvider);
    final current =
        !data.isLoading &&
        !data.hasError &&
        data.value?['owner'] == ref.watch(activeProfileIdProvider) &&
        data.value?['epoch'] == ref.watch(contextEpochProvider);
    final visible = current ? data.value : null;
    final items = (visible?['installations'] as List? ?? []).cast<Map>();
    return Scaffold(
      appBar: AppBar(
        title: const Text('MCP 与 Skill 计算服务'),
        actions: [
          IconButton(
            tooltip: '刷新持久状态',
            onPressed: busy ? null : () => ref.invalidate(extensionsProvider),
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const Text(
              '按档案管理服务和逐工具许可。只读工具可独立复读；Python Skill 由独立服务器沙箱运行。服务描述不能扩大权限。',
            ),
            if (busy) const LinearProgressIndicator(),
            if (error != null)
              Text(
                error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            data.when(
              loading: () => const Text('正在读取持久状态…'),
              error: (e, _) => Text('读取失败：$e'),
              data: (_) => Column(
                children: items.map((item) {
                  final c = jsonDecode(item['config'] as String) as Map;
                  return Card(
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '${c['name']} · ${item['state']}',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          SelectableText(
                            '${c['endpoint']}\n版本 ${c['serverVersion']} · 授权版本 ${item['grantEpoch']}',
                          ),
                          Text(
                            '工具：${(c['tools'] as List).map((t) => t['name']).join(', ')}',
                          ),
                          Text('连接：${item['authStatus'] ?? '未知'}'),
                          Wrap(
                            spacing: 8,
                            children: [
                              if (item['state'] == 'DISABLED' &&
                                  (c['tools'] as List).isNotEmpty &&
                                  item['authStatus'] != 'OAUTH_EXPIRED')
                                TextButton(
                                  onPressed: busy
                                      ? null
                                      : () => command(item, 'enable'),
                                  child: const Text('启用'),
                                ),
                              if (item['state'] == 'ENABLED')
                                TextButton(
                                  onPressed: busy
                                      ? null
                                      : () => command(item, 'disable'),
                                  child: const Text('停用'),
                                ),
                              if ([
                                'ENABLED',
                                'DISABLED',
                              ].contains(item['state']))
                                TextButton(
                                  onPressed: busy
                                      ? null
                                      : () => command(item, 'revoke'),
                                  child: const Text('撤权'),
                                ),
                              if (item['state'] != 'UNINSTALLED')
                                TextButton(
                                  onPressed: busy
                                      ? null
                                      : () => command(item, 'uninstall'),
                                  child: const Text('卸载'),
                                ),
                              TextButton(
                                onPressed: busy || item['state'] == 'ENABLED'
                                    ? null
                                    : () {
                                        slot.text = item['slot'] as String;
                                        endpoint.text = c['endpoint'] as String;
                                        protocol = c['protocol'] as String;
                                        config.text = item['config'] as String;
                                        token.clear();
                                        storedOAuth =
                                            c['oauth'] != null &&
                                            item['authStatus'] ==
                                                'OAUTH_CONNECTED';
                                        setState(() {
                                          discovered = null;
                                          selected.clear();
                                        });
                                      },
                                child: const Text('编辑 / 重新安装'),
                              ),
                            ],
                          ),
                          ExpansionTile(
                            title: const Text('查看已确认参数范围'),
                            children: [
                              SelectableText(
                                const JsonEncoder.withIndent('  ').convert(c),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  );
                }).toList(),
              ),
            ),
            const Divider(),
            for (final flow
                in (visible?['oauthFlows'] as List? ?? []).cast<Map>())
              ListTile(
                title: Text('OAuth · ${flow['slot']} · ${flow['status']}'),
                subtitle: Text(
                  flow['status'] == 'EXCHANGING'
                      ? '兑换结果未知；不会自动重放。请重新授权。'
                      : '返回应用后刷新状态；授权不会自动启用工具。',
                ),
                trailing: flow['status'] == 'CALLBACK'
                    ? TextButton(
                        onPressed: busy ? null : () => finishOAuth(flow),
                        child: const Text('完成连接'),
                      )
                    : null,
              ),
            const Text('添加或更新服务'),
            TextField(
              controller: slot,
              enabled: !busy,
              onChanged: (_) => setState(() => storedOAuth = false),
              decoration: const InputDecoration(
                labelText: '安装标识（字母、数字、下划线或连字符）',
              ),
            ),
            TextField(
              controller: endpoint,
              enabled: !busy,
              onChanged: (_) => setState(() {
                storedOAuth = false;
                discovered = null;
              }),
              decoration: const InputDecoration(labelText: 'HTTPS MCP 精确地址'),
            ),
            TextField(
              controller: token,
              enabled: !busy,
              onChanged: (_) => setState(() => storedOAuth = false),
              obscureText: true,
              enableSuggestions: false,
              autocorrect: false,
              decoration: const InputDecoration(
                labelText: '此服务专用 Bearer（可选；更新时重新输入）',
              ),
            ),
            SwitchListTile(
              title: const Text('使用此安装已授权的 OAuth 凭据'),
              value: storedOAuth,
              onChanged: busy
                  ? null
                  : (value) => setState(() => storedOAuth = value),
            ),
            ExpansionTile(
              title: const Text('OAuth 浏览器连接'),
              children: [
                const Text(
                  '填写服务提供的发行者和已注册的公开客户端 ID。需要密钥的机密客户端不适用于手机端。现有安装需先停用；返回后点“完成连接”，再发现工具并保存范围。',
                ),
                TextField(
                  controller: issuer,
                  enabled: !busy,
                  decoration: const InputDecoration(
                    labelText: 'HTTPS OAuth issuer',
                  ),
                ),
                TextField(
                  controller: clientId,
                  enabled: !busy,
                  decoration: const InputDecoration(labelText: '公开客户端 ID'),
                ),
                TextField(
                  controller: oauthScope,
                  enabled: !busy,
                  decoration: const InputDecoration(
                    labelText: '所需 scope（空格分隔；以服务说明为准）',
                  ),
                ),
                FilledButton(
                  onPressed: busy || !current
                      ? null
                      : () => connectOAuth(items),
                  child: const Text('确认发现并连接 OAuth'),
                ),
              ],
            ),
            DropdownButtonFormField<String>(
              isExpanded: true,
              initialValue: protocol,
              items: [
                '2025-11-25',
                '2025-06-18',
                '2025-03-26',
              ].map((v) => DropdownMenuItem(value: v, child: Text(v))).toList(),
              onChanged: busy ? null : (v) => setState(() => protocol = v!),
              decoration: const InputDecoration(labelText: '协议版本'),
            ),
            FilledButton(
              onPressed: busy ? null : discover,
              child: const Text('连接并发现工具'),
            ),
            if (discovered != null) ...[
              Text(
                '服务声明：${discovered!['serverName']} ${discovered!['serverVersion']}',
              ),
              const Text(
                '仅选择你确认可安全重复读取的工具，或已部署沙箱的 run_python。发送、删除、付款等工具需专属验收适配器。',
              ),
              for (final t in (discovered!['tools'] as List).cast<Map>())
                CheckboxListTile(
                  value: selected.contains(t['name']),
                  title: Text(t['name'] as String),
                  subtitle: const Text('确认允许独立复读；沙箱执行只读回同一回执'),
                  onChanged: busy
                      ? null
                      : (v) => setState(() {
                          if (v == true) {
                            selected.add(t['name'] as String);
                          } else {
                            selected.remove(t['name']);
                          }
                        }),
                ),
              OutlinedButton(
                onPressed: busy || selected.isEmpty ? null : prepareConfig,
                child: const Text('生成待确认配置'),
              ),
            ],
            const Text(
              '参数范围 scopes 可指定 pointer/kind/values；kind 支持 exact、directory、origin。未声明文件映射时，服务不能读取手机文件。',
            ),
            TextField(
              controller: config,
              enabled: !busy,
              minLines: 5,
              maxLines: 15,
              decoration: const InputDecoration(
                labelText: '逐工具 schema 与参数范围（JSON）',
              ),
            ),
            FilledButton(
              onPressed: busy || data.isLoading || data.hasError
                  ? null
                  : () => install(items),
              child: const Text('确认安装配置'),
            ),
            ExpansionTile(
              title: const Text('管理记录（最近100条）'),
              children: [
                for (final e in visible?['events'] as List? ?? [])
                  ListTile(
                    title: Text('${e['slot']} · ${e['action']}'),
                    subtitle: Text(
                      '版本 ${e['revision']} · ${DateTime.fromMillisecondsSinceEpoch(e['time'] as int).toLocal()}',
                    ),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
