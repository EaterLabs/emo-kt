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
    }
}