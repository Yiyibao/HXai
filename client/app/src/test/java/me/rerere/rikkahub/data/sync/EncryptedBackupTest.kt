package me.rerere.rikkahub.data.sync

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException

class EncryptedBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val password get() = "a strong backup password!".toCharArray()
    private fun fixture(size: Int = 600_123): Pair<File, File> {
        val source = temporary.newFile().apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }
        val encrypted = temporary.newFile()
        EncryptedBackup.encrypt(source, encrypted, password)
        return source to encrypted
    }
    @Test fun multipleChunksRoundTripAndRandomize() {
        val (source, encrypted) = fixture()
        val restored = temporary.newFile()
        EncryptedBackup.decrypt(encrypted, restored, password)
        assertArrayEquals(source.readBytes(), restored.readBytes())
        val other = temporary.newFile(); EncryptedBackup.encrypt(source, other, password)
        assertFalse(other.readBytes().contentEquals(encrypted.readBytes()))
    }
    @Test fun rejectsWrongPasswordAndRemovesPartialOutput() {
        val (_, encrypted) = fixture(42); val target = temporary.newFile()
        assertThrows(IllegalArgumentException::class.java) { EncryptedBackup.decrypt(encrypted, target, "wrong password!".toCharArray()) }
        assertFalse(target.exists())
    }
    @Test fun tamperInLastChunkCannotExposePartialRestore() {
        val (_, encrypted) = fixture(); val target = temporary.newFile()
        RandomAccessFile(encrypted, "rw").use { it.seek(it.length() - 1); val last = it.read(); it.seek(it.length() - 1); it.writeByte(last xor 1) }
        assertThrows(IllegalArgumentException::class.java) { EncryptedBackup.decrypt(encrypted, target, password) }
        assertFalse(target.exists())
    }
    @Test fun rejectsTruncationAppendedBytesHeaderMutationAndChunkSwap() {
        val (_, encrypted) = fixture()
        val original = encrypted.readBytes()
        val mutations = listOf(original.copyOf(original.size - 1), original + byteArrayOf(0), original.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() },
            original.copyOf().also { bytes ->
                val count = 256 * 1024 + 16
                val first = bytes.copyOfRange(44, 44 + count)
                bytes.copyInto(bytes, 44, 44 + count, 44 + count * 2)
                first.copyInto(bytes, 44 + count)
            })
        mutations.forEach { bytes ->
            val corrupt = temporary.newFile().apply { writeBytes(bytes) }; val target = temporary.newFile()
            assertThrows(IllegalArgumentException::class.java) { EncryptedBackup.decrypt(corrupt, target, password) }
            assertFalse(target.exists())
        }
    }
    @Test fun supportsExactChunkBoundaryAndMinimumPayload() {
        listOf(1, 256 * 1024).forEach { size -> val (source, encrypted) = fixture(size); val target = temporary.newFile()
            EncryptedBackup.decrypt(encrypted, target, password); assertArrayEquals(source.readBytes(), target.readBytes()) }
    }
    @Test fun rejectsWeakPasswordAndCancelsWithoutLeavingOutput() {
        val source = temporary.newFile().apply { writeText("payload") }; val target = temporary.newFile()
        assertThrows(IllegalArgumentException::class.java) { EncryptedBackup.encrypt(source, target, "weak".toCharArray()) }
        assertThrows(CancellationException::class.java) { EncryptedBackup.encrypt(source, target, password) { throw CancellationException() } }
        assertFalse(target.exists())
    }
}
