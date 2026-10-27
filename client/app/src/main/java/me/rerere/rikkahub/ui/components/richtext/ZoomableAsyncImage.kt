package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.ai.tools.ChatImages
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.java.KoinJavaComponent.getKoin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode

val LocalChatImageConversation = staticCompositionLocalOf<String?> { null }

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val placeholder = if(LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    val settingsStore = remember { getKoin().get<SettingsStore>() }
    val images = remember { getKoin().get<ChatImages>() }
    val settings by settingsStore.settingsFlow.collectAsState()
    val owner = settings.activeProfileId
    val conversationId = LocalChatImageConversation.current
    val resolved by produceState<Triple<String, String, String>?>(null, model, owner, conversationId) {
        value = null
        if (!model.isNullOrBlank()) {
            try {
                val file = images.resolve(owner, model, conversationId) {
                    check(settingsStore.settingsFlow.value.activeProfileId == owner) { "图片上下文已改变" }
                }
                value = Triple(owner, model, file.toUri().toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = null
            }
        }
    }
    val safeModel = resolved?.takeIf { it.first == owner && it.second == model }?.third
    val coilModel = ImageRequest.Builder(context)
        .data(safeModel)
        .placeholder(placeholder)
        .crossfade(false)
        .allowHardware(!export)
        .build()
    var loading by remember { mutableStateOf(false) }
    AsyncImage(
        model = coilModel,
        contentDescription = contentDescription,
        modifier = modifier
            .shimmer(isLoading = loading)
            .clickable {
                showImageViewer = safeModel != null
            },
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
        onLoading = {
            loading = true
        },
        onSuccess = {
            loading = false
        },
        onError = {
            loading = false
        },
    )
    if (showImageViewer && safeModel != null) {
        ImagePreviewDialog(images = listOf(safeModel)) {
            showImageViewer = false
        }
    }
}
