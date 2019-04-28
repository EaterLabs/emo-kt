package me.eater.emo.emo

import com.uchuhimo.konf.Config
import me.eater.emo.Account
import me.eater.emo.EmoEnvironment
import me.eater.emo.Target
import me.eater.emo.emo.dto.ClientLock
import me.eater.emo.emo.dto.Profile
import me.eater.emo.minecraft.dto.manifest.Argument
import me.eater.emo.minecraft.dto.manifest.emoKlaxon
import me.eater.emo.minecraft.dto.manifest.parseManifest
import java.nio.file.Paths

class MinecraftExecutor(val profileLocation: String, val account: Account) {
    var process: Process? = null

    /**
     * Start minecraft for given [profileLocation] and [account], returns running [Process]
     */
    fun execute(): Process {
        val config = Config { addSpec(Profile) }
        config.from.toml.file(Paths.get(profileLocation, "emo.toml").toFile())

        val args = when (config[Profile.target]) {
            Target.Client -> {
                val clientLock
                        : ClientLock = emoKlaxon()
                    .parse(Paths.get(profileLocation, ".emo/client.json").toFile())!!

                val manifest = parseManifest(
                    Paths.get(
                        profileLocation,
                        ".emo/minecraft.json"
                    ).toFile().readText()
                )
                val environment = EmoEnvironment()

                val classpath
                        : MutableList<String> =
                    manifest.getLibraries()
                        .filter { it.downloads.artifact !== null && environment.passesRules(it.rules) }
                        .map { "libraries/" + it.downloads.artifact!!.path }
                        .toMutableList()

                classpath.addAll(clientLock.start?.extraLibraries?.map { "libraries/$it" } ?: listOf())
                classpath.add("minecraft.jar")

                // Set default template variables and add variables from clientLock
                val vars = hashMapOf(
                    Pair("classpath", classpath.joinToString(if (environment.osName == "windows") ";" else ":")),
                    Pair("user_type", "mojang"),
                    Pair("auth_uuid", account.uuid),
                    Pair("auth_player_name", account.displayName),
                    Pair("auth_access_token", account.accessToken),
                    Pair("game_directory", profileLocation)
                ) + clientLock.vars

                val gameArgs = processArguments(
                    clientLock.start!!.gameArguments,
                    environment,
                    vars
                )

                val jvmArgs = processArguments(
                    clientLock.start!!.jvmArguments,
                    environment,
                    vars
                )

                listOf("java") + jvmArgs + listOf(clientLock.start!!.mainClass) + gameArgs
            }
            Target.Server -> {
                // -should- work
                listOf(
                    "java",
                    "-jar",
                    when (config[Profile.forge]) {
                        null -> "minecraft_server.${config[Profile.minecraft]}.jar"
                        else -> "forge.jar"
                    },
                    "-nogui"
                )
            }
        }

        process = ProcessBuilder().apply {
            command(args)
            directory(Paths.get(profileLocation).toFile())
            redirectOutput(ProcessBuilder.Redirect.INHERIT)
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()

        return process!!
    }

    private fun processArguments(
        arguments: List<Argument>,
        environment: EmoEnvironment,
        vars: Map<String, String>
    ): List<String> {
        return arguments
            .filter { environment.passesRules(it.rules) }
            .flatMap { it.value }
            .map { s -> s.replace(Regex("\\$\\{([^}]+)}")) { vars[it.groupValues[1]] ?: "" } }
    }
}