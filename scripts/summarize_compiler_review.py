"""Preserve exact compiler diagnostics and explain every remaining instance."""
from pathlib import Path
import collections
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / 'qa/warnings-review-20261008'

def warnings(name):
    result = []
    for line in (QA / name).read_text(encoding='utf-8-sig').splitlines():
        if not line.startswith('w:'):
            continue
        match = re.match(r'w: file:///(.*?):(\d+):(\d+) (.*)', line)
        if match:
            absolute, row, column, message = match.groups()
            path = Path(absolute).relative_to(ROOT).as_posix()
            result.append(dict(file=path, line=int(row), column=int(column), message=message, log=name))
        else:
            result.append(dict(file=None, line=None, message=line[3:], log=name))
    return result

def classify(item):
    msg = item['message']
    path = item['file'] or ''
    if path.startswith('client/ai/'):
        return ('旧消息序列化兼容', '弃用类型用于读取历史 ToolCall/ToolResult/Search 消息；直接删除分支可能损坏旧会话和备份。',
                '核对类型 serialName、旧分支及向 UIMessagePart.Tool 的转换；198 项 ai 单元测试通过。',
                '提供版本化旧数据迁移及含旧工具消息的跨版本备份/恢复样例，再移除兼容分支。')
    if 'ListItem(' in msg:
        return ('Material3 列表 API 弃用', '新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。',
                '已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。',
                '逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。')
    if 'ExperimentalCoilApi' in msg:
        return ('Coil 实验性缓存 API', 'CacheControlCacheStrategy 提供现有网络缓存合同；项目依赖版本将其标为实验性。去掉策略会改变缓存/请求行为，添加 opt-in 仅确认风险而非修复，因此没有为清零添加注解。',
                '核对 RouteActivity ImageLoader 的缓存配置与依赖版本；APK 构建及图片视觉链路结果见主报告。',
                '稳定版提供同等 Cache-Control 行为，或有明确缓存合同及请求/离线回归后单独迁移。')
    if 'Experimental Haze' in msg:
        return ('Haze 实验性视觉 API', '聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。',
                '核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。',
                '依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。')
    if 'FlowPreview' in msg:
        return ('协程 Flow 预览 API', '模型列表搜索使用 debounce 的既有节流行为；手写替代会改变取消和调度语义，标记 opt-in 不能消除 API 稳定性风险。',
                '核对 ModelList 的 debounce 与检索消费端；模型选择器页面测试通过。',
                '稳定等价 debounce API，或经过虚拟时钟、取消、快速输入与档案切换测试的局部替代。')
    if 'createAndroidComposeRule' in msg or 'createComposeRule' in msg:
        return ('Compose 仪器测试 API 弃用', 'v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。',
                '编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。',
                '按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。')
    if 'SHOW_IMPLICIT' in msg:
        return ('终端软键盘旧常量', '上游终端仍兼容 API 26；旧常量表示用户间接触发的输入法显示。无条件改为新的输入法合同可能改变键盘行为；共享终端当前未开放。',
                '核对 WorkspaceTerminalSession 的调用位置及入口关闭状态；没有声称真实终端/各输入法回归通过。',
                '终端开放前在 API 26 与目标版本、多种 IME 下验证等价 WindowInsets 控制迁移。')
    if 'unsafeCheckOpNoThrow' in msg:
        return ('UsageStats AppOps API 弃用', 'API 29 起查询 OPSTR_GET_USAGE_STATS 的实际授权状态，旧版本另有 checkOpNoThrow 分支；替代 note/start 系列会增加访问记录或操作语义。',
                '核对 API 分支、UID/package 参数及特殊权限设置入口；未声称 API 26 真机授予/撤销实测通过。',
                '明确新 API 的仅查询合同，并在权限拒绝/授予/撤销及多 Android 版本验证后迁移。')
    if 'connectionInfo' in msg or 'ipAddress' in msg:
        return ('Wi-Fi 连接信息 API 弃用', '当前查询用于本机 Web 服务局域网地址。新版 NetworkCapabilities/LinkProperties 需处理 VPN、蜂窝/Wi-Fi、多网络和回调生命周期，单纯换 getter 不能保证地址同义；Web 服务未开放。',
                '核对 NsdServiceRegistrar 的 IPv4 转换和后备地址；没有开启服务或改变用户连接配置。',
                'Web 服务完成档案边界后，以有线/Wi-Fi/VPN/断网与权限拒绝样例验证网络地址和 NSD 生命周期迁移。')
    raise RuntimeError(f'Unclassified diagnostic: {item}')

