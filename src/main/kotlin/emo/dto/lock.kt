package me.eater.emo.emo.dto

import me.eater.emo.minecraft.dto.manifest.Argument

data class ClientLock(
    var vars: Map<String, String> = mapOf(),
    var start: StartLock?
)

data class StartLock(
    var gameArguments: List<Argument>,
    var jvmArguments: List<Argument>,
    var extraLibraries: List<String>,
    var mainClass: String
)