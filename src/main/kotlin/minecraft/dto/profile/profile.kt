package me.eater.emo.minecraft.dto.profile

import com.beust.klaxon.*

private val klaxon = Klaxon()

data class Profile (
    val id: String,
    val name: String,
    val properties: List<Property>
) {
    public fun toJson() = klaxon.toJsonString(this)

    companion object {
        public fun fromJson(json: String) = klaxon.parse<Profile>(json)
    }
}

data class Property (
    val name: String,
    val value: String
)
