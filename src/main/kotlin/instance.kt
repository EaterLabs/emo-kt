package me.eater.emo

import com.beust.klaxon.Klaxon
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpGet
import com.mojang.authlib.Agent
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import me.eater.emo.emo.*
import me.eater.emo.emo.dto.repository.Links
import me.eater.emo.emo.dto.repository.Modpack
import me.eater.emo.emo.dto.repository.ModpackVersion
import me.eater.emo.emo.dto.repository.Repository
import me.eater.emo.utils.io
import me.eater.emo.utils.parallel
import java.io.File
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Instance {
    private var modpackCollectionCache: ModpackCollectionCache = ModpackCollectionCache()
    private val settingsLock = ReentrantLock()
    private var lastSettingsMtime: Long = 0
    private var settings: Settings = Settings.load()
    private val klaxon = Klaxon()
    private val localRepo = RepositoryDef("local", "$DataLocation/repo-local.json")

    fun <T> useSettings(readOnly: Boolean = false, block: (settings: Settings) -> T): T {
        return settingsLock.withLock {
            val lastModified = SettingsLocation.toFile().lastModified()
            if (lastModified > lastSettingsMtime) {
                settings = Settings.load()
                lastSettingsMtime = lastModified
            }
            val result = block(settings)
            if (readOnly) {
                settings.save()
                lastSettingsMtime = SettingsLocation.toFile().lastModified()
            }

            result
        }
    }

    fun getDataDir(): String {
        return DataLocation.toString()
    }

    suspend fun updateRepositories() {
        val repositories = useSettings { it.getConfiguredRepositories() }
        val repositoryCache: HashMap<String, RepositoryCache> = hashMapOf()
        val modpackCache: HashMap<String, ModpackCache> = hashMapOf()
        val repos: MutableList<Pair<RepositoryDef, Repository>> = mutableListOf()

        parallel(repositories.indices) {
            val sourceRepo = repositories[it]
            val repo = when (sourceRepo.type) {
                "local" -> {
                    io {
                        val json = File(sourceRepo.url).readText()

                        Repository.fromJson(json)
                    }
                }
                "remote" -> {
                    val json = sourceRepo.url.httpGet()
                        .awaitString()

                    Repository.fromJson(json)
                }
                else -> null
            }

            if (repo !== null) {
                repos.add(sourceRepo to repo)
            }
        }

        repos.forEach { (def, repo) ->
            repositoryCache.set(def.hash, RepositoryCache.fromRepository(def, repo))
            repo.modpacks.forEach { _, modpack ->
                modpackCache.set(modpack.id, ModpackCache(def.hash, modpack))
            }
        }

        modpackCollectionCache = ModpackCollectionCache(repositoryCache, modpackCache)
        saveModpackCollectionCache()
    }

    fun getModpackCollectionCachePath(): Path =
        Paths.get(getDataDir(), "modpack-cache.json").also { Files.createDirectories(it.parent) }

    suspend fun saveModpackCollectionCache() {
        val cacheFile = getModpackCollectionCachePath().toFile()
        io {
            cacheFile.writeText(klaxon.toJsonString(modpackCollectionCache))
        }
    }

    suspend fun loadModpackCollectionCache() {
        val cacheFile = getModpackCollectionCachePath().toFile()
        if (!cacheFile.exists()) {
            return
        }

        io {
            modpackCollectionCache = klaxon.parse(cacheFile) ?: return@io
        }
    }

    suspend fun minecraftLogIn(username: String, password: String, save: Boolean = true): Account {
        return GlobalScope.async {
            val authService = useSettings { settings ->
                YggdrasilAuthenticationService(Proxy.NO_PROXY, settings.clientToken)
                    .createUserAuthentication(Agent.MINECRAFT)
                    .apply {
                        setUsername(username)
                        setPassword(password)
                    }
            }

            authService.logIn()

            if (!authService.isLoggedIn) {
                throw Error("Failed logging in: Unknown reason")
            }

            if (save) {
                useSettings { settings ->
                    settings.addAccount(
                        authService.saveForStorage()
                    )
                }
            }

            Account.fromMap(authService.saveForStorage())

        }.await()
    }

    fun getAccounts(): List<Account> {
        return useSettings {
            it.getAccounts().map { Account.fromMap(it) }
        }
    }

    fun getRepositories() = useSettings(true) { it.getConfiguredRepositories() }
    fun removeRepository(repositoryDef: RepositoryDef) {
        useSettings { settings ->
            settings.removeRepository(repositoryDef)
        }
    }

    fun getModpacks(): List<ModpackCache> = modpackCollectionCache.modpackCache.values.toList()

    suspend fun getLocalRepo(): Repository {
        return io {
            val repoPath = Paths.get(localRepo.url)
            val repoFile = repoPath.toFile()

            val repo = if (!repoFile.exists()) {
                Files.createDirectories(repoPath.parent)
                Repository(
                    "Your local repo"
                ).also {
                    repoFile.writeText(klaxon.toJsonString(it))
                }
            } else {
                klaxon.parse(repoFile) ?: Repository(
                    "Your local repo"
                ).also {
                    repoFile.writeText(klaxon.toJsonString(it))
                }
            }

            repo
        }
    }

    suspend fun addLocalModpack(modpack: Modpack) {
        val repo = getLocalRepo()

        useSettings { settings ->
            val repos = settings.getConfiguredRepositories()
            if (repos.contains(localRepo)) {
                settings.addRepository(localRepo)
            }
        }

        if (!modpackCollectionCache.repositoryCache.containsKey(localRepo.hash)) {
            modpackCollectionCache.repositoryCache.set(localRepo.hash, RepositoryCache.fromRepository(localRepo, repo))
        }

        modpackCollectionCache.modpackCache.set(modpack.id, ModpackCache(localRepo.hash, modpack))
        saveModpackCollectionCache()
    }

    suspend fun getAccount(uuid: String = useSettings(true) { it.getSelectedAccountUuid() }): Account {
        return GlobalScope.async {
            val (accountMap, clientToken) = useSettings(true) {
                it.getAccount(uuid) to it.clientToken
            }

            val authService = YggdrasilAuthenticationService(Proxy.NO_PROXY, clientToken)
                .createUserAuthentication(Agent.MINECRAFT)

            authService.loadFromStorage(accountMap)
            authService.logIn()

            if (!authService.isLoggedIn) {
                throw Error("Not logged in")
            }

            val acc = authService.saveForStorage()
            useSettings { it.addAccount(acc) }

            Account.fromMap(acc)
        }.await()
    }

    fun addProfile(modpack: Modpack, modpackVersion: ModpackVersion, location: String, name: String) {
        val profile = Profile(location, name, modpack = modpackVersion, modpackName = modpack.name)
        useSettings { settings -> settings.addProfile(profile) }
    }

    fun getProfiles(): List<Profile> {
        return useSettings(true) { settings -> settings.getProfiles() }
    }

    fun getEmoContextForModpack(
        modpack: Modpack,
        modpackVersion: ModpackVersion,
        installLocation: String,
        target: Target,
        name: String = modpack.name
    ): EmoContext {
        return EmoContext(
            forgeVersion = VersionSelector.fromStringOrNull(modpackVersion.forge),
            minecraftVersion = VersionSelector(modpackVersion.minecraft),
            mods = modpackVersion.mods,
            target = target,
            installLocation = Paths.get(installLocation),
            profile = EmoProfile(),
            modpack = modpack,
            modpackVersion = modpackVersion,
            name = name
        )
    }
}

data class Account(
    val uuid: String,
    val displayName: String,
    val username: String,
    val accessToken: String,
    val userid: String
) {
    companion object {
        fun fromMap(map: Map<String, Any>): Account {
            return Account(
                map["uuid"] as String,
                map["displayName"] as String,
                map["username"] as String,
                map["accessToken"] as String,
                map["userid"] as String
            )
        }
    }
}

data class ModpackCollectionCache(
    val repositoryCache: HashMap<String, RepositoryCache> = hashMapOf(),
    val modpackCache: HashMap<String, ModpackCache> = hashMapOf()
)

data class RepositoryCache(
    val def: RepositoryDef,
    val name: String,
    val description: String = "",
    val logo: String? = null,
    val links: Links = Links()
) {
    companion object {
        fun fromRepository(def: RepositoryDef, repository: Repository): RepositoryCache =
            RepositoryCache(def, repository.name, repository.description, repository.logo, repository.links)
    }
}

data class ModpackCache(
    val repository: String,
    val modpack: Modpack
)