package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MathToolEnvelopeTest {
    private fun obj(s: String)=Json.parseToJsonElement(s).jsonObject
    private fun wire(operation: String,request: String)=buildJsonObject {put("operation",operation);put("request",request)}
    @Test fun exactEnvelopeIsVerifiedAndIndependentReadbackUsesTheActualWireHash(): Unit = runBlocking {
        val raw=obj("""{"operation":"identity","left":"x+x","right":"2*x"}""")
        val input=wire("identity","""{"left":"x+x","right":"2*x"}""")
        val old=MathVerification.verify(raw);val result=MathVerification.verify(input)
        assertEquals(JsonPrimitive("VERIFIED"),result["status"])
        assertEquals(old["difference_coefficients"],result["difference_coefficients"])
        assertEquals(JsonPrimitive(1),result["tool_input_contract"])
        assertNotEquals(old["input_sha256"],result["input_sha256"])
        assertTrue(MathVerification.accepts(input,result))
        assertFalse(MathVerification.accepts(input,old))
        assertFalse(MathVerification.accepts(raw,result))
    }
    @Test fun irrelevantDefaultsAndNestedOperationAreNotSilentlyStripped(): Unit = runBlocking {
        for (request in listOf("""{"left":"x","right":"x","expression":""}""",
            """{"operation":"identity","left":"x","right":"x"}""",
            """{"left":"x","right":"x","request":"{}"}""", "[]", "not JSON")) {
            val input=wire("identity",request)
            assertEquals(JsonPrimitive("UNSUPPORTED"),MathVerification.verify(input)["status"])
        }
    }
    @Test fun disproofUnsupportedDomainsAndModifiedInputsCannotBorrowSuccess(): Unit = runBlocking {
        val valid=wire("rational_identity","""{"left":"x/x","right":"1"}""")
        val result=MathVerification.verify(valid)
        assertTrue(MathVerification.accepts(valid,result))
        val wrong=wire("identity","""{"left":"x+x","right":"3*x"}""")
        assertEquals(JsonPrimitive("DISPROVED"),MathVerification.verify(wrong)["status"])
        val unsupported=wire("identity","""{"left":"sin(x)","right":"x"}""")
        assertEquals(JsonPrimitive("UNSUPPORTED"),MathVerification.verify(unsupported)["status"])
        assertFalse(MathVerification.accepts(wrong,result))
        val changed=wire("rational_identity","""{ "left":"x/x","right":"1"}""")
        assertFalse(MathVerification.accepts(changed,result))
    }
    @Test fun professionalInputsAndOldReceiptsKeepTheirExistingContract(): Unit = runBlocking {
        val raw=wire("professional_induction","""{"start":"0","initial":"0","next":"u+2*n+1","claimed":"n^2"}""")
        val result=MathVerification.verify(raw)
        assertEquals(JsonPrimitive("VERIFIED"),result["status"])
        assertNull(result["tool_input_contract"])
        assertTrue(MathVerification.accepts(raw,result))
    }
}
