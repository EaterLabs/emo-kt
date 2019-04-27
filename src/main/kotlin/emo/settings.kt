package me.eater.emo.emo

import com.beust.klaxon.Converter
import com.beust.klaxon.JsonValue
import com.beust.klaxon.Klaxon
import me.eater.emo.Account
import me.eater.emo.Instance
import me.eater.emo.emo.dto.repository.ModpackVersion
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

val DataLocation: Path = when (System.getProperty("os.name").toLowerCase()) {
    "linux" -> Paths.get(System.getProperty("user.home"), "/.local/share/emo")
    else -> Paths.get(System.getProperty("user.home"), "/.emo")
}

val SettingsLocation: Path = Paths.get(DataLocation.toString(), "/settings.json")

private fun <T> Klaxon.convert(
    k: kotlin.reflect.KClass<*>,
    fromJson: (JsonValue) -> T,
    toJson: (T) -> String,
    isUnion: Boolean = false
) =
    this.converter(object : Converter {
        @Suppress("UNCHECKED_CAST")
        override fun toJson(value: Any) = toJson(value as T)

        override fun fromJson(jv: JsonValue) = fromJson(jv) as Any
        override fun canConvert(cls: Class<*>) = cls == k.java || (isUnion && cls.superclass == k.java)
    })

var settingsKlaxon = Klaxon()
    .convert(UUID::class, { UUID.fromString(it.string!!) }, { "\"${it.toString()}\"" })

data class Settings(
    val clientToken: String,
    private var selectedAccount: String? = null,
    private val accounts: HashMap<String, Map<String, Any>> = hashMapOf(),
    private val profiles: MutableList<Profile> = mutableListOf(),
    private val repositories: MutableList<RepositoryDef> = mutableListOf()
) {
    fun save() {
        Files.createDirectories(SettingsLocation.parent)
        SettingsLocation.toFile().writeText(settingsKlaxon.toJsonString(this))
    }

    fun addAccount(account: MutableMap<String, Any>) {
        accounts[account["uuid"].toString()] = account
    }

    fun removeAccount(uuid: String) {
        accounts.remove(uuid)
    }

    fun getAccount(uuid: String): Map<String, Any> {
        return accounts[uuid]!!
    }

    fun getAccounts(): List<Map<String, Any>> {
        return this.accounts.values.toList()
    }

    fun selectAccount(uuid: String) {
        selectedAccount = uuid
    }

    fun getSelectedAccountUuid(): String {
        if (selectedAccount === null) {
            if (getAccounts().isEmpty()) throw Error("No accounts configured")
            val acc = getAccounts().first()
            selectAccount(acc["uuid"] as String)
        }

        return selectedAccount!!
    }

    fun getSelectedAccount(): Map<String, Any>? {
        return getAccount(getSelectedAccountUuid())
    }

    fun getConfiguredRepositories(): List<RepositoryDef> = repositories
    fun addRepository(repositoryDef: RepositoryDef) = repositories.add(repositoryDef)
    fun addProfile(profile: Profile) = profiles.add(profile)
    fun getProfiles(): List<Profile> = profiles
    fun removeRepository(repositoryDef: RepositoryDef) = repositories.remove(repositoryDef)

    companion object {
        fun load(): Settings {
            if (SettingsLocation.toFile().exists()) {
                var settings: Settings? = null
                try {
                    settings = settingsKlaxon.parse(SettingsLocation.toFile().readText())
                } catch (_: Exception) {
                }

                if (settings !== null) return settings
            }

            return Settings(UUID.randomUUID().toString()).apply {
                save()
            }
        }
    }
}

data class RepositoryDef(
    val type: String,
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

        other as RepositoryDef

        if (type != other.type) return false
        if (url != other.url) return false

        return true
    }

    val hash by lazy {
        Base64.getEncoder().encode("$type:$url".toByteArray()).toString(Charset.defaultCharset())
    }
}

data class Profile(
    val location: String,
    val name: String,
    val modpackName: String,
    val modpack: ModpackVersion
) {
    fun getExecutor(account: Account) = MinecraftExecutor(location, account)
}