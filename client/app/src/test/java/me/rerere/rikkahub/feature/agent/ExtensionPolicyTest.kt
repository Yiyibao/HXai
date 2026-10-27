package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExtensionPolicyTest {
    private val schema=Json.parseToJsonElement("""{"type":"object","properties":{"path":{"type":"string"},"url":{"type":"string"},"label":{"type":"string","maxLength":20}},"required":["path","url","label"],"additionalProperties":false}""").jsonObject
    private val pin=ExtensionToolPin("read",schema,listOf(ExtensionScope("/path","directory",listOf("/course")),ExtensionScope("/url","origin",listOf("https://example.com"))))
    private fun args(path: String="/course/a",url: String="https://example.com/read",label: String="αβ🔬")=buildJsonObject { put("path",path);put("url",url);put("label",label) }
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun unicodeAndRootPathAreAccepted() { ExtensionPolicy.schema(schema);ExtensionPolicy.arguments(pin,args());ExtensionPolicy.arguments(pin,args("/course")) }
    @Test fun directoryBoundaryCannotUsePrefixOrTraversal() { for(p in listOf("/course-other/a","/course/../private","/course/%2e%2e/private","/course\\private")) rejected { ExtensionPolicy.arguments(pin,args(p)) } }
    @Test fun originCannotUseUserinfoPortOrSubdomain() { for(u in listOf("https://evil.com/","https://example.com.evil.com","https://example.com:444/a","https://a@example.com/")) rejected { ExtensionPolicy.arguments(pin,args(url=u)) } }
    @Test fun undeclaredFieldsAndOversizeAreRejected() { rejected { ExtensionPolicy.arguments(pin,JsonObject(args()+ ("key" to JsonPrimitive("secret")))) };rejected { ExtensionPolicy.arguments(pin,args(label="x".repeat(21))) } }
    @Test fun unknownSchemaConstraintsAreNeverIgnored() { rejected { ExtensionPolicy.schema(JsonObject(schema + ("\$ref" to JsonPrimitive("https://evil.com")))) };rejected { ExtensionPolicy.schema(buildJsonObject { put("type","object");put("additionalProperties",true) }) } }
    @Test fun tlsEndpointPolicyKeepsLocalQaSeparate() { ExtensionPolicy.endpoint("https://example.com/mcp",false);ExtensionPolicy.endpoint("http://127.0.0.1:18345/mcp",true);rejected { ExtensionPolicy.endpoint("http://127.0.0.1/mcp",false) };rejected { ExtensionPolicy.endpoint("https://a:b@example.com/mcp",false) };rejected { ExtensionPolicy.endpoint("https://example.com/mcp?token=secret",false) } }
    @Test fun schemaEnumsNumbersAndNestedObjectsAreChecked() {
        val s=Json.parseToJsonElement("""{"type":"object","properties":{"n":{"type":"integer","minimum":0,"maximum":5},"data":{"type":"array","items":{"type":"string","enum":["a","β"]},"maxItems":2}},"required":["n","data"]}""").jsonObject
        val p=ExtensionToolPin("read",s)
        ExtensionPolicy.schema(s);ExtensionPolicy.arguments(p,Json.parseToJsonElement("""{"n":0,"data":["β"]}""").jsonObject)
        for(value in listOf("""{"n":1.5,"data":[]}""","""{"n":6,"data":[]}""","""{"n":1,"data":["c"]}""")) rejected { ExtensionPolicy.arguments(p,Json.parseToJsonElement(value).jsonObject) }
    }
    @Test fun sseMultilineAndNotificationsAreDecodedAsData() {
        val result=ExtensionMcpTransport.parseSse(": keepalive\r\nevent: message\r\ndata: {\"jsonrpc\":\"2.0\",\r\ndata: \"id\":3,\"result\":{}}\r\n\r\n")
        assertEquals(JsonPrimitive(3),result.single()["id"])
    }
}
