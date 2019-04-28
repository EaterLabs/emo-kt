package me.eater.emo

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException
import com.beust.jcommander.Parameters
import com.uchuhimo.konf.Config
import kotlinx.coroutines.runBlocking
import me.eater.emo.emo.*
import me.eater.emo.emo.dto.Profile
import me.eater.emo.forge.*
import me.eater.emo.minecraft.*
import me.eater.emo.utils.Noop
import me.eater.emo.utils.Workflow
import me.eater.emo.utils.WorkflowBuilder
import net.swiftzer.semver.SemVer
import java.nio.file.Paths
import kotlin.system.exitProcess

enum class Target {
    Server,
    Client
}

fun getInstallWorkflow(ctx: EmoContext): Workflow<EmoContext> {
    return WorkflowBuilder<EmoContext>().apply {

        // Minecraft
        bind(FetchVersionsManifest())
        bind(SelectMinecraftVersion())
        bind(FetchMinecraftManifest())
        bind(FetchMinecraftLibraries())
        bind(ExtractNatives())
        bind(FetchMinecraftAssetIndex())
        bind(FetchMinecraftAssets())
        bind(LoadForgeManifest())
        bind(FetchMinecraftJar())

        // Forge v1
        bind(FetchForgeVersions())
        bind(FetchUniversal())
        bind(FetchForgeLibraries())

        // Forge v2
        bind(FetchInstaller())
        bind(RunInstaller())
        bind(ForgeExtractManifest())
        bind(ForgeCleanInstaller())

        // Emo
        bind(CreateEmoProfile())
        bind(CreateEmoClientLock())
        bind(FetchMods())
        bind(AddProfile())

        // Misc
        bind(Noop())

        start("start")

        step("noop", step@{ ctx: EmoContext ->
            if (ctx.forgeVersion?.isStatic() == false && !ctx.minecraftVersion.isStatic()) {
                return@step "forge.fetch_versions"
            }

            return@step "minecraft.fetch_versions"
        }, name = "start", description = "Starting installer")

        step(
            "forge.fetch_versions",
            { if (ctx.selectedMinecraftVersion === null) "minecraft.fetch_versions" else "forge.select_installer" }
        )

        step("minecraft.fetch_versions", "minecraft.select_version")
        step("minecraft.select_version", "minecraft.fetch_manifest")

        step("minecraft.fetch_manifest", "minecraft.fetch_libraries")
        step("minecraft.fetch_libraries", "minecraft.extract_natives")
        step(
            "minecraft.extract_natives",
            { if (it.minecraftManifest!!.hasAssetIndex() && it.target == Target.Client) "minecraft.fetch_assets_index" else "minecraft.fetch_jar" }
        )
        step("minecraft.fetch_assets_index", "minecraft.fetch_assets")
        step("minecraft.fetch_assets", "minecraft.fetch_jar")
        step("minecraft.fetch_jar", {
            when {
                ctx.forgeVersion === null -> "emo.create_profile"
                else -> "forge.fetch_versions"
            }
        })
        step("noop", {
            if (SemVer.parse(ctx.selectedMinecraftVersion!!.id) >= SemVer(1, 13, 0)) {
                "forge.v2.fetch_installer"
            } else {
                "forge.v1.fetch_universal"
            }
        }, name = "forge.select_installer", description = "Selecting correct Forge installer")
        step("forge.v1.fetch_universal", "forge.v1.load_manifest")
        step("forge.v1.load_manifest", "forge.v1.fetch_libraries")
        step("forge.v1.fetch_libraries", "emo.fetch_mods")

        step("forge.v2.fetch_installer", "forge.v2.run_installer")
        step("forge.v2.run_installer", "forge.v2.extract_manifest")
        step("forge.v2.extract_manifest", "forge.v2.clean_installer")
        step("forge.v2.clean_installer", "emo.fetch_mods")

        step("emo.fetch_mods", "emo.create_profile")
        step("emo.create_profile", { if (it.target == Target.Client) "emo.create_client_lock" else null })
        step(
            "emo.create_client_lock",
            { if (it.instance != null && it.modpack != null && it.modpackVersion != null) "emo.add_profile" else null })
        step("emo.add_profile", null)
    }.build(ctx)
}

fun runInstallJob(ctx: EmoContext) {
    val workflow = getInstallWorkflow(ctx)
    workflow.processStarted += {
        println(
            "INFO: ${it.description}"
        )
    }

    workflow.execute()
    runBlocking {
        workflow.waitFor()
    }

    println("INFO: Completed.")
}

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

interface Command {
    fun execute()
}

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

@Parameters(commandDescription = "Initialise a minecraft install based on a profile")
class ImportCommand(
    @Parameter(description = "[install location]", required = true)
    var location: List<String> = arrayListOf(),
    @Parameter(names = ["-p", "--profile"], description = "Path to profile")
    var profile: String? = null
) : Command {
    override fun execute() {
        profile = (profile ?: "${location[0].expandTilde()}/emo.toml").expandTilde()

        val config = Config { addSpec(Profile) }.from.toml.file(Paths.get(profile).toFile())

        val ctx = EmoContext(
            minecraftVersion = VersionSelector(config[Profile.minecraft]),
            forgeVersion = config[Profile.forge]?.let { it -> VersionSelector(it) },
            target = config[Profile.target],
            installLocation = Paths.get(location[0].expandTilde()),
            mods = config[Profile.mods]
        )

        runInstallJob(ctx)
    }
}

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

private fun String.expandTilde(): String {
    if (this.startsWith("~/")) {
        return System.getProperty("user.home") + "/" + this.substring(2)
    }

    return this
}