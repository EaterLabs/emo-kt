package me.eater.emo.emo.dto.repository

import com.beust.klaxon.Converter
import com.beust.klaxon.JsonValue
import com.beust.klaxon.Klaxon


private fun <T> Klaxon.convert(
    k: kotlin.reflect.KClass<*>,
    fromJson: Klaxon.(JsonValue) -> T,
    toJson: Klaxon.(T) -> String,
    isUnion: Boolean = false
) =
    this.converter(object : Converter {
        @Suppress("UNCHECKED_CAST")
        override fun toJson(value: Any) = toJson.invoke(this@convert, value as T)

        override fun fromJson(jv: JsonValue) = fromJson.invoke(this@convert, jv) as Any
        override fun canConvert(cls: Class<*>) = cls == k.java || (isUnion && cls.superclass == k.java)
    })

private val klaxon = Klaxon()
    .convert(ModpackVersion.Channel::class, { ModpackVersion.Channel.fromString(it.string!!) }, { toJsonString("$it") })


open class Repository(
    val name: String,
    val description: String = "",
    val logo: String? = null,
    val links: Links = Links(),
    open val modpacks: Map<String, ModpackWithVersions> = mapOf()
) {
    public fun toJson() = klaxon.toJsonString(this)

    companion object {
        public fun fromJson(json: String) = klaxon.parse<Repository>(json)
    }
}

class MutableRepository(
    name: String,
    description: String = "",
    logo: String? = null,
    links: Links = Links(),
    override val modpacks: MutableMap<String, ModpackWithVersions> = mutableMapOf()
) : Repository(name, description, logo, links)

data class Links(
    val homepage: String? = null,
    val donate: String? = null
)

/**
 * A definition of a modpack, excluding versions for serialisation
 */
open class Modpack(
    /**
     * Id of this modpack, should be [author-handle]/[modpack-name]
     */
    val id: String,
    /**
     * URL to logo of this modpack
     */
    val logo: String? = null,
    /**
     * Links related to modpack
     */
    val links: Links = Links(),

    /**
     * Display name of this modpack
     */
    val name: String,

    /**
     * Description of this modpack
     */
    val description: String = "",

    /**
     * Authors of this modpack
     */
    val authors: List<Author> = listOf()
) {
    open fun withoutVersions() = this
}

/**
 * Modpack with versions, used in repository
 */
class ModpackWithVersions(
    id: String,
    logo: String? = null,
    links: Links = Links(),
    name: String,
    description: String = "",
    authors: List<Author> = listOf(),
    /**
     * All available versions of this modpack
     */
    val versions: Map<String, ModpackVersion>
) : Modpack(id, logo, links, name, description, authors) {
    /**
     * Get this modpack without all versions, useful for serialisation
     */
    override fun withoutVersions() = Modpack(id, logo, links, name, description, authors)
}

/**
 * Info about author
 */
data class Author(
    /**
     * Name of author
     */
    val name: String,
    /**
     * Email-address of author
     */
    val email: String? = null,

    /**
     * Links of author
     */
    val links: Links = Links()
)

/**
 * A version of modpack
 */
data class ModpackVersion(
    /**
     * Version string, MUST be SemVer
     */
    val version: String,

    /**
     * Which channel this version is released
     */
    val channel: Channel = Channel.Release,
    val mods: List<Mod>,
    val message: String = "",
    val minecraft: String,
    val forge: String? = null
) {

    /**
     * Channels for a modpack version, only 3 defined right now: release, beta, and alpha
     */
    enum class Channel(val value: String) {
        Release("release"),
        Beta("beta"),
        Alpha("alpha");

        override fun toString(): String = value

        companion object {
            fun fromString(value: String) {
                when (value) {
                    "release" -> Release
                    "beta" -> Beta
                    "alpha" -> Alpha
                    else -> throw IllegalArgumentException()
                }
            }
        }
    }
}

/**
 * Definition for a mod
 */
data class Mod(
    /**
     * (File)name of mod
     */
    val name: String,

    /**
     * URL to download mod from
     */
    val url: String
)