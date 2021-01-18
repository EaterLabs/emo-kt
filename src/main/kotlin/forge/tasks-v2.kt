package me.eater.emo.forge

import com.github.kittinunf.fuel.httpDownload
import me.eater.emo.EmoContext
import me.eater.emo.Target
import me.eater.emo.minecraft.dto.manifest.emoKlaxon
import me.eater.emo.utils.Process
import me.eater.emo.utils.await
import me.eater.emo.utils.io
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Paths
import java.util.function.Predicate
import java.util.jar.JarFile

/**
 * Process that fetches the Forge installer
 */
class FetchInstaller : Process<EmoContext> {
    override fun getName() = "forge.v2.fetch_installer"
    override fun getDescription() = "Fetching Forge installer"

    override suspend fun execute(context: EmoContext) {
        val versionTuple = "${context.selectedMinecraftVersion!!.id}-${context.selectedForgeVersion!!}"
        val artifactUrl =
            "https://files.minecraftforge.net/maven/net/minecraftforge/forge/$versionTuple/forge-$versionTuple-installer.jar"
        io {
            val file = File.createTempFile("emo.", ".jar")

            artifactUrl
                .httpDownload()
                .fileDestination { _, _ -> file }
                .await()

            context.forgeInstaller = file
        }
    }
}

/**
 * Process that runs the installer by the use of a classloader and loads of reflection
 */
class RunInstaller : Process<EmoContext> {
    override fun getName() = "forge.v2.run_installer"
    override fun getDescription() = "Running Forge installer"

    override suspend fun execute(context: EmoContext) {
        val installer = context.forgeInstaller!!
        val loader = URLClassLoader.newInstance(arrayOf(installer.toURI().toURL()))
        val prefix = "net.minecraftforge.installer"

        val progressCallbackClass = loader.loadClass("$prefix.actions.ProgressCallback")
        val progressCallback = progressCallbackClass.getDeclaredField("TO_STD_OUT").get(null)
        val util = loader.loadClass("$prefix.json.Util")
        val install = loader.loadClass("$prefix.json.Install")
        val installerClass = "$prefix.actions.${when (context.target) {
            Target.Server -> "ServerInstall"
            Target.Client -> "ClientInstall"
        }}"
        val installActionClass = loader.loadClass(installerClass)
        val actionClass = loader.loadClass("$prefix.actions.Action")
        val installProfile = util.getDeclaredMethod("loadInstallProfile").invoke(null)
        val installAction =
            installActionClass.getConstructor(install, progressCallbackClass)
                .newInstance(installProfile, progressCallback)

        val libraryDir = Paths.get(context.installLocation.toString(), "libraries").toFile()
        val minecraftLoc = Paths.get(context.installLocation.toString(), when (context.target) {
            Target.Client -> "minecraft.jar"
            Target.Server -> "minecraft_server.${context.minecraftVersion.selector}.jar"
        }).toFile()

        val successLibraries = try {
            actionClass.getDeclaredMethod("downloadLibraries", File::class.java, Predicate::class.java)
                .also {
                    it.isAccessible = true
                }
                .invoke(installAction, libraryDir, Predicate<String> { true }) as Boolean
        } catch (e: Throwable) {
            false
        }

        if (!successLibraries) {
            throw Error("Failed to download libraries needed for Forge")
        }

        val processors = actionClass.getDeclaredField("processors")
            .also {
                it.isAccessible = true
            }
            .get(installAction)
        val postProcessorsClass = loader.loadClass("$prefix.actions.PostProcessors")

        val successPostProcess =
            postProcessorsClass.getDeclaredMethod("process", File::class.java, File::class.java).invoke(
                processors,
                libraryDir,
                minecraftLoc
            ) as Boolean

        if (!successPostProcess) {
            throw Error("Failed to post process Minecraft for Forge")
        }
    }
}

/**
 * Process that extracts the manifest from the forge installer
 */
class ForgeExtractManifest : Process<EmoContext> {
    override fun getName() = "forge.v2.extract_manifest"
    override fun getDescription() = "Extracting Forge install manifest"

    override suspend fun execute(context: EmoContext) {
        val jar = JarFile(context.forgeInstaller!!)
        val json = String(jar.getInputStream(jar.getJarEntry("version.json")).readBytes())
        context.forgeManifest = emoKlaxon().parse<me.eater.emo.forge.dto.manifest.v2.Manifest>(json)
        jar.close()
    }
}

/**
 * Process that deletes the forge installer
 */
class ForgeCleanInstaller : Process<EmoContext> {
    override fun getName() = "forge.v2.clean_installer"
    override fun getDescription() = "Removing Forge installer"

    override suspend fun execute(context: EmoContext) {
        context.forgeInstaller!!.delete()
    }
}