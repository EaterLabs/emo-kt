package me.eater.emo.minecraft.dto.launcher

import com.beust.klaxon.Json
import com.beust.klaxon.Klaxon

private val klaxon = Klaxon()

data class LauncherManifest(
    val java: JavaLauncher,
    val linux: LauncherInfo,
    val osx: LauncherInfo,
    val windows: LauncherInfo
) {
    public fun toJson() = klaxon.toJsonString(this)

    companion object {
        public fun fromJson(json: String) = klaxon.parse<LauncherManifest>(json)
    }

    operator fun get(osName: String): LauncherInfo? =
        when (osName) {
            "linux" -> linux
            "windows" -> windows
            "osx" -> osx
            else -> null
        }

}

data class JavaLauncher(
    val lzma: LauncherArtifact,
    val sha1: String
)

data class VersionInfo(
    val commit: String,
    val name: String
)

data class JavaArtifactBundle(
    val jdk: LauncherArtifact,
    val jre: LauncherArtifact
)

data class LauncherArtifact(
    val sha1: String,
    val url: String,
    val version: String? = null
)

data class LauncherInfo(
    @Json(name = "32")
    val thirtyTwo: JavaArtifactBundle? = null,
    @Json(name = "64")
    val sixtyFour: JavaArtifactBundle? = null,
    val apphash: String? = null,
    val applink: String,
    val downloadhash: String,
    val rolloutPercent: Long = 0,
    val versions: Map<String, VersionInfo>
)
