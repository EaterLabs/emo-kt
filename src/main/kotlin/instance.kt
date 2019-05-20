package me.eater.emo

import com.beust.klaxon.Json
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpGet
import com.mojang.authlib.Agent
import com.mojang.authlib.exceptions.AuthenticationException
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import me.eater.emo.emo.*
import me.eater.emo.emo.dto.repository.*
import me.eater.emo.utils.ProcessStartedEvent
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

/**
 * EmoInstance
 */
@Suppress("UNUSED")
class EmoInstance {
    private var modpackCollectionCache: ModpackCollectionCache = ModpackCollectionCache()
    private val settingsLock = ReentrantLock()
    private var lastSettingsMtime: Long = 0
    private var settings: Settings = Settings.load()
    private val klaxon = settingsKlaxon
    private val localRepo = RepositoryDefinition(RepositoryType.Local, "$DataLocation/repo-local.json")

    fun <T> useSettings(readOnly: Boolean = false, block: (settings: Settings) -> T): T {
        return settingsLock.withLock {
            val lastModified = SettingsLocation.toFile().lastModified()
            if (lastModified > lastSettingsMtime) {
                settings = Settings.load()
                lastSettingsMtime = lastModified
            }
            val result = block(settings)
            if (!readOnly) {
                settings.save()
                lastSettingsMtime = SettingsLocation.toFile().lastModified()
            }

            result
        }
    }

    /**
     * Get location where all configuration and other data is stored for user
     */
    fun getDataDir(): String {
        return DataLocation.toString()
    }

    /**
     * Fetches all remote and local repositories and indexes those, save the new cache with [saveModpackCollectionCache]
     */
    suspend fun updateRepositories() {
        val repositories = useSettings { it.getConfiguredRepositories() }
        val repositoryCache: HashMap<String, RepositoryCache> = hashMapOf()
        val modpackCache: HashMap<String, ModpackCache> = hashMapOf()
        val repos: MutableList<Pair<RepositoryDefinition, Result<Repository>>> = mutableListOf()

        parallel(repositories.indices) {
            val sourceRepo = repositories[it]
            val repo = try {
                val json = when (sourceRepo.type) {
                    RepositoryType.Local -> {
                        io {
                            File(sourceRepo.url).readText()
                        }
                    }
                    RepositoryType.Remote -> {
                        sourceRepo.url.httpGet()
                            .awaitString()
                    }
                }

                Result.success(Repository.fromJson(json)!!)
            } catch (t: Throwable) {
                Result.failure<Repository>(t)
            }

            repos.add(Pair(sourceRepo, repo))
        }

        repos.forEach { (def, repoResult) ->
            val fakeRepoMaybe = when {
                repoResult.isSuccess -> RepositoryCache.fromRepository(def, repoResult.getOrThrow())
                modpackCollectionCache.repositoryCache.containsKey(def.hash) -> modpackCollectionCache.repositoryCache[def.hash]!!.copy(
                    status = RepositoryCache.Status.Broken(repoResult.exceptionOrNull()!!)
                )
                else -> RepositoryCache(
                    def,
                    name = "Unknown",
                    status = RepositoryCache.Status.Broken(repoResult.exceptionOrNull()!!),
                    description = "This repository has been added but couldn't be fetched."
                )
            }

            repositoryCache.set(def.hash, fakeRepoMaybe)

            if (repoResult.isSuccess) {
                repoResult.getOrThrow().modpacks.forEach { _, modpack ->
                    modpackCache.set(modpack.id, ModpackCache(def.hash, modpack))
                }
            } else if (modpackCollectionCache.repositoryCache.containsKey(def.hash)) {
                modpackCache.putAll(modpackCollectionCache.modpackCache.filter { it.value.repository == def.hash })
            }
        }

        modpackCollectionCache = ModpackCollectionCache(repositoryCache, modpackCache)
        saveModpackCollectionCache()
    }

    /**
     * Get path where cache for modpack collection is save
     */
    private fun getModpackCollectionCachePath(): Path =
        Paths.get(getDataDir(), "modpack-cache.json").also { Files.createDirectories(it.parent) }

    /**
     * Save current modpack collection, make sure either [updateRepositories] or [loadModpackCollectionCache] is called before, otherwise you will save an empty cache
     */
    suspend fun saveModpackCollectionCache() {
        val cacheFile = getModpackCollectionCachePath().toFile()
        io {
            cacheFile.writeText(klaxon.toJsonString(modpackCollectionCache))
        }
    }

    /**
     * Load the current modpack collection cache from disk
     */
    suspend fun loadModpackCollectionCache() {
        val cacheFile = getModpackCollectionCachePath().toFile()
        if (!cacheFile.exists()) {
            return
        }

        io {
            modpackCollectionCache = klaxon.parse(cacheFile) ?: return@io
        }
    }


