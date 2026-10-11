"""Fix proven local compiler issues; do not opt out of warnings or accept new APIs."""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
base=ROOT/'client/app/src/main/java/me/rerere/rikkahub'
changes={
 'data/datastore/PreferencesStore.kt': [('selectedTTSProviderId?.let','selectedTTSProviderId.let')],
 'data/repository/ConversationRepository.kt': [('stored!!.queueState','stored.queueState'),('folder.assistantId == conversation?.assistantId','folder.assistantId == conversation.assistantId')],
 'data/sync/webdav/WebDavClient.kt': [('currentHref!!.trimEnd','currentHref.trimEnd'),('href = currentHref!!','href = currentHref')],
 'feature/material/DocumentServiceRepository.kt': [('response.body ?: error("服务未返回数据")','response.body'),('response.body?.byteStream() ?: error("缺少资产")','response.body.byteStream()')],
 'feature/material/ui/StudyFileCard.kt': [('ButtonDefaults.outlinedButtonBorder','ButtonDefaults.outlinedButtonBorder(enabled = true)')],
 'ui/components/message/ChatMessageReasoning.kt': [('ReasoningTitle(title = thinkingTitle!!)','ReasoningTitle(title = thinkingTitle)')],
 'ui/components/ui/ViewText.kt': [('setLineSpacing(extraSpacing.toFloat(), 1.0f)','setLineSpacing(extraSpacing, 1.0f)')],
 'ui/pages/assistant/detail/AssistantBasicPage.kt': [('assistant.maxTokens!!, assistant.maxTokens','assistant.maxTokens, assistant.maxTokens')],
 'ui/pages/chat/VoiceSessionController.kt': [('capture?.join()','capture.join()')],
 'data/db/migrations/MigrationUtils.kt': [('var updatedPart: JsonElement = part','var updatedPart: JsonObject = partObject'),('val updatedObject = updatedPart as? JsonObject ?: return@map updatedPart','val updatedObject = updatedPart')],
 'data/db/migrations/Migration_11_12.kt': [('arrayOf(nodeId, conversationId, index, messagesJson, selectIndex)','arrayOf<Any>(nodeId, conversationId, index, messagesJson, selectIndex)')],
 'ui/components/ui/Tooltip.kt': [('import androidx.compose.material3.TooltipDefaults','import androidx.compose.material3.TooltipDefaults\nimport androidx.compose.material3.TooltipAnchorPosition'),('TooltipDefaults.rememberTooltipPositionProvider()','TooltipDefaults.rememberTooltipPositionProvider(preferredPosition = TooltipAnchorPosition.Above)')],
 'ui/components/message/ChatMessageTranslation.kt': [('Locale("es", "ES")','Locale.forLanguageTag("es-ES")')],
 'ui/pages/translator/TranslatorPage.kt': [('Locale("es", "ES")','Locale.forLanguageTag("es-ES")')],
 'web/routes/FilesRoutes.kt': [('part.dispose()','part.release()')],
 'ui/pages/chat/ChatPage.kt': [('import androidx.compose.material3.adaptive.currentWindowDpSize','import androidx.compose.ui.platform.LocalWindowInfo\nimport androidx.compose.ui.platform.LocalDensity\nimport androidx.compose.ui.unit.DpSize'),('val windowAdaptiveInfo = currentWindowDpSize()', 'val windowSize = LocalWindowInfo.current.containerSize\n    val windowAdaptiveInfo = with(LocalDensity.current) { DpSize(windowSize.width.toDp(), windowSize.height.toDp()) }')],
}
for relative, replacements in changes.items():
 p=base/relative
 t=p.read_text(encoding='utf-8')
 for old,new in replacements:
  assert old in t,(relative,old)
  t=t.replace(old,new)
 # Avoid duplicated import when a file already imports density.
 lines=t.splitlines(keepends=True);seen=set();out=[]
 for line in lines:
  if line.startswith('import ') and line in seen:continue
  if line.startswith('import '):seen.add(line)
  out.append(line)
 p.write_text(''.join(out),encoding='utf-8',newline='')
p=ROOT/'client/app/build.gradle.kts'
t=p.read_text(encoding='utf-8')
# Removing a nonexistent marker does not remove any real experimental API check.
t=t.replace('        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")\n','')
p.write_text(t,encoding='utf-8',newline='')
p=ROOT/'client/app/src/main/res/values/colors.xml'
t=p.read_text(encoding='utf-8').replace('  <color name="white">#FFFFFFFF</color>\n','')
p.write_text(t,encoding='utf-8',newline='')