final = warnings('final-compiler-full.log')
# Changed production sources were compiled again after the full compiler run.
# Replace matching diagnostics with their final local compile locations/evidence.
for last in warnings('final-privacy-build-tests-lint.log'):
    for index, old in enumerate(final):
        if (old['file'], old['message']) == (last['file'], last['message']):
            final[index] = last
for i, item in enumerate(final, 1):
    kind, reason, checks, conditions = classify(item)
    impact = {
        '旧消息序列化兼容': '当前兼容历史数据；移除旧分支可能使旧会话/备份不可读取。警告本身未证明当前数据损坏。',
        'Material3 列表 API 弃用': '未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。',
        'Coil 实验性缓存 API': '缓存策略接口可能不兼容变更；去掉策略会影响网络缓存，当前未确认因此导致缺陷。',
        'Haze 实验性视觉 API': '视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。',
        '协程 Flow 预览 API': '搜索节流 API 未来可能变更；错误替代会影响快速输入、取消和更新顺序。',
        'Compose 仪器测试 API 弃用': '影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。',
        '终端软键盘旧常量': '未来输入法显示行为可能不同；终端当前未开放，不作为已支持的学习功能。',
        'UsageStats AppOps API 弃用': '未来权限查询 API 行为可能变化，可能误判特殊权限；本轮没有旧版真机授予/撤销证据。',
        'Wi-Fi 连接信息 API 弃用': '未来网络查询受限或返回非预期地址；Web 当前未开放，开放前必须重新验证网络生命周期。',
    }[kind]
    item.update(number=i, type=kind, status='retained', impact=impact, reason=reason,
                checks=checks, required_conditions=conditions,
                current_source_sha256=hashlib.sha256((ROOT / item['file']).read_bytes()).hexdigest())
(QA / 'remaining-compiler-warnings.json').write_text(json.dumps(final, ensure_ascii=False, indent=2), encoding='utf-8')
lines = ['# 剩余编译器警告逐项记录', '', '源证据：final-compiler-full.log 强制重新编译全部依赖及 app/AndroidTest。最后日志隐私修改的 ContextUtil 诊断以 final-privacy-build-tests-lint.log 的实际重编译覆盖；其他警告源文件没有后续改动。', '']
for item in final:
    lines += [f"## {item['number']}. {item['type']}", '', f"位置：`{item['file']}:{item['line']}:{item['column']}`", '',
              '原始诊断：' + item['message'], '', '影响：' + item['impact'], '', '保留原因：' + item['reason'], '',
              '已做检查：' + item['checks'], '', '所需条件：' + item['required_conditions'], '']
(QA / 'remaining-compiler-warnings.md').write_text('\n'.join(lines), encoding='utf-8')
before = warnings('after-build-second.log') + warnings('full-build-tests-lint.log')
remaining_keys = {(x['file'], x['message']) for x in final}
for x in before:
    x['destination'] = 'retained; see remaining compiler ledger' if (x['file'], x['message']) in remaining_keys else 'removed from fresh final compiler output; original failed/intermediate logs retained'
(QA / 'first-compiler-warning-destinations.json').write_text(json.dumps(before, ensure_ascii=False, indent=2), encoding='utf-8')
counts = {'final_total': len(final), 'final_by_type': dict(collections.Counter(x['type'] for x in final)),
          'first_app_compile': len(warnings('after-build-second.log')),
          'first_other_module_compile': len(warnings('full-build-tests-lint.log')),
          'baseline_caveat': 'The first two compiler logs are incremental/intermediate runs (one failed); their 80 and 39 entries are not a single untouched-workspace baseline. Final full compiler log is successful and fresh. AndroidTest warnings previously not fully exposed.'}
(QA / 'compiler-warning-counts.json').write_text(json.dumps(counts, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(counts, ensure_ascii=False))