    /**
     * Try to authenticate with the Minecraft servers with given [username] and [password].
     * Throws on login failure, set [save] to false to not save this account in the settings.
     * (e.g. if you only want to check if these are correct credentials)
     */
    suspend fun accountLogIn(username: String, password: String, save: Boolean = true): Account {
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

    /**
     * Get a list of configured accounts saved in settings
     */
    fun getAccounts(): List<Account> {
        return useSettings {
            it.getAccounts().map { Account.fromMap(it) }
        }
    }

    /**
     * Get repository by id saved in [ModpackCache.repository]
     */
    fun getRepository(id: String): RepositoryCache? = this.modpackCollectionCache.repositoryCache.get(id)

    /**
     * Get a list of configured repositories from settings
     */
    fun getRepositories() = useSettings(true) { it.getConfiguredRepositories() }

    /**
     * Remove a repository
     */
    fun removeRepository(repositoryDefinition: RepositoryDefinition) {
        useSettings { settings ->
            settings.removeRepository(repositoryDefinition)
        }
    }

    /**
     * Get all modpacks currently known to cache, indexed by id. id is enforced `[user-handle]/[modpack-id]`
     */
    fun getModpacks(): Map<String, ModpackCache> = modpackCollectionCache.modpackCache

    /**
     * Get modpack by handle
     */
    fun getModpack(id: String): ModpackCache? = modpackCollectionCache.modpackCache.get(id)

    /**
     * Get the local modpack repo, which is used for locally imported or created modpacks
     */
    suspend fun getLocalRepo(): MutableRepository {
        return io {
            val repoPath = Paths.get(localRepo.url)
            val repoFile = repoPath.toFile()

            val repo = if (!repoFile.exists()) {
                Files.createDirectories(repoPath.parent)
                MutableRepository(
                    "Your local repo"
                ).also {
                    repoFile.writeText(klaxon.toJsonString(it))
                }
            } else {
                klaxon.parse<MutableRepository>(repoFile) ?: MutableRepository(
                    "Your local repo"
                ).also {
                    repoFile.writeText(klaxon.toJsonString(it))
                }
            }

            repo
        }
    }

    /**
     * Save [localRepository] as the current local repo
     */
    suspend fun saveLocalRepo(localRepository: MutableRepository) {
        io {
            val repoPath = Paths.get(localRepo.url)
            val repoFile = repoPath.toFile()
            Files.createDirectories(repoPath.parent)
            repoFile.writeText(klaxon.toJsonString(localRepository))
        }
    }

    /**
     * Add local [modpack] to local repository, will inject [modpack] into cache and save that immediately afterwards
     */
    suspend fun addLocalModpack(modpack: ModpackWithVersions) {
        val repo = getLocalRepo()

        useSettings { settings ->
            val repos = settings.getConfiguredRepositories()
            if (repos.contains(localRepo)) {
                settings.addRepository(localRepo)
            }
        }

        repo.modpacks.set(modpack.id, modpack)
        saveLocalRepo(repo)

        if (!modpackCollectionCache.repositoryCache.containsKey(localRepo.hash)) {
            modpackCollectionCache.repositoryCache.set(localRepo.hash, RepositoryCache.fromRepository(localRepo, repo))
        }

        modpackCollectionCache.modpackCache.set(modpack.id, ModpackCache(localRepo.hash, modpack))
        saveModpackCollectionCache()
    }

    /**
     * Remove account from settings by [uuid], will also log out the account
     */
    suspend fun removeAccount(uuid: String) {
        val (accountMap, clientToken) = useSettings(true) {
            it.getAccount(uuid) to it.clientToken
        }

        val authService = YggdrasilAuthenticationService(Proxy.NO_PROXY, clientToken)
            .createUserAuthentication(Agent.MINECRAFT)

        authService.loadFromStorage(accountMap)
        try {
            GlobalScope.async {
                authService.logOut()
            }.await()
        } catch (_: Throwable) {
            // Don't
        }

        useSettings { it.removeAccount(uuid) }
    }

    /**
     * Get an account by [uuid] if not given, selected account is used.
     * Will try to reauthenticate with the mojang account service
     * if [requireLoggedIn] is true or not given, will throw on failed re-authentication
     */
    suspend fun getAccountViaAuthentication(
        uuid: String = useSettings(true) { it.getSelectedAccountUuid() },
        requireLoggedIn: Boolean = true
    ): Account {
        return GlobalScope.async {
            val (accountMap, clientToken) = useSettings(true) {
                it.getAccount(uuid) to it.clientToken
            }

            val authService = YggdrasilAuthenticationService(Proxy.NO_PROXY, clientToken)
                .createUserAuthentication(Agent.MINECRAFT)

            authService.loadFromStorage(accountMap)
            try {
                authService.logIn()
            } catch (e: AuthenticationException) {
                if (requireLoggedIn) {
                    throw e
                }
            }

            if (!authService.isLoggedIn && requireLoggedIn) {
                throw Error("Not logged in")
            }

            val acc = authService.saveForStorage()
            useSettings { it.addAccount(acc) }

            Account.fromMap(acc)
        }.await()
    }

    /**
     * Add profile with given [modpack] and [modpackVersion] on [location] with [name] to settings.
     * This function is used by the install workflow
     */
    fun addProfile(modpack: Modpack, modpackVersion: ModpackVersion, location: String, name: String) {
        val profile = Profile(location, name, modpack.withoutVersions(), modpackVersion)
        useSettings { settings -> settings.addProfile(profile) }
    }

    /**
     * Get list of profiles saved in settings
     */
    fun getProfiles(): List<Profile> {
        return useSettings(true) { settings -> settings.getProfiles() }
    }

    /**
     * Run an install workflow for given [emoContext], [stateStart] will be called everytime the install workflow changes
     * to a state. function will return after install, or throw at failure
     */
    suspend fun runInstall(emoContext: EmoContext, stateStart: (ProcessStartedEvent<EmoContext>) -> Unit) {
        val workflow = getInstallWorkflow(emoContext)
        workflow.processStarted += stateStart
        workflow.waitFor()
    }

    /**
     * Get an [EmoContext] object for install configuration consisting of [modpack], [modpackVersion], [installLocation],
     * [target], and [name], if no [name] is given, name of modpack will be used
     */
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
            environment = EmoEnvironment(),
            modpack = modpack,
            modpackVersion = modpackVersion,
            name = name
        )
    }

    /**
     * Get a [MinecraftExecutor] for given [location] and [account]
     */
    fun getMinecraftExecutor(location: String, account: Account) = MinecraftExecutor(location, account)

    /**
     * Get a [MinecraftExecutor] for given [profile] and [account]
     */
    fun getMinecraftExecutor(profile: Profile, account: Account) = getMinecraftExecutor(profile.location, account)
}

