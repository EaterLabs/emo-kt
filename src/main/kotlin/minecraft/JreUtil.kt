package me.eater.emo.minecraft

import com.github.kittinunf.fuel.httpDownload
import me.eater.emo.EmoInstance
import me.eater.emo.minecraft.dto.launcher.LauncherArtifact
import me.eater.emo.utils.ZipUtil
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

        ZipUtil.unpack(temp, target) { file, thisEntry ->
            if (thisEntry.name.startsWith("bin/")) {
                file.setExecutable(true)
            }
        }

        temp.delete()
    }
}
