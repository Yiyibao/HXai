"""Produce instance-level audit from current, independently retained lint results."""
from pathlib import Path
import collections
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT/'qa/warnings-review-20261008'
REASONS = {
    'Aligned16KB': ('Dependency binaries flagged by Lint are not 16 KB aligned. The actual packaged replacement passes ELF alignment checks; JNI runtime compatibility on a real 16 KB device remains unverified.',
                    'Dependency AAR has an unaligned ARM64 binary; workspace also builds a replacement with the same soname. Lint analyzes dependency artifacts regardless of which binary the APK packages. Removing terminal code changes retained upstream functionality.',
                    'Inspected CMake termux_pty.cpp, dependency and pickFirst packaging. All 56 packaged native-library entries in the final Debug APK variants pass ELF 16 KB load-segment alignment checks; libtermux.so uses the local aligned build, not the dependency binary. useLegacyPackaging=true compresses and extracts native libraries. See packaged-native-alignment.json; this is not a 16 KB runtime test.',
                    'Verify all shipped ABI binaries and JNI behavior on a 16 KB device, then replace the dependency native artifact with a proven build.'),
    'DiscouragedPrivateApi': ('Future Android may reject the reflection; existing try/catch prevents startup crash, but large rows could exceed the platform CursorWindow limit.',
                            'Startup requests a 32 MiB global cursor window using a private field. A public CursorWindow constructor only sizes an individual cursor; replacing the call would require ownership changes for cursor creation and large-row regression testing.',
                            'Inspected DatabaseUtil, RikkaHubApp and SQLiteConfiguration; Room uses the separate requery helper and migrations remain unchanged.',
                            'Measure large legacy database rows on target devices and migrate cursor creation to supported per-cursor sizing before removing the compatibility fallback.'),
    'ModifierFactoryExtensionFunction': ('API consistency only; callers currently receive a fresh Modifier and chain it with then.',
                                         'These functions are extensions on third-party reorderable scopes needed to access longPressDraggableHandle. Converting to a Modifier receiver requires changing scope propagation across all drag surfaces, beyond a local signature fix.',
                                         'Reviewed both implementations and all caller sites; no state/context retained; existing draggingScale extends Modifier.',
                                         'A focused drag API migration with interaction tests on all list and row consumers.'),
    'IconDuplicates': ('Duplicate foreground and monochrome bitmap bytes increase APK size; current launcher appearance is preserved.',
                       'These resources deliberately serve distinct adaptive-icon layers. Deleting/aliasing without checking every density and Android themed-icon fallback can change launcher identity; no new artwork requested.',
                       'Compared declared foreground/monochrome roles and duplicate file locations; application installation exercised with current icons.',
                       'Provide approved monochrome artwork and verify themed/un-themed launcher behavior across supported densities.'),
    'RtlHardcoded': ('Explicit physical TextAlign.Left/Right stay physical in RTL locales.',
                     'ViewText maps explicit Compose Left/Right to physical gravity; Start/End have separate logical branches. Changing these branches to Start/End would alter the requested text alignment.',
                     'Inspected all six TextAlign branches in setComposeTextStyle; resource direction support is preserved.',
                     'Only change if the product contract removes physical alignment; then verify RTL and explicit left/right text.'),
    'UseKtx': ('Style suggestion; synchronous feedback persistence must report a failed commit.',
               'SharedPreferences.edit(commit=true) returns Unit, whereas FeedbackStorage.record must return the actual commit Boolean and clear must check it. The suggested extension would hide write failures.',
               'Inspected FeedbackStorage, FeedbackLedger and durable feedback process regression; keeps commit failure contract.',
               'An extension/API returning the actual commit result, or a separately tested equivalent wrapper that preserves failure reporting.'),
    'UsableSpace': ('Reported free space excludes potentially reclaimable cache; may understate storage capacity, but the report never reserves storage.',
                    'Diagnostics exports the observed freeStorageBytes metric. getAllocatableBytes estimates capacity and allocateBytes reserves storage; neither is the same measurement. Replacing it changes exported semantics and reservation behavior.',
                    'Inspected export JSON and bounded export files; no preflight allocation decision uses this metric.',
                    'If needed, add a distinct allocatableStorageBytes field with error handling and a schema contract; keep freeStorageBytes semantics.'),
    'Typos': ('No user-facing spelling error in the retained location example.',
              'us-central1 is an actual provider location identifier, not the English word central followed by punctuation. Altering it makes the configuration example invalid.',
              'Inspected provider location resource and retained identifier exactly.',
              'None unless provider location identifiers change; this is a documented analyzer false positive.'),
    'NewerVersionAvailable': ('Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.',
                            'A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.',
                            'Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.',
                            'Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.'),
    'AndroidGradlePluginVersion': ('A newer Gradle distribution is available; current configured build remains reproducible.',
                                  'Wrapper upgrade requires matching AGP/Kotlin/KSP/NDK compatibility checks; user prohibited whole-stack upgrades for warning count.',
                                  'Current wrapper and bundled Gradle are 9.6.0; build tasks executed with existing versions.',
                                  'A planned build-tool upgrade validated with current plugins, native build and supported host platforms.'),
}

