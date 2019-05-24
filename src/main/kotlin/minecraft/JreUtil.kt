package me.eater.emo.minecraft

import com.github.kittinunf.fuel.httpDownload
import me.eater.emo.EmoInstance
import me.eater.emo.minecraft.dto.launcher.LauncherArtifact
import me.eater.emo.utils.await
import me.eater.emo.utils.io
import org.tukaani.xz.LZMAInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object JreUtil {

    /**
     * Downloads and extract JRE at given location
     * See [EmoInstance.getAvailableJRE] to see how to obtain an [LauncherArtifact] object
     */
    suspend fun downloadJRE(jre: LauncherArtifact, target: String) {
        val temp = File.createTempFile("emo", ".zip.xz")
        jre.url
            .httpDownload()
            .fileDestination { _, _ -> temp }
            .await()

        io {
            val zip = ZipInputStream(LZMAInputStream(temp.inputStream().buffered()).buffered())
            var entry: ZipEntry? = null

            fun nextEntry(): Boolean {
                entry = zip.nextEntry
                return entry != null
            }

            while (nextEntry()) {
                val thisEntry = entry!!
                val path = "$target/${thisEntry.name}"
                if (thisEntry.isDirectory) {
                    Files.createDirectories(Paths.get(path))
                    continue
                }

                val outputStream = File(path).outputStream()
                zip.buffered().copyTo(outputStream)
                zip.closeEntry()
                outputStream.close()

                if (thisEntry.name.startsWith("bin/")) {
                    File(path).setExecutable(true)
                }

            }

            zip.close()
            temp.delete()
        }
    }
}
