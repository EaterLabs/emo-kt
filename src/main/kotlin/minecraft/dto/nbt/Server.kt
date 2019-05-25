package me.eater.emo.minecraft.dto.nbt

import com.flowpowered.nbt.ByteTag
import com.flowpowered.nbt.StringTag
import com.flowpowered.nbt.Tag

data class Server(
    val name: String,
    val ip: String,
    val icon: String? = null,
    val acceptTextures: Boolean? = null
) {
    fun toNBTMap(): Map<String, Tag<*>> {
        val mutableMap = mutableMapOf<String, Tag<*>>()
        mutableMap["name"] = StringTag("name", name)
        mutableMap["ip"] = StringTag("ip", ip)
        if (icon != null) {
            mutableMap["icon"] = StringTag("icon", icon)
        }

        if (acceptTextures != null) {
            mutableMap["acceptTextures"] = ByteTag("acceptTextures", acceptTextures)
        }

        return mutableMap
    }
}