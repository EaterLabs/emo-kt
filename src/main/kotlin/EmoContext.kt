package me.eater.emo

import me.eater.emo.emo.dto.repository.Mod
import me.eater.emo.emo.dto.repository.Modpack
import me.eater.emo.emo.dto.repository.ModpackVersion
import me.eater.emo.minecraft.dto.asset_index.AssetIndex
import me.eater.emo.minecraft.dto.manifest.Artifact
import me.eater.emo.minecraft.dto.manifest.Extract
import me.eater.emo.minecraft.dto.manifest.IManifest
import me.eater.emo.minecraft.dto.manifest.StartManifest
import me.eater.emo.minecraft.dto.minecraft_versions.Version
import me.eater.emo.minecraft.dto.minecraft_versions.VersionsManifest
import java.io.File
import java.nio.file.Path

/**
 * Data object which is used in the installer workflow,
 * carries temporary data used in the installer use [EmoInstance.getEmoContextForModpack] for initialisation.
 * At minimal only [minecraftVersion] and [installLocation] are needed.
 */
class EmoContext(
    /**
     * Version selector for Forge, if null, no forge will be installed
     */
    val forgeVersion: VersionSelector? = null,
    /**
     * [Path] to install location
     */
    val installLocation: Path,
    /**
     * Version selector for Minecraft
     */
    var minecraftVersion: VersionSelector,

    /**
     * [EmoEnvironment] which dictates which native libraries should be installed,
     * and flags should be used when starting
     */
    val environment: EmoEnvironment = EmoEnvironment(),
    /**
     * What install to target, [Target.Client] or [Target.Server]
     */
    val target: Target = Target.Client,
    /**
     * List of [Mod]'s to install
     */
    val mods: List<Mod> = listOf(),
    /**
     * Selected modpack, used in profile creation
     */
    val modpack: Modpack? = null,

    /**
     * Selected modpack version, used in profile creation
     */
    val modpackVersion: ModpackVersion? = null,

    /**
     * Instance used for this install, used in profile creation
     */
    val instance: EmoInstance? = null,

    /**
     * Name used for this install, used in profile creation
     */
    val name: String? = null
) {
    var forgeManifest: StartManifest? = null
    var assetIndex: AssetIndex? = null
    var selectedMinecraftVersion: Version? = null
    var selectedForgeVersion: String? = null
    var minecraftManifest: IManifest? = null
    var forgeInstaller: File? = null
    val extractQueue: ArrayList<Pair<Artifact, Extract>> = arrayListOf()

    private var versionsManifest: VersionsManifest? = null

    fun getVersionsManifest(): VersionsManifest {
        return versionsManifest!!
    }

    fun setVersionsManifest(manifest: VersionsManifest) {
        versionsManifest = manifest
    }
}