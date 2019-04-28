package me.eater.emo

class VersionSelector(val selector: String) {
    fun isStatic(): Boolean {
        return selector != "latest" && selector != "latest-snapshot" && selector != "recommended"
    }

    companion object {
        fun fromStringOrNull(selector: String?) =
            if (selector == null) {
                null
            } else {
                VersionSelector(selector)
            }

        /**
         * Version selector for latest minecraft version available
         */
        val MINECRAFT_LATEST = VersionSelector("latest")

        /**
         * Version selector for latest minecraft snapshot available
         */
        val MINECRAFT_LATEST_SNAPSHOT = VersionSelector("latest-snapshot")

        /**
         * Version selector for latest Forge version available
         */
        val FORGE_LATEST = VersionSelector("latest")

        /**
         * Version selector for recommended Forge version
         */
        val FORGE_RECOMMENDED = VersionSelector("recommended")
    }
}