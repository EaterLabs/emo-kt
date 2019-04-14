package me.eater.emo.minecraft.dto.minecraft_versions


class VersionsManifest(
    val latest: Latest,
    val versions: Array<Version>
)

class Version(
    val id: String,
    val type: String,
    val url: String,
    val time: String,
    val releaseTime: String
)

class Latest(val release: String, val snapshot: String)