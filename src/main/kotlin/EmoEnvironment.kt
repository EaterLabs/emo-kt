package me.eater.emo

import me.eater.emo.minecraft.dto.manifest.*

class EmoEnvironment {
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