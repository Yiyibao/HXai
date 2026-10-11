"""Apply narrowly scoped fixes using this run's lint locations; retain resource keys."""
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / 'qa/warnings-review-20261008'

def edit(relative, old, new):
    path = ROOT / relative
    text = path.read_text(encoding='utf-8')
    assert old in text, (relative, old)
    path.write_text(text.replace(old, new), encoding='utf-8', newline='')

base = 'client/app/src/main/java/me/rerere/rikkahub/'
edit(base+'utils/NotificationUtil.kt', '        return ActivityCompat.checkSelfPermission(',
     '        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {\n            return NotificationManagerCompat.from(context).areNotificationsEnabled()\n        }\n        return ActivityCompat.checkSelfPermission(')
edit(base+'ui/pages/backup/BackupVM.kt', 'discardEncryptedRestore(); super.onCleared()', 'discardEncryptedRestore()')
edit(base+'ui/pages/chat/ChatVM.kt', '        super.onCleared()\n', '')
edit(base+'ui/components/table/DataTable.kt', 'IntArray(columnCount) { 0 }', 'IntArray(columnCount)')
for relative, primitive, old in [
    ('ui/components/easteregg/EmojiBurst.kt', 'Long', 'mutableStateOf(0L)'),
    ('ui/pages/setting/SettingPreferencesNotificationPage.kt', 'Int', 'mutableStateOf(UPDATE_PAUSE_DAY_OPTIONS.first())'),
]:
    path = ROOT / (base+relative)
    text = path.read_text(encoding='utf-8')
    text = text.replace('import androidx.compose.runtime.mutableStateOf', f'import androidx.compose.runtime.mutable{primitive}StateOf\nimport androidx.compose.runtime.mutableStateOf')
    text = text.replace(old, old.replace('mutableStateOf', f'mutable{primitive}StateOf'))
    path.write_text(text, encoding='utf-8', newline='')
edit(base+'ui/components/ui/Switch.kt', 'import androidx.compose.ui.unit.dp', 'import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.unit.IntOffset\nimport androidx.compose.ui.platform.LocalDensity')
edit(base+'ui/components/ui/Switch.kt', '    val currentTrackColor = when {', '    val density = LocalDensity.current\n    val currentTrackColor = when {')
edit(base+'ui/components/ui/Switch.kt', '.offset(x = thumbOffset)', '.offset { IntOffset(with(density) { thumbOffset.roundToPx() }, 0) }')
edit(base+'feature/material/MaterialPdfPages.kt', 'import androidx.core.net.toUri', 'import androidx.core.net.toUri\nimport androidx.core.graphics.createBitmap')
edit(base+'feature/material/MaterialPdfPages.kt', 'Bitmap.createBitmap(', 'createBitmap(')

# Keep only the directory for background writes, rather than any Android context.
p = ROOT / (base+'feature/diagnostics/Diagnostics.kt')
t = p.read_text(encoding='utf-8')
t = t.replace('private var app: Context? = null', '@Volatile private var eventDirectory: File? = null')
t = t.replace('app = application.applicationContext', 'eventDirectory = directory(application)')
t = t.replace('val context = app ?: return', 'val root = eventDirectory ?: return')
t = t.replace('append(context, event)', 'append(root, event)')
t = t.replace('private fun read(context: Context, name: String): JSONObject = runCatching {',
    'private fun read(context: Context, name: String): JSONObject = read(directory(context), name)\n\n    private fun read(root: File, name: String): JSONObject = runCatching {')
t = t.replace('private fun write(context: Context, name: String, value: JSONObject) {',
    'private fun write(context: Context, name: String, value: JSONObject) = write(directory(context), name, value)\n\n    private fun write(root: File, name: String, value: JSONObject) {')
t = t.replace('val atomic = AtomicFile(File(directory(context), name))', 'val atomic = AtomicFile(File(root, name))')
t = t.replace('private fun append(context: Context, event: JSONObject)', 'private fun append(root: File, event: JSONObject)')
t = t.replace('val old = read(context, "events.json")', 'val old = read(root, "events.json")')
t = t.replace('write(context, "events.json", JSONObject().put("events", events))', 'write(root, "events.json", JSONObject().put("events", events))')
p.write_text(t, encoding='utf-8', newline='')

# Give the in-flight browser response a graceful drain before stopping the listener.
edit('client/oauth/src/main/java/me/rerere/oauth/OAuthLoopbackCallbackServer.kt',
     'server?.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)',
     'server?.stopSuspend(gracePeriodMillis = 1_000, timeoutMillis = 5_000)')

issues = ET.parse(QA/'before-lint.xml').getroot()
by_file = {}
for issue in issues:
    if issue.get('id') in ('TypographyEllipsis','TypographyDashes','Typos'):
        loc = issue.find('location')
        by_file.setdefault(Path(loc.get('file')), []).append((int(loc.get('line'))-1, issue.get('id')))
