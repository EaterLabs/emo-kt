package me.eater.emo.forge

import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.fuel.coroutines.awaitByteArrayResponse
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpDownload
import com.github.kittinunf.fuel.httpGet
import me.eater.emo.EmoContext
import me.eater.emo.Target
import me.eater.emo.VersionSelector
import me.eater.emo.forge.dto.manifest.v1.Library
import me.eater.emo.forge.dto.manifest.v1.Manifest
import me.eater.emo.forge.dto.promotions.Promotions
import me.eater.emo.utils.Process
import me.eater.emo.utils.io
import me.eater.emo.utils.parallel
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Pack200

/**
 * Process that fetches the known versions of Forge, and if needed selects the correct minecraft version
 */
class FetchForgeVersions : Process<EmoContext> {
    override fun getName() = "forge.fetch_versions"
    override fun getDescription() = "Fetching known Forge versions"

    override suspend fun execute(context: EmoContext) {
        if (context.forgeVersion!!.isStatic()) {
            context.selectedForgeVersion = context.forgeVersion.selector
            return
        }

        val manifest = "https://files.minecraftforge.net/maven/net/minecraftforge/forge/promotions.json"
            .httpGet()
            .awaitString()

        val promotions = Promotions.fromJson(manifest)!!

        val key = when (context.selectedMinecraftVersion) {
            null -> context.forgeVersion.selector
            else -> "${context.selectedMinecraftVersion!!.id}-${context.forgeVersion.selector}"
        }

        if (!promotions.promos.containsKey(key)) {
            throw Error("No forge distribution found for $key")
        }

        promotions.promos.getValue(key).run {
            context.selectedForgeVersion = version
            context.minecraftVersion = VersionSelector(minecraftVersion)
        }
    }
}

/**
 * Process that fetches the universal Forge jar
 */
class FetchUniversal : Process<EmoContext> {
    override fun getName() = "forge.v1.fetch_universal"
    override fun getDescription() = "Fetch universal Forge runtime"

    override suspend fun execute(context: EmoContext) {
        val versionTuple = "${context.selectedMinecraftVersion!!.id}-${context.selectedForgeVersion!!}"
        val artifactUrl =
            "https://files.minecraftforge.net/maven/net/minecraftforge/forge/$versionTuple/forge-$versionTuple-universal.jar"

        io {
            artifactUrl
                .httpDownload()
                .fileDestination { _, _ -> Paths.get(context.installLocation.toString(), "forge.jar").toFile() }
                .awaitByteArrayResponse()

        }
    }
}

/**
 * Process that loads the install manifest from the universal forge jar
 */
class LoadForgeManifest : Process<EmoContext> {
    override fun getName() = "forge.v1.load_manifest"
    override fun getDescription() = "Loading Forge install manifest"

    override suspend fun execute(context: EmoContext) {
        io {
            val jar = JarFile(Paths.get(context.installLocation.toString(), "forge.jar").toFile())
            val json = String(jar.getInputStream(jar.getJarEntry("version.json")).readBytes())
            context.forgeManifest = Manifest.fromJson(json)!!
            jar.close()
        }
    }
}

/**
 * Process that fetches the libraries needed for forge
 */
class FetchForgeLibraries : Process<EmoContext> {
    override fun getName() = "forge.v1.fetch_libraries"
    override fun getDescription() = "Fetching libraries for Forge"

    override suspend fun execute(context: EmoContext) {
        parallel((context.forgeManifest!! as Manifest).libraries) {
            if (it.clientreq === null && it.serverreq === null) {
                return@parallel
            }

            val mirror = it.url ?: "https://libraries.minecraft.net"

            val file = Paths.get(context.installLocation.toString(), "libraries", it.getPath())
            Files.createDirectories(file.parent)

            if (Files.exists(file)) return@parallel

            try {
                (mirror + '/' + it.getPath())
                    .httpDownload()
                    .fileDestination { _, _ -> file.toFile() }
                    .awaitByteArrayResponse()
            } catch (t: Throwable) {
                if (t is FuelError && t.response.statusCode == 404 && it.url != null) {
                    tryDownloadingPack(it, file.toFile())
                } else {
                    throw t
                }
            }

        }

        if (context.target == Target.Client) {
            val newPath = Paths.get(
                context.installLocation.toString(),
                "libraries/net/minecraftforge/forge",
                "${context.selectedMinecraftVersion!!.id}-${context.selectedForgeVersion!!}",
                "forge-${context.selectedMinecraftVersion!!.id}-${context.selectedForgeVersion!!}.jar"
            )
            Files.createDirectories(newPath.parent)
            Files.move(
                Paths.get(context.installLocation.toString(), "forge.jar"),
                newPath,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }


    @ExperimentalUnsignedTypes
    suspend fun tryDownloadingPack(lib: Library, dest: File) {
        val (_, _, data) = ("${lib.url!!}/${lib.getPath()}.pack.xz")
            .httpGet()
            .awaitByteArrayResponse()

        io {
            val decompressed = XZInputStream(data.inputStream()).readBytes()
            // val decompressedBuffer = ByteBuffer.wrap(decompressed)
            val decompressedLen = decompressed.size

            val blobLen = (decompressed[decompressedLen - 8].toUByte() and 0xFF.toUByte()).toInt() or
                    ((decompressed[decompressedLen - 7].toUByte() and 0xFF.toUByte()).toInt() shl 8) or
                    ((decompressed[decompressedLen - 6].toUByte() and 0xFF.toUByte()).toInt() shl 16) or
                    ((decompressed[decompressedLen - 5].toUByte() and 0xFF.toUByte()).toInt() shl 24)


            val checksums = decompressed.copyOfRange(decompressedLen - blobLen - 8, decompressedLen - 8)
            val jarFile = FileOutputStream(dest)
            val jar = JarOutputStream(jarFile)
            Pack200.newUnpacker().unpack(decompressed.inputStream(0, decompressedLen - blobLen - 8), jar)
            val checksumsEntry = JarEntry("checksums.sha1")
            checksumsEntry.time = 0
            jar.putNextEntry(checksumsEntry)
            jar.write(checksums)
            jar.closeEntry()
            jar.close()
            jarFile.close()
        }
    }
}