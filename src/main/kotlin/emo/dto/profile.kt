package me.eater.emo.emo.dto

import me.eater.emo.Target
import me.eater.emo.emo.dto.repository.Mod
import me.eater.emo.emo.settingsKlaxon

data class Profile (
    val name: String,
    val target: Target,
    val minecraft: String,
    val forge: String? = null,
    val mods: List<Mod>
) {
    public fun toJson() = settingsKlaxon.toJsonString(this)

    companion object {
        public fun fromJson(json: String) = settingsKlaxon.parse<Profile>(json)
    }
}