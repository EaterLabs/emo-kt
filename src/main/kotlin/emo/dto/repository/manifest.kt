package me.eater.emo.emo.dto.repository

import com.beust.klaxon.Klaxon

private val klaxon = Klaxon()

data class Repository(
    val name: String,
    val description: String = "",
    val logo: String? = null,
    val links: Links = Links(),
    val modpacks: Map<String, Modpack> = mapOf()
) {
    public fun toJson() = klaxon.toJsonString(this)

    companion object {
        public fun fromJson(json: String) = klaxon.parse<Repository>(json)
    }
}

data class Links(
    val homepage: String? = null,
    val donate: String? = null
)

data class Modpack(
    val id: String,
    val logo: String? = null,
    val links: Links = Links(),
    val name: String,
    val description: String = "",
    val versions: Map<String, Version>,
    val authors: List<Author> = listOf()
)

data class Author(
    val name: String,
    val email: String,
    val links: Links = Links()
)

data class Version(
    val version: String,
    val channel: String = "release",
    val mods: List<Mod>,
    val message: String = "",
    val minecraft: String,
    val forge: String? = null
)

data class Mod(
    val name: String,
    val url: String
)
