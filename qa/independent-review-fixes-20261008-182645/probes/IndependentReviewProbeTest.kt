package independent.review

import me.rerere.rikkahub.ui.pages.extensions.skills.SkillsVM
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import sun.misc.Unsafe

class IndependentReviewProbeTest {
    @Test fun oversizedCompressedSkillMustRejectWithoutExhaustingHeap() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write("---\nname: qa-resource-probe\ndescription: synthetic\n---\nInstructions".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/synthetic.bin"))
            val chunk = ByteArray(8192)
            repeat(16384) { zip.write(chunk) } // 128 MiB, generated without allocating it.
            zip.closeEntry()
        }
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as Unsafe
        // Bypass Android ViewModel initialization: parsing happens before SkillManager use.
        // No install, profile, network, or application file is touched.
        val vm = unsafe.allocateInstance(SkillsVM::class.java)
        val method = SkillsVM::class.java.getDeclaredMethod("importSkillsFromZip", ByteArray::class.java).apply { isAccessible = true }
        val failure = try { method.invoke(vm, output.toByteArray()); null }
            catch (error: InvocationTargetException) { error.cause }
        println("QA_COMPRESSED_BYTES=${output.size()} QA_EXPANDED_BYTES=${128L * 1024 * 1024} QA_FAILURE=${failure?.javaClass?.name}")
        assertTrue("Oversized ZIP must fail through a bounded import error; actual=${failure?.javaClass?.name}", failure is Exception)
    }
}
