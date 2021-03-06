package me.eater.emo.minecraft

import com.beust.klaxon.Klaxon
import com.flowpowered.nbt.*
import com.flowpowered.nbt.stream.NBTInputStream
import com.flowpowered.nbt.stream.NBTOutputStream
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.requests.download
import com.github.kittinunf.fuel.coroutines.awaitByteArrayResponse
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpDownload
import com.github.kittinunf.fuel.httpGet
import me.eater.emo.EmoContext
import me.eater.emo.Target
import me.eater.emo.minecraft.dto.asset_index.AssetIndex
import me.eater.emo.minecraft.dto.manifest.Artifact
import me.eater.emo.minecraft.dto.manifest.parseManifest
import me.eater.emo.minecraft.dto.minecraft_versions.VersionsManifest
import me.eater.emo.utils.Process
import me.eater.emo.utils.await
import me.eater.emo.utils.io
import me.eater.emo.utils.parallel
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile

const val MINECRAFT_VERSIONS_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json"
const val MINECRAFT_ASSETS_HOST_URL = "https://resources.download.minecraft.net/"

/**
 * Process that fetches version manifest
 */
class FetchVersionsManifest : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_versions"
    override fun getDescription() = "Fetching version manifest from Mojang"

    override suspend fun execute(context: EmoContext) {
        val result = Fuel.get(MINECRAFT_VERSIONS_MANIFEST)
            .awaitString()

        context.setVersionsManifest(Klaxon().parse<VersionsManifest>(result)!!)
    }
}

/**
 * Process that selects which Minecraft version to install
 */
class SelectMinecraftVersion : Process<EmoContext> {
    override fun getName() = "minecraft.select_version"
    override fun getDescription() = "Selecting Minecraft version to install"

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

/**
 * Process that will download the install manifest for selected minecraft version
 */
class FetchMinecraftManifest : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_manifest"
    override fun getDescription() = "Fetching Minecraft install manifest"

    override suspend fun execute(context: EmoContext) {

        val result = Fuel.get(context.selectedMinecraftVersion!!.url)
            .awaitString()

        val manifest = parseManifest(result)
        context.minecraftManifest = manifest

        io {
            val path = Paths.get(context.installLocation.toString(), ".emo/minecraft.json")
            Files.createDirectories(path.parent)
            path.toFile().writeText(result)
        }
    }
}

/**
 * Process that will download libraries for selected minecraft version
 */
class FetchMinecraftLibraries : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_libraries"
    override fun getDescription() = "Fetching libraries for Minecraft"

    override suspend fun execute(context: EmoContext) {
        parallel(context.minecraftManifest!!.getLibraries().filter { context.environment.passesRules(it.rules) }) {

            if (it.downloads.artifact !== null) {
                download(context, it.downloads.artifact)
            }

            val native = context.environment.selectNative(it)
            if (native !== null) {
                download(context, native)
                context.extractQueue.add(Pair(native, it.extract))
            }
        }
    }

    private suspend fun download(ctx: EmoContext, artifact: Artifact) {
        val path: Path = Paths.get(ctx.installLocation.toString(), "libraries", artifact.path)

        if (Files.exists(path)) return

        Files.createDirectories(path.parent)

        artifact.url
            .httpGet()
            .download()
            .fileDestination { _, _ -> File(path.toUri()) }
            .await()
    }
}

/**
 * Process that will extract native libraries for current minecraft install
 */
class ExtractNatives : Process<EmoContext> {
    override fun getName() = "minecraft.extract_natives"
    override fun getDescription() = "Extracting Native libraries for Minecraft"

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

/**
 * Process that fetches the asset index for this minecraft install
 */
class FetchMinecraftAssetIndex : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_assets_index"
    override fun getDescription() = "Fetching asset index"

    override suspend fun execute(context: EmoContext) {
        val result = context.minecraftManifest!!.getAssetIndexUrl()
            .httpGet()
            .awaitString()

        val index = AssetIndex.fromJson(result)
        context.assetIndex = index

        io {
            val assetIndexPath = Paths.get(
                context.installLocation.toString(),
                "assets/indexes/",
                context.minecraftManifest!!.getAssetIndexId() + ".json"
            )
            Files.createDirectories(assetIndexPath.parent)
            assetIndexPath.toFile().writeText(result)
        }
    }
}

/**
 * Process that fetches all assets for the current asset index
 */
class FetchMinecraftAssets : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_assets"
    override fun getDescription() = "Downloading assets for Minecraft"

    override suspend fun execute(context: EmoContext) {
        parallel(context.assetIndex!!.objects.entries, 20) {
            val assetId = it.value.hash.substring(0..1) + '/' + it.value.hash
            val path: Path = Paths.get(context.installLocation.toString(), "assets/objects/", assetId)

            if (Files.exists(path)) return@parallel

            Files.createDirectories(path.parent)

            (MINECRAFT_ASSETS_HOST_URL + assetId)
                .httpDownload()
                .fileDestination { _, _ -> path.toFile() }
                .awaitByteArrayResponse()
        }
    }
}

/**
 * Process that fetches the minecraft jar for the current install
 */
class FetchMinecraftJar : Process<EmoContext> {
    override fun getName() = "minecraft.fetch_jar"
    override fun getDescription() = "Fetching Minecraft executable"

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
            .awaitByteArrayResponse()
    }
}

@Suppress("BlockingMethodInNonBlockingContext", "UNCHECKED_CAST")
class AddServers : Process<EmoContext> {
    override fun getName() = "minecraft.add_servers"
    override fun getDescription() = "Adding preconfigured servers"

    override suspend fun execute(context: EmoContext) {
        val serversFile = File(context.installLocation.toString() + "/servers.dat")
        val map = context.servers
            .map { it.ip to it }
            .toMap()
        val todo = mutableSetOf(*map.keys.toTypedArray())

        val serverList: MutableList<MutableMap<String, Tag<*>>> = if (serversFile.exists()) {
            val tag = NBTInputStream(serversFile.inputStream(), false).let {
                val tag = it.readTag()
                it.close()
                tag
            }

            (((tag as? CompoundTag)?.value?.get("servers") as? ListTag<*>) ?: ListTag(
                "servers",
                CompoundTag::class.java,
                listOf()
            )).value
                .toMutableList()
                .map { it?.value as? CompoundTag }
                .flatMap { if (it == null) listOf<MutableMap<String, Tag<*>>>() else listOf(it.value.toMutableMap()) }
                .toMutableList()
        } else mutableListOf()

        for (server in serverList) {
            val entry = map[(server["ip"] as? StringTag)?.value ?: continue] ?: continue
            server["name"] = StringTag("name", entry.name)
            todo.remove(entry.ip)
        }

        for (item in todo) {
            val entry = map[item] ?: continue
            serverList.add(entry.toNBTMap().toMutableMap())
        }

        val newTag = CompoundTag("", CompoundMap(mapOf(
            "servers" to ListTag(
                "servers", CompoundTag::class.java,
                serverList.map {
                    CompoundTag("", CompoundMap(it))
                }
            )
        )))

        val outputStream = serversFile.outputStream()
        val nbtOutputStream = NBTOutputStream(outputStream, false)
        nbtOutputStream.writeTag(newTag)
        nbtOutputStream.flush()
        nbtOutputStream.close()
    }
}
