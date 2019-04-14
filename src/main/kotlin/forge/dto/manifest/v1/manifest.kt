package me.eater.emo.forge.dto.manifest.v1



import com.beust.klaxon.*

private fun <T> Klaxon.convert(k: kotlin.reflect.KClass<*>, fromJson: (JsonValue) -> T, toJson: (T) -> String, isUnion: Boolean = false) =
    this.converter(object: Converter {
        @Suppress("UNCHECKED_CAST")
        override fun toJson(value: Any)        = toJson(value as T)
        override fun fromJson(jv: JsonValue)   = fromJson(jv) as Any
        override fun canConvert(cls: Class<*>) = cls == k.java || (isUnion && cls.superclass == k.java)
    })

private val klaxon = Klaxon()
    .convert(JsonObject::class, { it.obj!! }, { it.toJsonString() })

data class Manifest (
    val id: String,
    val time: String,
    val releaseTime: String,
    val type: String,
    val minecraftArguments: String,
    val mainClass: String,
    val inheritsFrom: String,
    val jar: String,
    val logging: Logging,
    val libraries: List<Library>
) {
    public fun toJson() = klaxon.toJsonString(this)

    companion object {
        public fun fromJson(json: String) = klaxon.parse<Manifest>(json)
    }
}

data class Library (
    val name: String,
    val url: String? = null,
    val serverreq: Boolean? = null,
    val checksums: List<String>? = null,
    val clientreq: Boolean? = null
) {
    fun getPath(): String {
        val (group, name, version) = name.split(':')
        return "${group.replace('.', '/')}/$name/$version/$name-$version.jar"
    }
}

typealias Logging = JsonObject
