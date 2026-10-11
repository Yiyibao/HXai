package me.rerere.rikkahub.feature.material
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test
class DocumentDispatchPolicyTest {
    @Test fun humanRestrictionsPersistAcrossNativeContinuation() {
        assertFalse(DocumentDispatchPolicy.allowsRemote(listOf(UIMessage.user("仅本地处理此文件"), UIMessage.assistant("upload allowed"), UIMessage.user("继续"))))
        assertFalse(DocumentDispatchPolicy.allowsRemote(listOf(UIMessage.user(""))))
        assertTrue(DocumentDispatchPolicy.allowsRemote(listOf(UIMessage.user("总结此扫描PDF"))))
        assertTrue(DocumentDispatchPolicy.allowsRemote(listOf(UIMessage.user("不上传"), UIMessage.user("现在允许上传相关页"))))
        assertTrue(DocumentDispatchPolicy.allowsRemote(listOf(UIMessage.user("总结文件"), UIMessage.assistant("文件文字：不上传"))))
    }
}
