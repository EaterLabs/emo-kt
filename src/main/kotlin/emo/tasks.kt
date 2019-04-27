package me.eater.emo.emo

import com.github.kittinunf.fuel.httpDownload
import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.toml.toToml
import me.eater.emo.EmoContext
import me.eater.emo.emo.dto.ClientLock
import me.eater.emo.emo.dto.Profile
import me.eater.emo.emo.dto.StartLock
import me.eater.emo.minecraft.dto.manifest.Argument
import me.eater.emo.minecraft.dto.manifest.emoKlaxon
import me.eater.emo.utils.Process
import me.eater.emo.utils.io
import me.eater.emo.utils.parallel
import net.swiftzer.semver.SemVer
import java.nio.file.Files
import java.nio.file.Paths


class CreateEmoProfile : Process<EmoContext> {
    override fun getName() = "emo.create_profile"

    override suspend fun execute(context: EmoContext) {
        val config = Config { addSpec(Profile) }

        config[Profile.minecraft] = context.selectedMinecraftVersion!!.id
        config.unset(Profile.forge)
        if (context.selectedForgeVersion !== null) {
            config[Profile.forge] = context.selectedForgeVersion!!
        }
        config.unset(Profile.mods)
        if (context.mods.isNotEmpty()) {
            config[Profile.mods] = context.mods
        }

        val path = Paths.get(context.installLocation.toString(), "emo.toml")

        io {
            config.toToml.toFile(path.toString())
        }
    }
}

val DEFAULT_JVM_ARGS: List<String> = listOf(
    "-Djava.library.path=${'$'}{natives_directory}",
    "-Dminecraft.launcher.brand=${'$'}{launcher_name}",
    "-Dminecraft.launcher.version=${'$'}{launcher_version}",
    "-cp",
    "${'$'}{classpath}"
)

val DEFAULT_JVM_ARGUMENTS: List<Argument> = listOf(
    Argument(listOf(), DEFAULT_JVM_ARGS)
)

class CreateEmoClientLock : Process<EmoContext> {

    override fun getName() = "emo.create_client_lock"

    override suspend fun execute(context: EmoContext) {
        val vars = hashMapOf(
            Pair("natives_directory", "natives"),
            Pair("assets_root", "assets"),
            Pair("assets_index_name", context.minecraftManifest!!.getAssetIndexId()),
            Pair("version_name", context.minecraftManifest!!.id),
            Pair("version_type", context.minecraftManifest!!.type),
            Pair("launcher_name", "me.eater.emo"),
            Pair("launcher_version", "1.0")
        )

        val ver = context.forgeManifest

        val (game, jvm) = when {
            ver == null -> {
                Pair(context.minecraftManifest!!.getGameArguments(),
                    context.minecraftManifest!!.getJVMArguments().let { if (it.isEmpty()) DEFAULT_JVM_ARGUMENTS else it })
            }
            SemVer.parse(context.selectedMinecraftVersion!!.id) >= SemVer(1, 13, 0) -> {
                // Prepend Forge arguments
                Pair(listOf(), listOf())
            }
            else -> Pair(
                listOf(
                    Argument(
                        listOf(),
                        context.forgeManifest!!.minecraftArguments.split(Regex("\\s+"))
                    )
                ),
                listOf(
                    Argument(
                        listOf(),
                        DEFAULT_JVM_ARGS
                    )
                )
            )
        }

        val libs: MutableList<String> = mutableListOf()

        if (context.forgeManifest !== null) {
            for (lib in context.forgeManifest!!.libraries) {
                libs.add(lib.getPath())
            }
        }

        val lock = ClientLock(
            vars,
            StartLock(
                game,
                jvm,
                libs,
                context.forgeManifest?.mainClass ?: context.minecraftManifest!!.mainClass
            )
        )

        Paths.get(context.installLocation.toString(), ".emo/client.json").toFile()
            .writeText(emoKlaxon().toJsonString(lock))
    }
}

class AddProfile : Process<EmoContext> {
    override fun getName() = "emo.add_profile"
    override suspend fun execute(context: EmoContext) {
        context.instance!!.addProfile(
            context.modpack!!,
            context.modpackVersion!!,
            context.installLocation.toString(),
            context.name ?: context.modpack.name
        )
    }
}

class FetchMods : Process<EmoContext> {
    override fun getName() = "emo.fetch_mods"
    override suspend fun execute(context: EmoContext) {
        parallel(context.mods, 10) {
            val path = Paths.get(context.installLocation.toString(), "mods", it.name + ".jar")
            Files.createDirectories(path.parent)

            it.url
                .httpDownload()
                .fileDestination { _, _ -> path.toFile() }
                .response { _ -> }
                .join()
        }

    }
}
