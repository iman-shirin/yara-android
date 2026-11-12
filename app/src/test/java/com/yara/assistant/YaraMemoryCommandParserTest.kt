package com.yara.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YaraMemoryCommandParserTest {
    @Test
    fun parsesSaveCommandInPersian() {
        assertEquals(
            YaraMemoryCommand.Save("من پاسخ فارسی را ترجیح می‌دهم"),
            YaraMemoryCommandParser.parse("به خاطر بسپار: من پاسخ فارسی را ترجیح می‌دهم")
        )
    }

    @Test
    fun parsesForgetCommandInPersian() {
        assertEquals(
            YaraMemoryCommand.Forget("ترجیح قدیمی"),
            YaraMemoryCommandParser.parse("فراموش کن، ترجیح قدیمی")
        )
    }

    @Test
    fun parsesListCommand() {
        assertEquals(YaraMemoryCommand.List, YaraMemoryCommandParser.parse("فهرست حافظه"))
    }

    @Test
    fun ignoresOrdinaryConversationAndEmptyMemoryCommands() {
        assertNull(YaraMemoryCommandParser.parse("سلام یارا"))
        assertNull(YaraMemoryCommandParser.parse("به خاطر بسپار"))
        assertNull(YaraMemoryCommandParser.parse("فراموش کن:"))
    }
}
