package me.rerere.rikkahub.service

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.tabs.ImportExportTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupPageTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    @Test fun requiresStrongMatchingPasswordAndClearsCancelledEntry() {
        val vm = org.koin.core.context.GlobalContext.get().get<BackupVM>()
        compose.activity.setContent { MaterialTheme { ImportExportTab(vm) {} } }
        compose.onNodeWithText("完整加密备份").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "s5-backup-page.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("创建完整加密备份").performClick()
        compose.onNodeWithText("继续").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("strong fixture password!")
        compose.onNodeWithText("继续").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("different fixture password!")
        compose.onNodeWithText("继续").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextClearance()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("strong fixture password!")
        compose.onNodeWithText("继续").assertIsEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("从加密备份恢复").performClick()
        compose.onNodeWithText("继续").assertIsNotEnabled()
        assertEquals(1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("创建完整加密备份").assertIsEnabled()
    }
}
