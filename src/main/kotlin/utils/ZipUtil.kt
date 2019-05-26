package me.eater.emo.utils

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ZipUtil {
    suspend fun unpack(
        inputStream: InputStream,
        target: String,
        beforeFile: (ZipEntry) -> Boolean = { true },
        afterFile: (File, ZipEntry) -> Unit = { _, _ -> }
    ) {
        io {
            val zip = ZipInputStream(inputStream)
            var entry: ZipEntry? = null

            fun nextEntry(): Boolean {
                entry = zip.nextEntry
                return entry != null
            }

            while (nextEntry()) {
                val thisEntry = entry!!
                if (!beforeFile(thisEntry)) {
                    continue
                }

                val path = "$target/${thisEntry.name}"
                if (thisEntry.isDirectory) {
                    Files.createDirectories(Paths.get(path))
                    continue
                }

                val outputFile = File(path)
                outputFile.parentFile.mkdirs()
                val outputStream = outputFile.outputStream()
                zip.buffered().copyTo(outputStream)
                zip.closeEntry()
                outputStream.close()

                afterFile(File(path), thisEntry)
            }

            zip.close()
        }
    }
}