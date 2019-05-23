package me.eater.emo

import kotlinx.coroutines.runBlocking
import me.eater.emo.emo.AddProfile
import me.eater.emo.emo.CreateEmoClientLock
import me.eater.emo.emo.CreateEmoProfile
import me.eater.emo.emo.FetchMods
import me.eater.emo.forge.*
import me.eater.emo.minecraft.*
import me.eater.emo.utils.Noop
import me.eater.emo.utils.Workflow
import me.eater.emo.utils.WorkflowBuilder
import net.swiftzer.semver.SemVer

/**
 * Get workflow used to install a new Minecraft profile
 */
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
            if (SemVer.parse(ctx.selectedMinecraftVersion!!.id) >= SemVer(
                    1,
                    13,
                    0
                )
            ) {
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

/**
 * Run install job and print all state changes to STDOUT, will block
 */
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