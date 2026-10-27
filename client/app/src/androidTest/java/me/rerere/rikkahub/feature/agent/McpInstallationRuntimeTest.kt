package me.rerere.rikkahub.feature.agent

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class McpInstallationRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val config get()=McpPinnedConfig(18889,"fixture",AgentTaskStore.hash(""))
    private fun fixture(block: (McpInstallationStore,String)->Unit) {
        val name="qa-mcp-registry-${UUID.randomUUID()}.db"
        try { McpInstallationStore(context,name).use { block(it,name) } }
        finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }
    @Test fun installationIsDisabledUntilExplicitGrantAndReopenKeepsExactIdentity()=fixture { store,name ->
        val installed=store.install("alice",0,config)
        assertEquals("DISABLED",installed.state)
        assertTrue(runCatching { store.checkCurrent(installed) }.isFailure)
        val enabled=store.command("alice",installed.id,installed.revision,"enable")
        assertNotEquals(installed.id,enabled.id)
        McpInstallationStore(context,name).use { assertEquals(enabled,it.get("alice"));it.checkCurrent(enabled) }
    }
    @Test fun revocationCannotBeUndoneByEnableOrReopeningAndOtherOwnerIsPreserved()=fixture { store,name ->
        val alice=store.install("alice",0,config)
        val bob=store.install("bob",0,config.copy(recordId="bob"))
        val enabled=store.command("alice",alice.id,alice.revision,"enable")
        val revoked=store.command("alice",enabled.id,enabled.revision,"revoke")
        McpInstallationStore(context,name).use { reopened ->
            assertEquals(revoked,reopened.get("alice"));assertEquals(bob,reopened.get("bob"))
            assertTrue(runCatching { reopened.command("alice",revoked.id,revoked.revision,"enable") }.isFailure)
            assertTrue(runCatching { reopened.checkCurrent(enabled) }.isFailure)
        }
        assertEquals(bob,store.get("bob"))
    }
    @Test fun foreignIdentityAndStaleCasCommandsMakeNoMutation()=fixture { store,_ ->
        val alice=store.install("alice",0,config);val bob=store.install("bob",0,config)
        assertTrue(runCatching { store.command("bob",alice.id,bob.revision,"enable") }.isFailure)
        assertTrue(runCatching { store.command("alice",alice.id,alice.revision+1,"revoke") }.isFailure)
        assertTrue(runCatching { store.install("alice",0,config.copy(port=18890)) }.isFailure)
        assertEquals(alice,store.get("alice"));assertEquals(bob,store.get("bob"))
    }
    @Test fun enabledConfigCannotBeReplacedAndReenableDoesNotRestoreOldIdentity()=fixture { store,_ ->
        val first=store.install("alice",0,config);val enabled=store.command("alice",first.id,first.revision,"enable")
        assertTrue(runCatching { store.install("alice",enabled.revision,config.copy(port=18890)) }.isFailure)
        val disabled=store.command("alice",enabled.id,enabled.revision,"disable")
        val again=store.command("alice",disabled.id,disabled.revision,"enable")
        assertNotEquals(enabled.id,again.id);assertTrue(again.grantEpoch>enabled.grantEpoch)
        assertTrue(runCatching { store.checkCurrent(enabled) }.isFailure)
        store.checkCurrent(again)
    }
    @Test fun uninstallRetainsTombstoneAndNewInstallNeedsSeparateAuthorization()=fixture { store,name ->
        val first=store.install("alice",0,config)
        val removed=store.command("alice",first.id,first.revision,"uninstall")
        McpInstallationStore(context,name).use { assertEquals("UNINSTALLED",it.get("alice")!!.state) }
        assertTrue(runCatching { store.install("alice",0,config) }.isFailure)
        val next=store.install("alice",removed.revision,config)
        assertEquals("DISABLED",next.state);assertTrue(next.revision>removed.revision)
        assertNotEquals(first.id,next.id)
    }
    @Test fun invalidConfigTransactionPreservesHealthyRecords()=fixture { store,_ ->
        val good=store.install("alice",0,config)
        for (bad in listOf(config.copy(port=80),config.copy(recordId="../file"),config.copy(protocol="unknown"),
            config.copy(sha256="bad"),config.copy(toolContractHash="untrusted"),config.copy(serverVersion="x\ny"))) {
            assertTrue(runCatching { store.install("alice",good.revision,bad) }.isFailure)
            assertEquals(good,store.get("alice"))
        }
        val normal=store.command("alice",good.id,good.revision,"enable");store.checkCurrent(normal)
    }
    @Test fun concurrentGrantCommandsConsumeExactlyOneRevision()=fixture { store,name ->
        val pin=store.install("alice",0,config)
        val ready=CountDownLatch(2);val start=CountDownLatch(1);val won=AtomicInteger()
        val threads=(1..2).map { thread {
            McpInstallationStore(context,name).use { other ->
                other.readableDatabase
                ready.countDown();start.await()
                if(runCatching { other.command("alice",pin.id,pin.revision,"enable") }.isSuccess) won.incrementAndGet()
            }
        } }
        assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));start.countDown();threads.forEach { it.join(10000);assertFalse(it.isAlive) }
        assertEquals(1,won.get());assertEquals(pin.revision+1,store.get("alice")!!.revision)
    }
    @Test fun unsupportedFutureSchemaIsRefusedWithoutDeletingRecords()=fixture { store,name ->
        val pin=store.install("alice",0,config);store.close()
        val file=context.noBackupFilesDir.resolve(name)
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.version=2 }
        McpInstallationStore(context,name).use { assertTrue(runCatching { it.get("alice") }.isFailure) }
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2,db.version)
            db.rawQuery("SELECT installation_id FROM installations WHERE owner='alice'",null).use { row -> assertTrue(row.moveToFirst());assertEquals(pin.id,row.getString(0)) }
        }
    }
    @Test fun corruptConfigCannotBecomeAuthorizedAndIsNotRebuilt()=fixture { store,name ->
        store.install("alice",0,config)
        store.writableDatabase.execSQL("UPDATE installations SET config_json='unparseable' WHERE owner='alice'")
        McpInstallationStore(context,name).use { assertTrue(runCatching { it.get("alice") }.isFailure) }
        store.readableDatabase.rawQuery("SELECT config_json FROM installations WHERE owner='alice'",null).use {
            assertTrue(it.moveToFirst());assertEquals("unparseable",it.getString(0))
        }
    }
    @Test fun corruptSqliteBytesArePreservedInsteadOfDeletingTheRegistry()=fixture { store,name ->
        store.install("alice",0,config);store.close()
        val file=context.noBackupFilesDir.resolve(name)
        val corrupt="synthetic invalid SQLite header; preserve revocation evidence".toByteArray()
        file.writeBytes(corrupt)
        McpInstallationStore(context,name).use { assertTrue(runCatching { it.get("alice") }.isFailure) }
        assertTrue(file.exists());assertArrayEquals(corrupt,file.readBytes())
    }
}
