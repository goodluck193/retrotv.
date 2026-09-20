package com.retrotv.emu

import java.text.Normalizer
import java.util.Locale

object CoverNames {
    private val tags = Regex("\\([^)]*\\)|\\[[^]]*\\]")
    private val punctuation = Regex("[^a-z0-9]+")
    fun normalize(title: String): String {
        val ascii = Normalizer.normalize(tags.replace(title, ""), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace("&", " and ")
        return punctuation.replace(ascii, " ").trim().removeSuffix(" the").removePrefix("the ")
    }
}