def read(path):
    return list(ET.parse(path).getroot())

def locations(issue):
    out = []
    for loc in issue.findall('location'):
        path = loc.get('file').replace('\\','/')
        prefix = str(ROOT).replace('\\','/')+'/'
        if path.startswith(prefix):
            path = path[len(prefix):]
        out.append({'file':path, 'line':loc.get('line'), 'column':loc.get('column')})
    return out

before = read(QA/'before-lint.xml')
after = read(QA/'after-lint.xml')
remaining = []
for index, issue in enumerate(after,1):
    kind = issue.get('id')
    details = REASONS.get(kind, ('Requires review', 'No resolution claimed', 'See source and current report', 'Focused follow-up investigation'))
    remaining.append({'number':index,'type':kind,'severity':issue.get('severity'),
                      'message':issue.get('message'),'locations':locations(issue),
                      'impact':details[0],'reason':details[1],'checks':details[2],'required_conditions':details[3],
                      'status':'retained'})
(QA/'remaining-warnings.json').write_text(json.dumps(remaining, ensure_ascii=False, indent=2), encoding='utf-8')
lines = ['# 每条剩余 Lint 项目', '', '源文件：本轮 after-lint.xml。Hint 独立标明，不计入 warning。', '']
for entry in remaining:
    lines += [f"## {entry['number']}. {entry['type']} ({entry['severity']})", '', entry['message'], '',
              '位置：'+ '; '.join(f"`{l['file']}:{l['line'] or 'binary/resource'}`" for l in entry['locations']), '',
              '影响：'+entry['impact'], '', '保留原因：'+entry['reason'], '',
              '已做检查：'+entry['checks'], '', '解决条件：'+entry['required_conditions'], '']
(QA/'remaining-warnings.md').write_text('\n'.join(lines), encoding='utf-8')
counts = {phase:dict(collections.Counter((i.get('severity') for i in values))) for phase,values in [('before',before),('after',after)]}
types = sorted(set(i.get('id') for i in before+after))
lines = ['# 警告分类与去向', '', '|类型|修复前|修复后|去向|', '|---|---:|---:|---|']
for kind in types:
    a = sum(i.get('id')==kind for i in before)
    b = sum(i.get('id')==kind for i in after)
    lines.append(f"|{kind}|{a}|{b}|{'本轮消除，见构建/复审证据' if not b else '逐项保留说明见 remaining-warnings.md'}|")
(QA/'warning-types.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')

# Instance-level before list keeps original locations even after resource lines move.
destinations=[]
def fingerprint(i):
    return (i.get('id'), i.get('message'), tuple(l['file'] for l in locations(i)))
after_instances=collections.Counter(fingerprint(i) for i in after)
for index,i in enumerate(before,1):
    kind=i.get('id')
    key=fingerprint(i)
    retained=after_instances[key]>0
    if retained:after_instances[key]-=1
    destinations.append({'number':index,'type':kind,'severity':i.get('severity'),'locations':locations(i),
                         'source_excerpt':i.get('errorLine1'), 'message':i.get('message'),
                         'destination':'retained; see exact instance in remaining-warnings.json' if retained else 'resolved in current lint'})
(QA/'before-warning-destinations.json').write_text(json.dumps(destinations,ensure_ascii=False,indent=2),encoding='utf-8')
(QA/'warning-counts.json').write_text(json.dumps(counts,indent=2),encoding='utf-8')
print(counts)
