package me.eater.emo

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException
import com.beust.jcommander.Parameters
import com.mojang.authlib.Agent
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import com.uchuhimo.konf.Config
import me.eater.emo.emo.CreateEmoClientLock
import me.eater.emo.emo.CreateEmoProfile
import me.eater.emo.emo.FetchMods
import me.eater.emo.emo.Settings
import me.eater.emo.emo.dto.ClientLock
import me.eater.emo.emo.dto.Profile
import me.eater.emo.forge.*
import me.eater.emo.forge.dto.manifest.v1.Manifest
import me.eater.emo.minecraft.*
import me.eater.emo.minecraft.dto.asset_index.AssetIndex
import me.eater.emo.minecraft.dto.manifest.*
import me.eater.emo.minecraft.dto.minecraft_versions.Version
import me.eater.emo.minecraft.dto.minecraft_versions.VersionsManifest
import me.eater.emo.utils.Noop
import me.eater.emo.utils.Workflow
import me.eater.emo.utils.WorkflowBuilder
import me.eater.emo.utils.slice
import net.swiftzer.semver.SemVer
import java.net.Proxy
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

enum class Target {
    Server,
    Client
}

data class EmoMod (val url: String, val name: String)

class EmoContext(
    val forgeVersion: VersionSelector? = null,
    val installLocation: Path,
    var minecraftVersion: VersionSelector,
    val profile: EmoProfile = EmoProfile(),
    val target: Target = Target.Client,
    val mods: List<EmoMod> = listOf()
) {
    var forgeManifest: Manifest? = null
    var assetIndex: AssetIndex? = null
    var selectedMinecraftVersion: Version? = null
    var selectedForgeVersion: String? = null
    var minecraftManifest: IManifest? = null
    val extractQueue: ArrayList<Pair<Artifact, Extract>> = arrayListOf()

    private var versionsManifest: VersionsManifest? = null

    fun getVersionsManifest(): VersionsManifest {
        return versionsManifest!!
    }

    fun setVersionsManifest(manifest: VersionsManifest) {
        versionsManifest = manifest
    }
}

class EmoProfile {
    val osName: String = System.getProperty("os.name").toLowerCase().run {
        when {
            this == "linux" -> "linux"
            this.contains("mac", true) -> "osx"
            this.contains("windows", true) -> "windows"
            else -> "other"
        }
    }
    private val osVersion: String = System.getProperty("os.version")
    private val osArch: String = System.getProperty("os.arch").let {
        when (it) {
            "amd64" -> "x86"
            "x86_64" -> "x86"
            else -> it
        }
    }



    fun passesOSCheck(check: OS): Boolean {
        if (check.arch !== null && check.arch != osArch) {
            return false
        }

        if (check.version !== null && !check.version.matches(osVersion)) {
            return false
        }

        if (check.name !== null && check.name != osName) {
            return false
        }

        return true
    }

    fun passesRule(rule: Rule): Boolean {
        val result: Boolean = rule.run result@{
            if (os !== null && !passesOSCheck(os)) {
                return@result false
            }

            if (features !== null) {
                return@result false
            }

            return@result true
        }

        if (rule.action == Action.Disallow) {
            return !result
        }

        return result
    }

    fun passesRules(rules: Iterable<Rule>): Boolean {
        for (rule in rules) {
            if (!passesRule(rule)) {
                return false
            }
        }

        return true
    }

    fun selectNative(library: Library): Artifact? {
        if (library.natives.containsKey(osName)) {
            return library.downloads.classifiers[library.natives[osName]]
        }

        return null
    }
}

class VersionSelector(val selector: String) {
    fun isStatic(): Boolean {
        return selector != "latest" && selector != "latest-snapshot" && selector != "recommended"
    }
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

        // Forge
        bind(FetchForgeVersions())
        bind(FetchInstaller())
        bind(FetchUniversal())
        bind(FetchForgeLibraries())

