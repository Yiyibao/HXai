package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class ProviderPhysicalAccountingTest {
    @Test
    fun serverToolContinuationHasTwoReceiptsBeforeTwoPhysicalDispatches(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-physical-${UUID.randomUUID()}.db"
        val scheduler = AgentScheduler(context,name)
        val server = ServerSocket(0).apply { soTimeout=15000 }
        val model = Model(modelId="qa-claude-physical")
        val provider = ProviderSetting.Claude(models=listOf(model),apiKey="synthetic-only",baseUrl="http://127.0.0.1:${server.localPort}")
        val assistant = Assistant(chatModelId=model.id,reasoningLevel=ReasoningLevel.OFF)
        val settings = Settings(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,chatModelId=model.id)
        val conversation = Conversation.ofId(Uuid.random(),assistant.id).copy(profileId=settings.activeProfileId)
        val task = scheduler.accept(conversation,"physical-continuation","synthetic search",settings)
        val failure = AtomicReference<Throwable>()
        val worker = thread(name="qa-physical-provider-http") {
            try {
                repeat(2) { index ->
                    server.accept().use { socket ->
                        socket.soTimeout=10000
                        val reader=socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        assertTrue(reader.readLine().startsWith("POST "))
                        var length=0
                        while (true) {
                            val line=reader.readLine()
                            if(line.isEmpty()) break
                            if(line.startsWith("Content-Length:",ignoreCase=true)) length=line.substringAfter(':').trim().toInt()
                        }
                        require(length in 1..100000)
                        // This fixture's requests are ASCII; Content-Length equals its character count.
                        val body=CharArray(length)
                        var read=0
                        while(read<length) {
                            val count=reader.read(body,read,length-read)
                            require(count>0)
                            read+=count
                        }
                        val encoded=String(body)
                        assertEquals(model.modelId,Json.parseToJsonElement(encoded).jsonObject["model"]?.jsonPrimitive?.content)
                        AgentTaskStore(context,name).use { store ->
                            store.readableDatabase.rawQuery("SELECT input_hash FROM calls WHERE task_id=? AND state='IN_FLIGHT' AND tool='provider:main'",arrayOf(task.id)).use {
                                assertTrue("Journal must precede physical dispatch",it.moveToFirst())
                                assertEquals(AgentTaskStore.hash(encoded),it.getString(0))
                                assertFalse(it.moveToNext())
                            }
                            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM usage WHERE task_id=?",arrayOf(task.id)).use {
                                assertTrue(it.moveToFirst()); assertEquals(index+1,it.getInt(0))
                            }
                        }
                        val content=if(index==0) """[{"type":"text","text":"searching"},{"type":"server_tool_use","id":"srv_1","name":"web_search","input":{"query":"synthetic"}}]"""
                            else """[{"type":"web_search_tool_result","tool_use_id":"srv_1","content":[]},{"type":"text","text":"done"}]"""
                        val input=if(index==0) 10 else 5
                        val output=if(index==0) 2 else 3
                        val reason=if(index==0) "pause_turn" else "end_turn"
                        val response="""{"id":"msg_$index","type":"message","role":"assistant","model":"qa-claude-physical","content":$content,"stop_reason":"$reason","usage":{"input_tokens":$input,"output_tokens":$output,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}""".toByteArray()
                        val stream=socket.getOutputStream()
                        stream.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        stream.write(response); stream.flush()
                    }
                }
            } catch(error:Throwable) { failure.set(error) }
        }
        try {
            scheduler.run(task,{ settings },{}, { "COMPLETED_UNVERIFIED" },{ false }) {
                val execution=requireNotNull(currentCoroutineContext()[AgentExecution])
                withContext(execution.accounting("main")) {
                    GlobalContext.get().get<ProviderManager>().getProviderByType(provider).generateText(
                        providerSetting=provider,messages=listOf(UIMessage.user("synthetic search")),
                        params=TextGenerationParams(model=model,reasoningLevel=ReasoningLevel.OFF))
                }
            }
            worker.join(5000)
            assertFalse(worker.isAlive)
            failure.get()?.let { throw it }
            AgentTaskStore(context,name).use { store ->
                store.readableDatabase.rawQuery("SELECT request_id,role,prompt_tokens,completion_tokens,cached_tokens,total_tokens,provider_id,model_id,reasoning,configuration_reference,provenance FROM usage WHERE task_id=? ORDER BY created_at,rowid",arrayOf(task.id)).use {
                    assertTrue(it.moveToFirst())
                    val first=it.getString(0)
                    assertEquals("main",it.getString(1)); assertEquals(10,it.getInt(2)); assertEquals(2,it.getInt(3)); assertEquals(0,it.getInt(4)); assertEquals(12,it.getInt(5))
                    assertTrue(it.moveToNext()); assertNotEquals(first,it.getString(0))
                    assertEquals("main",it.getString(1)); assertEquals(5,it.getInt(2)); assertEquals(3,it.getInt(3)); assertEquals(0,it.getInt(4)); assertEquals(8,it.getInt(5))
                    assertEquals(provider.id.toString(),it.getString(6)); assertEquals(model.id.toString(),it.getString(7))
                    assertEquals("OFF",it.getString(8)); assertEquals(task.configurationReference,it.getString(9)); assertEquals("reported",it.getString(10))
                    assertFalse(it.moveToNext())
                }
                assertEquals("COMPLETED_UNVERIFIED",store.get(task.owner,task.id).state)
            }
        } finally {
            server.close(); worker.join(5000); scheduler.close()
            listOf("","-journal","-wal","-shm").forEach { context.noBackupFilesDir.resolve(name+it).delete() }
        }
    }
}
