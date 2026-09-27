package io.github.agopalareddy.umm.core.cleanup

sealed interface LanguageChoice {
    data object Auto : LanguageChoice
    data class Fixed(val iso639_1: String) : LanguageChoice

    /** Stable string form used in storage: "auto" or the ISO 639-1 code. */
    fun encode(): String = when (this) {
        Auto -> AUTO
        is Fixed -> iso639_1
    }

    companion object {
        const val AUTO = "auto"
        fun decode(value: String): LanguageChoice = if (value == AUTO) Auto else Fixed(value)
    }
}
