package me.eater.emo.minecraft.dto.nbt

data class Server (
    val name: String,
    val ip: String,
    val icon: String,
    val acceptTextures: Boolean
)