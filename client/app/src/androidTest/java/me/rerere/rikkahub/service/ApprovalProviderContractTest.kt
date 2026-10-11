package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Reports only allowlisted contract diagnostics, never response bodies or connection data. */
@RunWith(AndroidJUnit4::class)
class ApprovalProviderContractTest {
    @Test fun askUserSchemaAccepted() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realModel") == "true")
        val koin = GlobalContext.get()
        val settings = withTimeout(20_000) { koin.get<SettingsStore>().settingsFlow.first { !it.init } }
        val model = requireNotNull(settings.getCurrentChatModel())
        val provider = settings.providers.filterIsInstance<ProviderSetting.OpenAI>().single { p -> p.models.any { it.id == model.id } }
        val client = koin.get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!response.isSuccessful) {
                val error = me.rerere.ai.util.parseModelHttpError(response.code, response.peekBody(65536).string())
                InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                    putString("qa_http_status", response.code.toString())
                    putString("qa_rejection_reason", error.rejectionReason?.name ?: "UNKNOWN")
                })
            }
            response
        }.build()
        val tool = koin.get<LocalTools>().getTools(listOf(me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.AskUser))
        withTimeout(120_000) {
            OpenAIProvider(client, InstrumentationRegistry.getInstrumentation().targetContext).streamText(
                provider, listOf(UIMessage.user("Call ask_user once to ask whether to continue this synthetic test.")),
                TextGenerationParams(model = model, tools = tool)
            ).collect { }
        }
    }
}
