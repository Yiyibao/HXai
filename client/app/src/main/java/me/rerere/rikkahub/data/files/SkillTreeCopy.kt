package me.rerere.rikkahub.data.files

import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Rollback copy of the registered tree, including links themselves, never their targets. */
internal fun copySkillTree(source: File, destination: File): Boolean = try {
    val root = source.toPath()
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.createDirectories(destination.toPath().resolve(root.relativize(dir)))
            return FileVisitResult.CONTINUE
        }
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.copy(file, destination.toPath().resolve(root.relativize(file)), LinkOption.NOFOLLOW_LINKS)
            return FileVisitResult.CONTINUE
        }
    })
    true
} catch (_: IOException) { false }
