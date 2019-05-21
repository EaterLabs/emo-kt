package me.eater.emo.emo

import com.beust.klaxon.Converter
import com.beust.klaxon.Json
import com.beust.klaxon.JsonValue
import com.beust.klaxon.Klaxon
import me.eater.emo.Account
import me.eater.emo.Target
import me.eater.emo.emo.dto.repository.Modpack
import me.eater.emo.emo.dto.repository.ModpackVersion
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.*

/**
 * Location of emo data storage
 */
val DataLocation: Path = when (System.getProperty("os.name").toLowerCase()) {
    "linux" -> Paths.get(System.getProperty("user.home"), "/.local/share/emo")
    else -> Paths.get(System.getProperty("user.home"), "/.emo")
}

/**
 * Location of Settings file
 */
val SettingsLocation: Path = Paths.get(DataLocation.toString(), "/settings.json")

private fun <T> Klaxon.convert(
    k: kotlin.reflect.KClass<*>,
    fromJson: Klaxon.(JsonValue) -> T,
    toJson: Klaxon.(T) -> String,
    isUnion: Boolean = false
) =
    this.converter(object : Converter {
        @Suppress("UNCHECKED_CAST")
        override fun toJson(value: Any) = toJson.invoke(this@convert, value as T)

        override fun fromJson(jv: JsonValue) = fromJson.invoke(this@convert, jv) as Any
        override fun canConvert(cls: Class<*>) = cls == k.java || (isUnion && cls.superclass == k.java)
    })

var settingsKlaxon = Klaxon()
    .convert(UUID::class, { UUID.fromString(it.string!!) }, { toJsonString("$it") })
    .convert(RepositoryType::class, { RepositoryType.fromString(it.string!!) }, { toJsonString("$it") })
    .convert(ModpackVersion.Channel::class, { ModpackVersion.Channel.fromString(it.string!!) }, { toJsonString("$it") })
    .convert(Target::class, { Target.fromString(it.string!!) }, { toJsonString("$it") })
    .convert(Instant::class, { Instant.parse(it.string!!) }, { toJsonString("$it") })

/**
 * Class which holds all current settings
 */
data class Settings(
    val clientToken: String,
    var selectedAccount: String? = null,
    val accounts: HashMap<String, Map<String, Any>> = hashMapOf(),
    val profiles: MutableList<Profile> = mutableListOf(),
    val repositories: MutableList<RepositoryDefinition> = mutableListOf()
) {
    /**
     * Save settings to file
     */
    fun save() {
        Files.createDirectories(SettingsLocation.parent)
        SettingsLocation.toFile().writeText(settingsKlaxon.toJsonString(this))
    }

    /**
     * Add account data retrieved from Yggdrasil to settings
     */
    fun addAccount(account: MutableMap<String, Any>) {
        accounts[account["uuid"].toString()] = account
    }

    /**
     * Remove account by [uuid]
     */
    fun removeAccount(uuid: String) {
        accounts.remove(uuid)
    }

    /**
     * Get account by [uuid], will throw when no account is found
     */
    fun getAccount(uuid: String): Map<String, Any> {
        return accounts[uuid]!!
    }

    /**
     * Get all account maps in a list
     */
    fun getAccounts(): List<Map<String, Any>> {
        return this.accounts.values.toList()
    }

    /**
     * Set selected account by [uuid]
     */
    fun selectAccount(uuid: String) {
        selectedAccount = uuid
    }

    /**
     * Get currently selected account uuid, will throw when no accounts are configured
     */
    fun getSelectedAccountUuid(): String {
        if (selectedAccount === null || !accounts.containsKey(selectedAccount!!)) {
            if (getAccounts().isEmpty()) throw Error("No accounts configured")
            val acc = getAccounts().first()
            selectAccount(acc["uuid"] as String)
        }

        return selectedAccount!!
    }

    /**
     * Get currently selected account, will throw when no accounts are configured
     */
    fun getSelectedAccount(): Map<String, Any>? {
        return getAccount(getSelectedAccountUuid())
    }

    /**
     * Get currently configured repository definitons
     */
    fun getConfiguredRepositories(): List<RepositoryDefinition> = repositories

    /**
     * Add repository definition to settings
     */
    fun addRepository(repositoryDefinition: RepositoryDefinition) = repositories.add(repositoryDefinition)

    /**
     * Add [profile] to settings
     */
    fun addProfile(profile: Profile) = profiles.add(profile)

    /**
     * Get profiles from settings
     */
    @JvmName("getRealProfiles")
    fun getProfiles(): List<Profile> = profiles

    /**
     * Remove repository definition from settings
     */
    fun removeRepository(repositoryDefinition: RepositoryDefinition) = repositories.remove(repositoryDefinition)

    companion object {

        /**
         * Load settings from file, if settings is corrupt or not found a new settings file is created.
         */
        fun load(): Settings {
            if (SettingsLocation.toFile().exists()) {
                var settings: Settings? = null
                try {
                    settings = settingsKlaxon.parse(SettingsLocation.toFile().readText())
                } catch (e: Exception) {
                    println(e)
                }

                if (settings !== null) return settings
            }

            return Settings(UUID.randomUUID().toString()).apply {
                save()
            }
        }
    }
}

/**
 * Repository Definition, stores data about a repository
 */
data class RepositoryDefinition(
    /**
     * [type] of repository, represented by [RepositoryType]
     */
    val type: RepositoryType,
    /**
     * URL to repository, may be an http(s) url or just a file path (which is used for local repositories)
     */
    val url: String
) {
    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + url.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RepositoryDefinition

        if (type != other.type) return false
        if (url != other.url) return false

        return true
    }

    /**
     * Get unique hash of this [RepositoryDefinition], is a base64 of [type] and [url] joined by an ':'
     */
    @Json(ignored = true)
    val hash by lazy {
        Base64.getEncoder().encode("$type:$url".toByteArray()).toString(Charset.defaultCharset())
    }
}

/**
 * Type of repository
 */
enum class RepositoryType {
    /**
     * Remote repository type is used for a repository that should be fetched via http/s
     */
    Remote {
        override fun toString() = "remote"
    },
    /**
     * Local repostiroy type is used for a repository that is located on the local computer
     */
    Local {
        override fun toString() = "local"
    };

    companion object {
        /**
         * Create a repository type from string
         */
        fun fromString(value: String) = when (value) {
            "remote" -> Remote
            "local" -> Local
            else -> throw IllegalArgumentException()
        }
    }
}

/**
 * Class that holds info about a profile, used for serialisation
 */
data class Profile(
    /**
     * Location where this profile is located
     */
    val location: String,
    /**
     * Name of profile
     */
    val name: String,
    /**
     * Name of modpack of this profile
     */
    val modpack: Modpack,
    /**
     * Modpack version
     */
    val modpackVersion: ModpackVersion,
    /**
     * Time created
     */
    val createdOn: Instant = Instant.MIN,
    /**
     * Time last touched
     */
    val lastTouched: Instant = if (createdOn == Instant.MIN) Instant.now() else createdOn
) {
    /**
     * Get [MinecraftExecutor] for this profile with [account]
     */
    fun getExecutor(account: Account) = MinecraftExecutor(location, account)
}