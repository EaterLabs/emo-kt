package me.eater.emo.emo

import com.beust.klaxon.Converter
import com.beust.klaxon.JsonValue
import com.beust.klaxon.Klaxon
import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpGet
import me.eater.emo.emo.dto.repository.Links
import me.eater.emo.emo.dto.repository.Modpack
import me.eater.emo.emo.dto.repository.Repository
import me.eater.emo.utils.io
import me.eater.emo.utils.parallel
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.collections.HashMap

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
    var selectedAccount: String? = null,
    val accounts: HashMap<String, Map<String, Any>> = hashMapOf(),
    val repositories: MutableList<RepositoryDef> = mutableListOf()
) {
    fun save() {
        Files.createDirectories(SettingsLocation.parent)
        SettingsLocation.toFile().writeText(settingsKlaxon.toJsonString(this))
    }

    fun addAccount(account: MutableMap<String, Any>) {
        accounts[account["uuid"].toString()] = account
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

    fun getSelectedAccount(): Map<String, Any>? {
        if (selectedAccount === null) {
            if (getAccounts().isEmpty()) return null
            val acc = getAccounts().first()
            selectAccount(acc["uuid"] as String)
        }

        return getAccount(selectedAccount!!)
    }

    fun getConfiguredRepositories(): List<RepositoryDef> = repositories
    fun addRepository(repositoryDef: RepositoryDef) = repositories.add(repositoryDef)
    suspend fun updateRepositories() {
        val repositoryCache: HashMap<Int, RepositoryCache> = hashMapOf()
        val modpackCache: HashMap<String, ModpackCache> = hashMapOf()
        val repos = Array<Repository?>(this.repositories.size) { null }

        parallel(this.repositories.indices) {
            val sourceRepo = this.repositories[it]
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

            repos[it] = repo
        }

        repos.filterNotNull().forEachIndexed { index, repository ->
            repositoryCache.set(index, RepositoryCache.fromRepository(repository))
        }
    }

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
)

data class RepositoryCache(
    val name: String,
    val description: String = "",
    val logo: String? = null,
    val links: Links = Links()
) {
    companion object {
        fun fromRepository(repository: Repository): RepositoryCache =
            RepositoryCache(repository.name, repository.description, repository.logo, repository.links)
    }
}

data class ModpackCache(
    val repository: Int,
    val modpack: Modpack
)