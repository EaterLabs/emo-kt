package me.eater.emo

/**
 * Version selector class, used to wrap a static or dynamic version
 */
class VersionSelector(val selector: String) {
    /**
     * Returns true if version wrapped is absolute/static (e.g.: 1.2.3), false if non-static (e.g. latest)
     */
    fun isStatic(): Boolean {
        return selector != "latest" && selector != "latest-snapshot" && selector != "recommended"
    }

    companion object {

        /**
         * Return version selector from String or null given, return null
         */
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