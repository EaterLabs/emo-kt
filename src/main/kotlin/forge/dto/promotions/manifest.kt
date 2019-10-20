package me.eater.emo.forge.dto.promotions

import com.beust.klaxon.*

private val klaxon = Klaxon()

data class Promotions (
    val name: String,
    val promos: Map<String, Promo>,
    val webpath: String,
    val adfocus: String? = null
) {
    fun toJson() = klaxon.toJsonString(this)

    companion object {
        fun fromJson(json: String) = klaxon.parse<Promotions>(json)
    }
}

data class Promo (
    @Json(name = "mcversion")
    val minecraftVersion: String,
    val version: String
)