/**
 * Class to hold account data received from Mojang via Authentication
 */
data class Account(
    /**
     * UUID of account known by Mojang account services
     */
    val uuid: String,
    /**
     * Display name of account, used in e.g. Minecraft
     */
    val displayName: String,
    /**
     * Username that is used for login [is with non-legacy accounts an email-address]
     */
    val username: String,
    /**
     * Access token that is used to re-authenticate with Mojang, in game as well in the client
     */
    val accessToken: String,
    /**
     * user id of game profile used. [Has zero importance to us.]
     */
    val userid: String
) {
    companion object {
        fun fromMap(map: Map<String, Any>): Account {
            return Account(
                map["uuid"].toString(),
                map["displayName"] as String,
                map["username"] as String,
                map["accessToken"] as String,
                map["userid"] as String
            )
        }
    }
}

/**
 * Data object which holds cache of modpacks
 */
data class ModpackCollectionCache(
    /**
     * Cache of known repositories
     */
    val repositoryCache: HashMap<String, RepositoryCache> = hashMapOf(),

    /**
     * Cache of known modpacks
     */
    val modpackCache: HashMap<String, ModpackCache> = hashMapOf()
)

/**
 * Repository cache
 */
data class RepositoryCache(
    /**
     * Definition of this repository
     */
    val definition: RepositoryDefinition,

    /**
     * Name of this repository
     */
    val name: String,

    /**
     * Description of this repository
     */
    val description: String = "",

    /**
     * Logo of this repostiory
     */
    val logo: String? = null,

    /**
     * Links about this repository
     */
    val links: Links = Links(),

    /**
     * Status of repository, filled after update
     */
    @Json(ignored = true)
    val status: Status = Status.Ok
) {
    companion object {
        /**
         * Create a cache object from the [RepositoryDefinition] and [Repository]
         */
        fun fromRepository(definition: RepositoryDefinition, repository: Repository): RepositoryCache =
            RepositoryCache(definition, repository.name, repository.description, repository.logo, repository.links)
    }

    sealed class Status {
        object Ok : Status()
        data class Broken(val t: Throwable) : Status()

        fun isOkay() = this is Ok
        fun isBroken() = this is Broken
        fun getExceptionOrNull(): Throwable? = when (this) {
            is Broken -> this.t
            else -> null
        }
    }
}

/**
 * Data object containing a [modpack] and a indentifier to the repository it originates from
 */
data class ModpackCache(
    /**
     * id of repository, fetch the repository with [EmoInstance.getRepository]
     */
    val repository: String,

    /**
     * Cached version of the modpack defintion
     */
    val modpack: ModpackWithVersions
)