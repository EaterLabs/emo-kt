package me.eater.emo.emo

import com.beust.klaxon.Converter
import com.beust.klaxon.JsonValue
import com.beust.klaxon.Klaxon
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import kotlin.collections.HashMap

val SettingsLocation = when (System.getProperty("os.name").toLowerCase()) {
    "linux" -> Paths.get(System.getProperty("user.home"), "/.local/share/emo/settings.json")
    else -> Paths.get(System.getProperty("user.home"), "/.emo/settings.json")
}

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
    val accounts: HashMap<String, Map<String, Any>> = hashMapOf()
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

    companion object {
        fun load(): Settings {
            if (SettingsLocation.toFile().exists()) {
                var settings: Settings? = null
                try {
                     settings = settingsKlaxon.parse(SettingsLocation.toFile().readText())
                } catch (_: Exception) {}

                if (settings !== null) return settings
            }

            return Settings(UUID.randomUUID().toString()).apply {
                save()
            }
        }
    }
}