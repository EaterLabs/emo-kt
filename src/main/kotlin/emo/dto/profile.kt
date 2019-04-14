package me.eater.emo.emo.dto

import com.uchuhimo.konf.ConfigSpec
import me.eater.emo.EmoMod
import me.eater.emo.Target

object Profile : ConfigSpec("emo") {
    val name by optional("emo")
    val target by optional(Target.Client)
    val minecraft by required<String>()
    val forge by required<String?>()
    val mods by optional<List<EmoMod>>(listOf())
}