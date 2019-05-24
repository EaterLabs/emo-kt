package me.eater.emo

import me.eater.emo.emo.Profile
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
    val name: String? = null,
    /**
     * Overlay used to distribute resource packs and configs
     */
    val overlay: String? = modpackVersion?.overlay
) {
    /**
     * Holds Forge manifest, if available
     */
    var forgeManifest: StartManifest? = null
    /**
     * Holds [AssetIndex] for current install
     */
    var assetIndex: AssetIndex? = null

    /**
     * Holds the selected minecraft version, [Version] object, which contains the url to the manifest
     */
    var selectedMinecraftVersion: Version? = null
    /**
     * Holds the selected Forge version
     */
    var selectedForgeVersion: String? = null
    /**
     * Holds the install manifest of Minecraft
     */
    var minecraftManifest: IManifest? = null
    /**
     * Holds the File of the downloaded Forge installer
     */
    var forgeInstaller: File? = null

    /**
     * Queue for native libraries that should be extracted
     */
    val extractQueue: ArrayList<Pair<Artifact, Extract>> = arrayListOf()

    /**
     * The profile for this install
     */
    var profile: Profile? = null

    private var versionsManifest: VersionsManifest? = null

    /**
     * Get the current [VersionsManifest]
     */
    fun getVersionsManifest(): VersionsManifest {
        return versionsManifest!!
    }

    /**
     * Set the current [VersionsManifest]
     */
    fun setVersionsManifest(manifest: VersionsManifest) {
        versionsManifest = manifest
    }
}