for path, entries in by_file.items():
    lines = path.read_text(encoding='utf-8').splitlines(keepends=True)
    for line, kind in entries:
        # Change only display text, never identifiers, URLs or machine-readable examples.
        start = lines[line].index('>')+1
        text = lines[line][start:]
        if kind == 'TypographyEllipsis':
            text = text.replace('...', '…')
        elif kind == 'TypographyDashes':
            text = re.sub(r'(?<=\d)\s*-\s*(?=\d)', '–', text)
        elif 'Avaliable' in text:
            text = text.replace('Avaliable', 'Available')
        lines[line] = lines[line][:start]+text
    path.write_text(''.join(lines), encoding='utf-8', newline='')

# These bitmaps are logos/placeholders with deliberate pixel sizes, not mdpi icons.
for issue in issues:
    if issue.get('id') == 'IconLocation':
        path = Path(issue.find('location').get('file'))
        target = path.parent.with_name('drawable-nodpi') / path.name
        assert not target.exists()
        target.parent.mkdir(exist_ok=True)
        shutil.move(path, target)
for source_set in ('main','debug'):
    source = ROOT / f'client/app/src/{source_set}/res/mipmap-anydpi-v26'
    target = source.with_name('mipmap-anydpi')
    for path in source.iterdir():
        assert not (target/path.name).exists()
        target.mkdir(exist_ok=True)
        shutil.move(path, target/path.name)
    source.rmdir()

# Lint plus repository-wide search confirm these resources have no consumer.
# Remove resource entries in all locales; preserve every supported feature.
unused = []
import subprocess
sources = []
for value in subprocess.check_output(['rg', '--files', str(ROOT/'client')], text=True).splitlines():
    source = Path(value)
    if source.suffix not in ('.kt','.java','.xml','.html','.js','.ts','.tsx'):
        continue
    if source.suffix == '.xml' and 'res' in source.parts:
        continue
    sources.append((source, source.read_text(encoding='utf-8', errors='replace')))
for issue in issues:
    if issue.get('id') != 'UnusedResources':
        continue
    kind, name = re.search(r'R\.(\w+)\.(\w+)', issue.get('message')).groups()
    references = []
    for source, content in sources:
        if re.search(r'\b'+re.escape(name)+r'\b', content):
            references.append(str(source.relative_to(ROOT)))
    if references:
        unused.append({'name':name,'action':'retained','references':references})
        continue
    if kind in ('string','color'):
        for path in (ROOT/'client/app/src').glob('*/res/values*/*.xml'):
            text = path.read_text(encoding='utf-8')
            changed = re.sub(r'^\s*<'+kind+r' name="'+name+r'"[^>]*>.*?</'+kind+r'>\r?\n', '', text, flags=re.M)
            if changed != text:
                path.write_text(changed, encoding='utf-8', newline='')
    elif kind == 'drawable':
        Path(issue.find('location').get('file')).unlink(missing_ok=True)
    unused.append({'name':name,'action':'removed','references':[]})
import json
(QA/'unused-resource-audit.json').write_text(json.dumps(unused, ensure_ascii=False, indent=2), encoding='utf-8')

# Migrate count strings to plurals and use the actual quantity at each call site.
plural_names = {'assistant_page_max_tokens_limit','backup_page_reminder_interval_days',
                'backup_page_reminder_last_days','chain_of_thought_show_more_steps',
                'chat_message_tool_ask_questions','chat_message_tool_search_results_count',
                'chat_page_message_count','citations_count','prompt_page_entries_count_format',
                'setting_files_page_clean_older_than_days','setting_page_chat_storage_desc',
                'setting_provider_page_model_count','workspace_detail_extracting',
                'tool_ui_list_files_items','setting_update_reminder_pause_days'}
singular_words = {'tokens':'token','days':'day','steps':'step','questions':'question','results':'result',
                  'messages':'message','Citations':'Citation','entries':'entry','files':'file','models':'model','items':'item'}
for path in (ROOT/'client/app/src/main/res').glob('values*/strings.xml'):
    text = path.read_text(encoding='utf-8')
    for name in plural_names:
        pattern = r'<string name="'+name+r'"[^>]*>(.*?)</string>'
        def convert(match):
            other = match[1]
            one = other
            if path.parent.name == 'values':
                for word, replacement in singular_words.items():
                    one = re.sub(r'\b'+word+r'\b', replacement, one)
            return f'<plurals name="{name}">\n    <item quantity="one">{one}</item>\n    <item quantity="other">{other}</item>\n  </plurals>'
        text = re.sub(pattern, convert, text)
    path.write_text(text, encoding='utf-8', newline='')
for path in (ROOT/'client/app/src').rglob('*.kt'):
    text = path.read_text(encoding='utf-8')
    changed = False
    for name in plural_names:
        # Counts at these call sites are identifiers, member accesses or subtraction.
        pattern = r'stringResource\(\s*R.string.'+name+r',\s*([^,\)]+)'
        def replace(match):
            nonlocal_dummy = None
            count = match[1].strip()
            quantity = count+'!!' if name == 'assistant_page_max_tokens_limit' else count
            return f'pluralStringResource(R.plurals.{name}, {quantity}, {count}'
        new = re.sub(pattern, replace, text)
        changed |= new != text
        text = new
    if changed:
        text = text.replace('import androidx.compose.ui.res.stringResource', 'import androidx.compose.ui.res.pluralStringResource\nimport androidx.compose.ui.res.stringResource')
        path.write_text(text, encoding='utf-8', newline='')
