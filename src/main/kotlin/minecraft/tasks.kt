package me.eater.emo.minecraft

import com.beust.klaxon.Klaxon
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.requests.download
import com.github.kittinunf.fuel.coroutines.awaitStringResponseResult
import com.github.kittinunf.fuel.httpDownload
import com.github.kittinunf.fuel.httpGet
import me.eater.emo.EmoContext
import me.eater.emo.Target
import me.eater.emo.minecraft.dto.asset_index.AssetIndex
import me.eater.emo.minecraft.dto.manifest.Artifact
import me.eater.emo.minecraft.dto.manifest.parseManifest
import me.eater.emo.minecraft.dto.minecraft_versions.VersionsManifest
import me.eater.emo.utils.Process
import me.eater.emo.utils.io
import me.eater.emo.utils.parallel
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile

const val MINECRAFT_VERSIONS_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json"
const val MINECRAFT_ASSESTS_HOST_URL = "https://resources.download.minecraft.net/"

class FetchVersionsManifest : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_versions"

    override suspend fun execute(context: EmoContext) {
        val (_, _, result) = Fuel.get(MINECRAFT_VERSIONS_MANIFEST)
            .awaitStringResponseResult()

        context.setVersionsManifest(Klaxon().parse<VersionsManifest>(result.get())!!)
    }
}

class SelectMinecraftVersion : Process<EmoContext> {
    override fun getName() = "minecraft.select_version"

    override suspend fun execute(context: EmoContext) {
        val selected: String = when {
            context.minecraftVersion.isStatic() -> context.minecraftVersion.selector
            context.minecraftVersion.selector == "latest" -> context.getVersionsManifest().latest.release
            else -> context.getVersionsManifest().latest.snapshot
        }

        for (version in context.getVersionsManifest().versions) {
            if (version.id == selected) {
                context.selectedMinecraftVersion = version
                break
            }
        }

        if (context.selectedMinecraftVersion === null) {
            throw Error("Couldn't find any Minecraft version with id '$selected'")
        }
    }
}

class FetchMinecraftManifest : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_manifest"

    override suspend fun execute(context: EmoContext) {

        val (_, _, result) = Fuel.get(context.selectedMinecraftVersion!!.url)
            .awaitStringResponseResult()

        val manifest = parseManifest(result.get())
        context.minecraftManifest = manifest

        io {
            val path = Paths.get(context.installLocation.toString(), ".emo/minecraft.json")
            Files.createDirectories(path.parent)
            path.toFile().writeText(result.get())
        }
    }
}

class FetchMinecraftLibraries : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_libraries"

    override suspend fun execute(context: EmoContext) {
        parallel(context.minecraftManifest!!.getLibraries().filter { context.profile.passesRules(it.rules) }) {

            if (it.downloads.artifact !== null) {
                download(context, it.downloads.artifact)
            }

            val native = context.profile.selectNative(it)
            if (native !== null) {
                download(context, native)
                context.extractQueue.add(Pair(native, it.extract))
            }
        }
    }

    private fun download(ctx: EmoContext, artifact: Artifact) {
        val path: Path = Paths.get(ctx.installLocation.toString(), "libraries", artifact.path)

        if (Files.exists(path)) return

        Files.createDirectories(path.parent)

        artifact.url
            .httpGet()
            .download()
            .fileDestination { _, _ -> File(path.toUri()) }
            .response { _ -> }
            .join()
    }
}

class ExtractNatives : Process<EmoContext> {
    override fun getName() = "minecraft.extract_natives"

    override suspend fun execute(context: EmoContext) {
        io {
            for (pair in context.extractQueue) {
                val artifact = pair.first
                val extract = pair.second
                val path: Path = Paths.get(context.installLocation.toString(), "libraries", artifact.path)
                val jar = JarFile(path.toFile())

                for (entry in jar.entries()) {
                    if (entry.isDirectory) {
                        continue
                    }

                    if (extract.exclude.find { entry.name.startsWith(it) } !== null) {
                        continue
                    }

                    val output = Paths.get(context.installLocation.toString(), "natives", entry.name)
                    Files.createDirectories(output.parent)
                    jar.getInputStream(entry).copyTo(output.toFile().outputStream())
                }

                jar.close()

                try {
                    Files.delete(path)
                } catch (e: Exception) {
                    // ?? :( why you like this windows
                    println("Failed to delete natives jar at: $path")
                }
            }
        }
    }
}

class FetchMinecraftAssetIndex : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_assets_index"

    override suspend fun execute(context: EmoContext) {
        val (_, _, result) = context.minecraftManifest!!.getAssetIndexUrl()
            .httpGet()
            .awaitStringResponseResult()

        val index = AssetIndex.fromJson(result.get())
        context.assetIndex = index

        io {
            val assetIndexPath = Paths.get(
                context.installLocation.toString(),
                "assets/indexes/",
                context.minecraftManifest!!.getAssetIndexId() + ".json"
            )
            Files.createDirectories(assetIndexPath.parent)
            assetIndexPath.toFile().writeText(result.get())
        }
    }
}

class FetchMinecraftAssets : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_assets"

    override suspend fun execute(context: EmoContext) {
        parallel(context.assetIndex!!.objects.entries, 20) {
            val assetId = it.value.hash.substring(0..1) + '/' + it.value.hash
            val path: Path = Paths.get(context.installLocation.toString(), "assets/objects/", assetId)

            if (Files.exists(path)) return@parallel

            Files.createDirectories(path.parent)

            (MINECRAFT_ASSESTS_HOST_URL + assetId)
                .httpDownload()
                .fileDestination { _, _ -> path.toFile() }
                .response { _ -> }
                .join()
        }
    }
}

class FetchMinecraftJar : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_jar"

    override suspend fun execute(context: EmoContext) {
        val (path, url) = when {
            context.target == Target.Client -> Pair(
                "minecraft.jar",
                context.minecraftManifest!!.getMinecraftClientUrl()
            )
            context.forgeVersion === null -> Pair("minecraft.jar", context.minecraftManifest!!.getMinecraftServerUrl())
            else -> Pair(
                "minecraft_server.${context.minecraftVersion.selector}.jar",
                context.minecraftManifest!!.getMinecraftServerUrl()
            )
        }

        url
            .httpDownload()
            .fileDestination { _, _ -> Paths.get(context.installLocation.toString(), path).toFile() }
            .response { _ -> }
            .join()
    }
}