        // Emo
        bind(CreateEmoProfile())
        bind(CreateEmoClientLock())
        bind(FetchMods())

        // Misc
        bind(Noop())

        start("start")

        step("noop", step@{ ctx: EmoContext ->
            if (ctx.forgeVersion?.isStatic() == false && !ctx.minecraftVersion.isStatic()) {
                return@step "forge.fetch_versions"
            }

            return@step "minecraft.fetch_versions"
        }, name = "start")

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
        }, name = "forge.select_installer")
        step("forge.v1.fetch_universal", "forge.v1.load_manifest")
        step("forge.v1.load_manifest", "forge.v1.fetch_libraries")
        step("forge.v1.fetch_libraries", "emo.create_profile")
        step("emo.create_profile", { if (it.target == Target.Client) "emo.create_client_lock" else null })
        step("emo.create_client_lock", "emo.fetch_mods")
        step("emo.fetch_mods", null)
    }.build(ctx)
}

fun runInstallJob(ctx: EmoContext) {
    val workflow = getInstallWorkflow(ctx)
    workflow.processStarted += {
        println(
            "Start process: ${it.step}${when (it.step) {
                it.process.getName() -> ""
                else -> " (${it.process.getName()})"
            }}"
        )
    }

    workflow.execute()
    workflow.waitFor()

    println("Completed.")
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

        val settings = Settings.load()
        val authService = YggdrasilAuthenticationService(Proxy.NO_PROXY, settings.clientToken)
            .createUserAuthentication(Agent.MINECRAFT)
            .apply {
                setUsername(username[0])
                setPassword(pw.toString())
            }

        authService.logIn()

        if (!authService.isLoggedIn) {
            println("Failed to login")
            exitProcess(1)
        }
        val stor = authService.saveForStorage()
        settings.addAccount(stor)
        settings.save()
        println("User ${authService.selectedProfile.name} saved!")
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
        val config = Config { addSpec(Profile) }
        config.from.toml.file(Paths.get(location[0].expandTilde(), "emo.toml").toFile())

        if (config[Profile.target] == Target.Server) {
            // TODO
        }

        val clientLock: ClientLock = emoKlaxon()
            .parse(Paths.get(location[0], ".emo/client.json").toFile())!!

        val manifest = parseManifest(Paths.get(location[0].expandTilde(), ".emo/minecraft.json").toFile().readText())
        val profile = EmoProfile()

        val classpath: MutableList<String> =
            manifest.getLibraries().filter { it.downloads.artifact !== null && profile.passesRules(it.rules) }
                .map { "libraries/" + it.downloads.artifact!!.path }
                .toMutableList()

        classpath.addAll(clientLock.start?.extraLibraries?.map { "libraries/$it" } ?: listOf())
        classpath.add("minecraft.jar")

        val settings = Settings.load()

        val service = YggdrasilAuthenticationService(Proxy.NO_PROXY, settings.clientToken)
        val auth = service.createUserAuthentication(Agent.MINECRAFT)
        auth.loadFromStorage(settings.getSelectedAccount()!!)
        auth.logIn()

        if (!auth.isLoggedIn) {
            println("Failed to log in")
            exitProcess(1)
        }

        settings.addAccount(auth.saveForStorage())
        settings.save()

        val vars = hashMapOf(
            Pair("classpath", classpath.joinToString(if (profile.osName == "windows") ";" else ":")),
            Pair("user_type", "mojang"),
            Pair("auth_uuid", auth.selectedProfile.id.toString()),
            Pair("auth_player_name", auth.selectedProfile.name),
            Pair("auth_access_token", auth.authenticatedToken),
            Pair("game_directory", location[0].expandTilde())
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

        val process = ProcessBuilder().apply {
            command(args)
            directory(Paths.get(location[0].expandTilde()).toFile())
            redirectOutput(ProcessBuilder.Redirect.INHERIT)
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()

        process.waitFor()
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