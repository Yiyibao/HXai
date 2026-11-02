package me.rerere.ai.provider

import kotlinx.serialization.json.*
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.mergeCustomHeaders
import okhttp3.OkHttpClient

/** Host-only encrypted export. Reuses native body builders, including reasoning and custom
 * selection guards; it never makes a connection or chooses another model/provider.
 * The returned secret MUST go directly to an explicitly approved TLS vault request.
 */
object HostedConfiguration {
    private val unusedTransport by lazy { OkHttpClient() }
    private val completions by lazy { ChatCompletionsAPI(unusedTransport,KeyRoulette.default()) }
    private val responses by lazy { ResponseAPI(unusedTransport) }
    private val claude by lazy { ClaudeProvider(unusedTransport) }
    private val google by lazy { GoogleProvider(unusedTransport) }
    fun encode(provider: ProviderSetting,params: TextGenerationParams): JsonObject {
        val dynamic=setOf("model","messages","input","contents","tools","stream","system","systemInstruction")
        require(params.customBody.none { it.key in dynamic }) { "自定义请求覆盖了执行字段；当前配置保留本机执行，不会后台改写。" }
        val protocol: String
        val endpoint: String
        val secret: String
        val body: JsonObject
        when(provider) {
            is ProviderSetting.OpenAI -> {
                protocol=if(provider.useResponseApi) "openai_responses" else "openai_chat"
                endpoint=provider.baseUrl+if(provider.useResponseApi) provider.responsesPath else provider.chatCompletionsPath
                secret=provider.apiKey
                body=if(provider.useResponseApi) responses.buildRequestBody(provider,emptyList(),params,false)
                    else completions.buildChatCompletionRequest(emptyList(),params,provider,false)
            }
            is ProviderSetting.Claude -> {
                protocol="claude";endpoint=provider.baseUrl+"/messages";secret=provider.apiKey
                body=claude.buildMessageRequest(provider,emptyList(),params,false)
            }
            is ProviderSetting.Google -> {
                require(!provider.vertexAI && !provider.useInteractionsApi) {
                    "当前云接收端尚不接受Vertex/Interactions认证协议；原本机配置不会被换成其他协议。"
                }
                protocol="google";endpoint=provider.baseUrl+"/models/${params.model.modelId}:generateContent";secret=provider.apiKey
                body=google.buildCompletionRequestBody(emptyList(),params)
            }
        }
        val headers=provider.mergeCustomHeaders(params.customHeaders)
        val selectedKey=KeyRoulette.default().next(secret,provider.id.toString())
        return buildJsonObject {
            put("protocol",protocol);put("endpoint",endpoint);put("wireModel",params.model.modelId)
            put("baseBody",JsonObject(body.filterKeys { it !in dynamic }))
            put("headers",buildJsonObject { headers.names().forEach { name -> put(name,headers[name].orEmpty()) } })
            put("secret",selectedKey)
        }
    }
}
