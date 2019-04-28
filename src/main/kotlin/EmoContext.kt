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

class EmoContext(
    val forgeVersion: VersionSelector? = null,
    val installLocation: Path,
    var minecraftVersion: VersionSelector,
    val environment: EmoEnvironment = EmoEnvironment(),
    val target: Target = Target.Client,
    val mods: List<Mod> = listOf(),
    val modpack: Modpack? = null,
    val modpackVersion: ModpackVersion? = null,
    val instance: EmoInstance? = null,
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