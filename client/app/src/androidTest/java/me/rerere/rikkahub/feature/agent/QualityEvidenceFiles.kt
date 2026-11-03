package me.rerere.rikkahub.feature.agent

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** QA evidence only. A final name is never visible while its bytes are being written. */
internal object QualityEvidenceFiles {
    @Synchronized fun write(file: File, bytes: ByteArray, checkpoint: (String) -> Unit = {}) {
        require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024)
        val directory = requireNotNull(file.absoluteFile.parentFile)
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val lockFile = File(directory, ".evidence.lock")
        check(!Files.isSymbolicLink(lockFile.toPath()))
        // Android app SELinux policy can prohibit hard links. All QA writers use
        // this process monitor plus an OS file lock before testing/publishing a
        // final name. The lock is released by the OS on real process exit.
        RandomAccessFile(lockFile, "rw").channel.use { channel -> channel.lock().use {
            check(!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "Existing evaluation evidence is immutable"
            }
            val temporary = File.createTempFile(".evidence-", ".pending", directory)
            try {
            FileOutputStream(temporary).use { stream ->
                val split = bytes.size / 2
                stream.write(bytes, 0, split)
                checkpoint("after_half_write")
                stream.write(bytes, split, bytes.size - split)
                stream.fd.sync()
            }
            checkpoint("after_file_sync")
            // No fallback to a copy or direct final-file write. The lock above
            // makes the no-overwrite check valid for every cooperating QA writer.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            checkpoint("after_publish")
            syncDirectory(directory)
            check(file.readBytes().contentEquals(bytes)) { "Evaluation evidence readback mismatch" }
            } finally {
                Files.deleteIfExists(temporary.toPath())
                syncDirectory(directory)
            }
        } }
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
}
