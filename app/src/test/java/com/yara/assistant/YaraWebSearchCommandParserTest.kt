package com.yara.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YaraWebSearchCommandParserTest {
    @Test
    fun parsesShortSearchPrefix() {
        assertEquals(
            YaraWebSearchCommand.Search("آخرین خبرهای فناوری"),
            YaraWebSearchCommandParser.parse("جستجو: آخرین خبرهای فناوری")
        )
    }

    @Test
    fun parsesNaturalSearchPrefix() {
        assertEquals(
            YaraWebSearchCommand.Search("هوش مصنوعی روی اندروید"),
            YaraWebSearchCommandParser.parse("در اینترنت جستجو کن: هوش مصنوعی روی اندروید")
        )
    }

    @Test
    fun rejectsEmptyAndOrdinaryMessages() {
        assertNull(YaraWebSearchCommandParser.parse("سلام"))
        assertNull(YaraWebSearchCommandParser.parse("جستجو:"))
    }
}
