package me.eater.emo.minecraft.dto.manifest.v21

import com.beust.klaxon.*
import me.eater.emo.minecraft.dto.manifest.*

private fun <T> Klaxon.convert(
    k: kotlin.reflect.KClass<*>,
    fromJson: (JsonValue) -> T,
    toJson: (T) -> String,
    isUnion: Boolean = false
) =
    this.converter(object : Converter {
        @Suppress("UNCHECKED_CAST")
        override fun toJson(value: Any) = toJson(value as T)

        override fun fromJson(jv: JsonValue) = fromJson(jv) as Any
        override fun canConvert(cls: Class<*>) = cls == k.java || (isUnion && cls.superclass == k.java)
    })

private val klaxon = emoKlaxon()

data class Manifest(
    val arguments: Arguments,
    val assetIndex: AssetIndex,
    val assets: String,
    val downloads: ManifestDownloads,
    override val id: String,
    val libraries: List<Library>,
    val logging: Logging,
    override val mainClass: String,
    val minimumLauncherVersion: Long,
    val releaseTime: String,
    val time: String,
    override val type: String
) : IManifest {
    override fun getJVMArguments() = arguments.jvm
    override fun getGameArguments() = arguments.game
    override fun getLibraries(): Iterable<Library> = libraries
    override fun hasAssetIndex(): Boolean = true
    override fun getAssetIndexUrl() = assetIndex.url
    override fun getAssetIndexId() = assetIndex.id
    override fun getMinecraftServerUrl() = downloads.server.url
    override fun getMinecraftClientUrl() = downloads.client.url

    // JSON
    public fun toJson() = klaxon.toJsonString(this)

    companion object {
        public fun fromJson(json: String) = klaxon.parse<Manifest>(json)
    }
}

data class AssetIndex(
    val id: String,
    val sha1: String,
    val size: Long,
    val totalSize: Long? = null,
    val url: String
)

data class ManifestDownloads(
    val client: Artifact,
    val server: Artifact
)

data class Logging(
    val client: LoggingClient
)

data class LoggingClient(
    val argument: String,
    val file: AssetIndex,
    val type: String
)
