package me.eater.emo.emo

import com.github.kittinunf.fuel.httpDownload
import me.eater.emo.EmoContext
import me.eater.emo.emo.dto.ClientLock
import me.eater.emo.emo.dto.Profile
import me.eater.emo.emo.dto.StartLock
import me.eater.emo.minecraft.dto.manifest.Argument
import me.eater.emo.minecraft.dto.manifest.emoKlaxon
import me.eater.emo.utils.Process
import me.eater.emo.utils.await
import me.eater.emo.utils.io
import me.eater.emo.utils.parallel
import net.swiftzer.semver.SemVer
import java.nio.file.Files
import java.nio.file.Paths


/**
 * Process that creates the emo profile needed to start this minecraft install
 */
class CreateEmoProfile : Process<EmoContext> {
    override fun getName() = "emo.create_profile"
    override fun getDescription() = "Creating emo profile"

    override suspend fun execute(context: EmoContext) {
        val profile = Profile(
            context.name ?: "emo",
            context.target,
            context.selectedMinecraftVersion!!.id,
            context.selectedForgeVersion,
            context.mods
        )

        val path = Paths.get(context.installLocation.toString(), "emo.json")

        io {
            path.toFile().writeText(profile.toJson())
        }
    }
}

/**
 * Default JVM arguments to be used when none are provided
 */
val DEFAULT_JVM_ARGS: List<String> = listOf(
    "-Djava.library.path=${'$'}{natives_directory}",
    "-Dminecraft.launcher.brand=${'$'}{launcher_name}",
    "-Dminecraft.launcher.version=${'$'}{launcher_version}",
    "-cp",
    "${'$'}{classpath}"
)

/**
 * Default JVM Arguments to be used when none provided
 * @see DEFAULT_JVM_ARGS
 */
val DEFAULT_JVM_ARGUMENTS: List<Argument> = listOf(
    Argument(listOf(), DEFAULT_JVM_ARGS)
)

/**
 * Process that creates a client lock with additional info to start the minecraft client
 */
class CreateEmoClientLock : Process<EmoContext> {
    override fun getName() = "emo.create_client_lock"
    override fun getDescription() = "Creating client lock for emo"

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

        val forgeManifest = context.forgeManifest

        val jvmArguments =
            context.minecraftManifest!!.getJVMArguments().let { if (it.isEmpty()) DEFAULT_JVM_ARGUMENTS else it }

        val (game, jvm) = when {
            forgeManifest == null -> {
                Pair(context.minecraftManifest!!.getGameArguments(), jvmArguments)
            }
            SemVer.parse(context.selectedMinecraftVersion!!.id) >= SemVer(1, 13, 0) -> {
                // Prepend Forge arguments
                Pair(
                    listOf(
                        *forgeManifest.getGameArguments().toTypedArray(),
                        *context.minecraftManifest!!.getGameArguments().toTypedArray()
                    ),
                    listOf(
                        *forgeManifest.getJVMArguments().toTypedArray(),
                        *jvmArguments.toTypedArray()
                    )
                )
            }
            else -> Pair(
                context.forgeManifest!!.getGameArguments(),
                jvmArguments
            )
        }

        val libs: MutableList<String> = mutableListOf()

        if (context.forgeManifest !== null) {
            for (lib in context.forgeManifest!!.getLibraries()) {
                libs.add(lib.getPath() ?: continue)
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

/**
 * Process that adds this profile to the settings
 */
class AddProfile : Process<EmoContext> {
    override fun getName() = "emo.add_profile"
    override fun getDescription() = "Adding profile to settings"
    override suspend fun execute(context: EmoContext) {
        val profile = context.instance!!.addProfile(
            context.modpack!!,
            context.modpackVersion!!,
            context.installLocation.toString(),
            context.name ?: context.modpack.name
        )

        context.profile = profile
    }
}

/**
 * Process that fetches the mods needed for this install
 */
class FetchMods : Process<EmoContext> {
    override fun getName() = "emo.fetch_mods"
    override fun getDescription() = "Fetching mods"
    override suspend fun execute(context: EmoContext) {
        parallel(context.mods, 10) {
            val path = Paths.get(context.installLocation.toString(), "mods", it.name + ".jar")
            Files.createDirectories(path.parent)

            it.url
                .httpDownload()
                .fileDestination { _, _ -> path.toFile() }
                .await()
        }

    }
}
