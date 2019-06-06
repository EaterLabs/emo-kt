package me.eater.emo

import me.eater.emo.minecraft.dto.manifest.*

/**
 * Holds checks and data about the current environment, which is used for selecting the correct libraries and natives
 */
class EmoEnvironment {
    /**
     * Normalized OS name for this environment
     */
    val osName: String by lazy {
        System.getProperty("os.name").toLowerCase().run {
            when {
                this == "linux" -> "linux"
                this.contains("mac", true) -> "osx"
                this.contains("windows", true) -> "windows"
                else -> "other"
            }
        }
    }

    /**
     * OS version for this environment
     */
    private val osVersion: String by lazy { System.getProperty("os.version") }

    /**
     * Normalized OS architecture for this environment
     */
    val osArch: String by lazy {
        System.getProperty("os.arch").let {
            if (Regex("""^(x86_?64|amd64|ia32e|em64t|x64)${'$'}""").matches(it))
                "64"
            else
                "32"
        }
    }


    /**
     * Check if this environment passes the given OS check, returns true on pass, false on fail
     */
    fun passesOSCheck(check: OS): Boolean {
        if (check.arch !== null && check.arch != osArch) {
            return false
        }

        if (check.version !== null && !check.version.containsMatchIn(osVersion)) {
            return false
        }

        if (check.name !== null && check.name != osName) {
            return false
        }

        return true
    }

    /**
     * Check if this environment passes the given [rule], returns true on pass, false on fail
     */
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

    /**
     * Check if this environment passes the given [rules], returns true on pass, false on fail
     */
    fun passesRules(rules: Iterable<Rule>): Boolean {
        for (rule in rules) {
            if (!passesRule(rule)) {
                return false
            }
        }

        return true
    }

    /**
     * Select if there is a native artifact that we should select in this [library],
     * Returns the native artifact if found
     */
    fun selectNative(library: Library): Artifact? {
        if (library.natives.containsKey(osName)) {
            return library.downloads.classifiers[expandSimpleTemplating(library.natives.getOrDefault(osName, ""))]
        }

        return null
    }

    /**
     * Expand the simple string template that is used for natives, so far only ${arch} is supported.
     */
    private fun expandSimpleTemplating(input: String): String {
        return input.replace(Regex("\\$\\{([A-Za-z0-9]+)}")) {
            when (it.groupValues[1]) {
                "arch" -> osArch
                else -> it.value
            }
        }
    }
}
