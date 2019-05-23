package me.eater.emo.forge.dto.manifest.v2

import com.beust.klaxon.Klaxon
import me.eater.emo.minecraft.dto.manifest.ILibrary
import me.eater.emo.minecraft.dto.manifest.Library
import me.eater.emo.minecraft.dto.manifest.StartManifest
import me.eater.emo.minecraft.dto.manifest.Arguments

private val klaxon = Klaxon()

data class Manifest(
    override val id: String,
    val time: String,
    val releaseTime: String,
    override val type: String,
    override val mainClass: String,
    val inheritsFrom: String,
    val arguments: Arguments,
    val libraries: List<Library>
) : StartManifest {
    public fun toJson() = klaxon.toJsonString(this)

    override fun getJVMArguments() = arguments.jvm
    override fun getGameArguments() = arguments.game
    override fun getLibraries(): Iterable<ILibrary> = libraries

    companion object {
        public fun fromJson(json: String) = klaxon.parse<Manifest>(json)
    }
}