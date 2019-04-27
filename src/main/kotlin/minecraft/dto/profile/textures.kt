package me.eater.emo.minecraft.dto.profile

import com.beust.klaxon.*

private val klaxon = Klaxon()

data class Textures (
    val timestamp: Long,

    @Json(name = "profileId")
    val profileID: String,

    val profileName: String,
    val textures: TexturesClass
) {
    public fun toJson() = klaxon.toJsonString(this)

    companion object {
        public fun fromJson(json: String) = klaxon.parse<Textures>(json)
    }
}

data class TexturesClass (
    @Json(name = "SKIN")
    val skin: Texture? = null,

    @Json(name = "CAPE")
    val cape: Texture? = null
)

data class Texture (
    val url: String
)
