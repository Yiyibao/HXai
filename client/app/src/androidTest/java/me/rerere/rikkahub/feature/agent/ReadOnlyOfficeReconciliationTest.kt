package me.rerere.rikkahub.feature.agent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.material.DocumentServiceRepository
import me.rerere.rikkahub.feature.material.OfficeConversionRepository
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

/** Explicit opt-in: one owned public historical request, GET only, never upload/cancel/deploy. */
class ReadOnlyOfficeReconciliationTest {
    @Test fun inspectOriginalPublicRequestWithoutReupload(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("officeReadConsent")=="EXISTING_PUBLIC_OWNED_GET_ONLY")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val run=requireNotNull(args.getString("officeReadRunId")).also {require(it.matches(Regex("[a-zA-Z0-9_-]{1,80}")))}
        val directory=File(context.noBackupFilesDir,"quality-evaluation/$run")
        check(!directory.exists() && directory.mkdirs())
        val owner="17650f09-a284-429b-9ead-e6ab38892fef"
        val id="e6f374f9-3a5a-4090-99a9-4a0f7e5ef924"
        val expected="5ea4ceaee2633ecdc10de69f447fb4bd7f981e7f3a9cc9349c06c4713a140c9a"
        val documents=GlobalContext.get().get<DocumentServiceRepository>()
        val repository=documents.officeConversions
        val expectedUrl=(documents.config(owner).baseUrl+"/api/v1/conversion-requests/$id").toHttpUrl()
        val before=repository.task(owner,id)
        require(before.sourceSha256==expected && before.remoteId==null && before.state=="SUBMITTING" && !before.cancelRequested)
        val field=OfficeConversionRepository::class.java.getDeclaredField("http").apply {isAccessible=true}
        val original=field.get(repository) as OkHttpClient
        val paths=ArrayList<String>();var httpStatus: Int?=null
        val guarded=original.newBuilder().retryOnConnectionFailure(false).addInterceptor {chain ->
            val request=chain.request()
            check(request.method=="GET" && request.url==expectedUrl) {
                "Historical reconciliation permits only the exact owned GET"
            }
            check(paths.isEmpty()) {"No repeated request in this readback run"}
            paths+=request.url.encodedPath
            chain.proceed(request).also {httpStatus=it.code}
        }.build()
        var status="BLOCKED";var errorType: String?=null
        field.set(repository,guarded)
        try {
            repository.refresh(owner,id)
            status="ORIGINAL_METADATA_READ_BACK_NOT_ARTIFACT_ACCEPTANCE"
        } catch(error: Exception) {
            if(error is CancellationException)throw error
            errorType=error.javaClass.simpleName
        } finally {field.set(repository,original)}
        val after=repository.task(owner,id)
        val result=buildJsonObject {
            put("scope","ONE_ORIGINAL_PUBLIC_OWNER_REQUEST_GET_ONLY");put("status",status)
            put("owner",owner);put("localIntentId",id);put("sourceSha256",expected)
            put("httpStatus",httpStatus?.let(::JsonPrimitive) ?: JsonNull);put("errorType",errorType)
            put("requests",JsonArray(paths.map(::JsonPrimitive)));put("uploads",0);put("cancelCalls",0)
            put("beforeState",before.state);put("afterState",after.state);put("remoteId",after.remoteId)
            put("intentUnchanged",before==after);put("artifactAcceptance","NOT_EVALUATED")
        }
        File(directory,"READONLY_OFFICE_RECONCILIATION.json").outputStream().use {it.write(result.toString().toByteArray());it.fd.sync()}
    }
}
