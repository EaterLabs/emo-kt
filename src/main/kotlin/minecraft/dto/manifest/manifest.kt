package me.eater.emo.minecraft.dto.manifest

import com.beust.klaxon.*
import me.eater.emo.minecraft.dto.manifest.v18.Manifest as ManifestV18
import me.eater.emo.minecraft.dto.manifest.v21.Manifest as ManifestV21

interface StartManifest {
    val id: String
    val type: String
    val mainClass: String
    fun getJVMArguments(): List<Argument>
    fun getGameArguments(): List<Argument>
    fun getLibraries(): Iterable<ILibrary>
}

interface IManifest : StartManifest {
    fun hasAssetIndex(): Boolean
    fun getAssetIndexUrl(): String
    fun getAssetIndexId(): String
    fun getMinecraftServerUrl(): String
    fun getMinecraftClientUrl(): String
    override fun getLibraries(): Iterable<Library>
}

interface ILibrary {
    fun getPath(): String?
}

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

fun emoKlaxon() = Klaxon()
    .convert(Argument::class, { Argument.fromJson(it) }, { findConverterFromClass(Any::class.java, null).toJson(it) }, true)
    .convert(Action::class, { Action.fromValue(it.string!!) }, { toJsonString(it.value) })
    .convert(Regex::class, {
        when (it.inside) {
            is JsonObject -> Regex(it.obj!!.get("pattern") as String)
            is String -> Regex(it.string!!)
            else -> throw IllegalArgumentException()
        }
    }, { toJsonString(it.pattern) })

private val klaxonEmo = emoKlaxon()

class Argument(
    val rules: List<Rule> = listOf(),
    val value: List<String> = listOf()
) {
    companion object {
        public fun fromJson(jv: JsonValue): Argument = when (jv.inside) {
            is JsonObject -> {
                Argument(
                    klaxonEmo.parseFromJsonArray(jv.obj!!.array<Any?>("rules") ?: JsonArray<Any?>(listOf()))
                        ?: listOf(), when (val value = jv.obj!!.get("value")) {
                        is String -> listOf(value)
                        is JsonArray<*> -> Klaxon().parseFromJsonArray(value)!!
                        else -> throw IllegalArgumentException()
                    }
                )
            }
            is String -> Argument(listOf(), listOf(jv.string!!))
            else -> throw IllegalArgumentException()
        }
    }
}

class VersionManifest(
    val minimumLauncherVersion: Long
)

data class Artifact(
    val sha1: String,
    val size: Long,
    val url: String,
    val path: String? = null
)

enum class Action(val value: String) {
    Allow("allow"),
    Disallow("disallow");

    companion object {
        public fun fromValue(value: String): Action = when (value.toLowerCase()) {
            "allow" -> Allow
            "disallow" -> Disallow
            else -> throw IllegalArgumentException()
        }
    }
}

data class OS(
    val name: String? = null,
    val version: Regex? = null,
    val arch: String? = null
)

data class Features(
    @Json(name = "is_demo_user")
    val demoUser: Boolean? = null,
    @Json(name = "has_custom_resolution")
    val customResolution: Boolean? = null
)

data class Rule(
    val action: Action,
    val os: OS? = null,
    val features: Features? = null
)

data class Library(
    val downloads: LibraryDownloads,
    val name: String,
    val natives: Map<String, String> = hashMapOf(),
    val extract: Extract = Extract(),
    val rules: List<Rule> = listOf()
) : ILibrary {
    override fun getPath(): String? {
        return downloads.artifact?.path
    }
}

data class LibraryDownloads(
    val artifact: Artifact? = null,
    val classifiers: Map<String, Artifact> = hashMapOf()
)

data class Extract(
    val exclude: List<String> = listOf("META-INF/")
)

fun parseManifest(manifest: String): IManifest {
    val manifestVersion = Klaxon().parse<VersionManifest>(manifest)!!.minimumLauncherVersion

    return when {
        (manifestVersion >= 21) -> {
            ManifestV21.fromJson(manifest)!!
        }

        (manifestVersion >= 18) -> {
            ManifestV18.fromJson(manifest)!!
        }

        else -> {
            throw Error("Don't have a manifest definition for Minecraft manifest version $manifestVersion")
        }
    }
}

data class Arguments(
    val game: List<Argument> = listOf(),
    val jvm: List<Argument> = listOf()
)