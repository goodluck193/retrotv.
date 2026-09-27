package com.retrotv.emu

import java.text.Normalizer
import java.util.Locale

object CoverNames {
    private val tags = Regex("\\([^)]*\\)|\\[[^]]*\\]")
    private val punctuation = Regex("[^a-z0-9]+")
    fun displayTitle(title: String): String = tags.replace(title, "").trim().trimEnd(']', ')').trim()

    /** Explicit alternate titles, never a fuzzy match that could confuse a sequel. */
    fun searchKey(title: String): String = when (val key = normalize(title)) {
        "ecco tides of time" -> "ecco the tides of time"
        else -> key
    }

    fun normalize(title: String): String {
        val ascii = Normalizer.normalize(tags.replace(title, ""), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace("&", " and ")
        return punctuation.replace(ascii, " ").trim().removeSuffix(" the").removePrefix("the ")
    }
}
