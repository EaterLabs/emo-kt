package me.eater.emo.minecraft.dto.asset_index

import com.beust.klaxon.*

private val klaxon = Klaxon()

data class AssetIndex (
    val objects: Map<String, Object>
) {
    public fun toJson() = klaxon.toJsonString(this)

    companion object {
        public fun fromJson(json: String) = klaxon.parse<AssetIndex>(json)
    }
}

data class Object (
    val hash: String,
    val size: Long
)
