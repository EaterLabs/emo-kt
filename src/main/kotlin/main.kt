package me.eater.emo

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException
import com.beust.jcommander.Parameters
import kotlinx.coroutines.runBlocking
import me.eater.emo.emo.MinecraftExecutor
import me.eater.emo.emo.dto.Profile
import java.io.File
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Target, either Server or Client
 */
enum class Target {
    Server,
    Client;

    override fun toString(): String =
        when (this) {
            Client -> "client"
            Server -> "server"
        }


    companion object {
        fun fromString(value: String): Target =
            when (value.toLowerCase()) {
                "server" -> Server
                else -> Client
            }

    }
}

/**
 * Main entry via CLI
 */
fun main(args: Array<String>) {
    val commands = hashMapOf(
        Pair("init", InitCommand()),
        Pair("login", LoginCommand()),
        Pair("start", StartCommand()),
        Pair("import", ImportCommand())
    )

    val jc = JCommander.newBuilder().apply {
        for (command in commands) {
            addCommand(command.key, command.value)
        }
    }.build()

    try {
        jc.parse(*args)
    } catch (e: ParameterException) {
        println(e.message)
        e.usage()
        exitProcess(1)
    }

    when (jc.parsedCommand) {
        in commands -> commands[jc.parsedCommand]!!.execute()
        else -> {
            jc.usage()
            exitProcess(1)
        }
    }
}

/**
 * Command abstraction
 */
interface Command {
    /**
     * Execute command
     */
    fun execute()
}

/**
 * Command for logging in
 */
@Parameters(commandDescription = "Log in a account to use with emo")
class LoginCommand(
    @Parameter(description = "[username]", required = true)
    var username: List<String> = arrayListOf()
) : Command {
    override fun execute() {
        print("Please enter password for ${username[0]}: ")
        System.out.flush()
        val pw = System.console()?.readPassword() ?: readLine()

        if (pw === null) {
            println("Can't read password (non-console tty?)")
            exitProcess(1)
        }

        runBlocking {
            val acc = try {
                EmoInstance().accountLogIn(username[0], pw as String)
            } catch (error: Throwable) {
                print(error.message)
                exitProcess(1)
            }

            println("User ${acc.displayName} saved!")
        }
    }
}


/**
 * Command to import a profile and install a new minecraft profile based on that
 */
@Parameters(commandDescription = "Initialise a minecraft install based on a profile")
class ImportCommand(
    @Parameter(description = "[install location]", required = true)
    var location: List<String> = arrayListOf(),
    @Parameter(names = ["-p", "--profile"], description = "Path to profile")
    var profile: String? = null
) : Command {
    override fun execute() {
        profile = (profile ?: "${location[0].expandTilde()}/emo.json").expandTilde()

        val config = Profile.fromJson(File(profile).readText())!!

        val ctx = EmoContext(
            minecraftVersion = VersionSelector(config.minecraft),
            forgeVersion = VersionSelector.fromStringOrNull(config.forge),
            target = config.target,
            installLocation = Paths.get(location[0].expandTilde()),
            mods = config.mods
        )

        runInstallJob(ctx)
    }
}

/**
 * Start Minecraft from a profile directory
 */
@Parameters(commandDescription = "Start minecraft from a emo profile install")
class StartCommand(
    @Parameter(description = "[profile location]", required = true)
    var location: List<String> = arrayListOf()
) : Command {
    override fun execute() {
        val executor = runBlocking {
            MinecraftExecutor(location[0].expandTilde(), EmoInstance().getAccountViaAuthentication())
        }

        executor
            .execute()
            .waitFor()
    }
}

/**
 * Initialise a new Minecraft install by versions
 */
@Parameters(commandDescription = "Initialise a new minecraft install")
class InitCommand(
    @Parameter(names = ["--target", "-t"], description = "What target to install")
    var target: Target = Target.Client,
    @Parameter(names = ["--minecraft", "-M"], description = "What version of minecraft to install")
    var minecraft: String = "latest",
    @Parameter(names = ["--forge", "-F"], description = "What version of Forge to install, omit to disable forge")
    var forge: String? = null,
    @Parameter(description = "[install location]", required = true)
    var location: List<String> = arrayListOf()
) : Command {
    override fun execute() {
        val ctx = EmoContext(
            forgeVersion = forge?.let { VersionSelector(it) },
            installLocation = Paths.get(location[0].expandTilde()),
            minecraftVersion = VersionSelector(minecraft),
            target = this.target
        )

        runInstallJob(ctx)
    }
}

/**
 * Function to expand the tilde to the users home dir.
 */
private fun String.expandTilde(): String {
    if (this.startsWith("~/")) {
        return System.getProperty("user.home") + "/" + this.substring(2)
    }

    return this
}