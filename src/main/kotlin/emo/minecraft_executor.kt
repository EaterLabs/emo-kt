package me.eater.emo.emo

import com.uchuhimo.konf.Config
import me.eater.emo.Account
import me.eater.emo.EmoEnvironment
import me.eater.emo.Target
import me.eater.emo.emo.dto.ClientLock
import me.eater.emo.emo.dto.Profile
import me.eater.emo.minecraft.dto.manifest.emoKlaxon
import me.eater.emo.minecraft.dto.manifest.parseManifest
import java.nio.file.Paths

class MinecraftExecutor(val profileLocation: String, val account: Account) {
    var process: Process? = null

    fun execute(): Process {
        val config = Config { addSpec(Profile) }
        config.from.toml.file(Paths.get(profileLocation, "emo.toml").toFile())

        if (config[Profile.target] == Target.Server) {
            // TODO
        }

        val clientLock: ClientLock = emoKlaxon()
            .parse(Paths.get(profileLocation, ".emo/client.json").toFile())!!

        val manifest = parseManifest(Paths.get(profileLocation, ".emo/minecraft.json").toFile().readText())
        val profile = EmoEnvironment()

        val classpath: MutableList<String> =
            manifest.getLibraries().filter { it.downloads.artifact !== null && profile.passesRules(it.rules) }
                .map { "libraries/" + it.downloads.artifact!!.path }
                .toMutableList()

        classpath.addAll(clientLock.start?.extraLibraries?.map { "libraries/$it" } ?: listOf())
        classpath.add("minecraft.jar")

        val vars = hashMapOf(
            Pair("classpath", classpath.joinToString(if (profile.osName == "windows") ";" else ":")),
            Pair("user_type", "mojang"),
            Pair("auth_uuid", account.uuid),
            Pair("auth_player_name", account.displayName),
            Pair("auth_access_token", account.accessToken),
            Pair("game_directory", profileLocation)
        ) + clientLock.vars

        val gameArgs = clientLock.start!!.gameArguments
            .filter { profile.passesRules(it.rules) }
            .flatMap { it.value }
            .map { it.replace(Regex("\\$\\{([^\\}]+)\\}")) { vars[it.groupValues[1]] ?: "" } }

        val jvmArgs = clientLock.start!!.jvmArguments
            .filter { profile.passesRules(it.rules) }
            .flatMap { it.value }
            .map { it.replace(Regex("\\$\\{([^\\}]+)\\}")) { vars[it.groupValues[1]] ?: "" } }

        val args = listOf("java") + jvmArgs + listOf(clientLock.start!!.mainClass) + gameArgs

        process = ProcessBuilder().apply {
            command(args)
            directory(Paths.get(profileLocation).toFile())
            redirectOutput(ProcessBuilder.Redirect.INHERIT)
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()

        return process!!
    }
}