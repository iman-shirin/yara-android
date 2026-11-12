package com.yara.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class YaraReminderCommandParserTest {
    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 11, 12, 10, 0)
        .atZone(zone).toInstant().toEpochMilli()

    @Test
    fun parsesTomorrowReminder() {
        val result = YaraReminderCommandParser.parse("یادآوری فردا ساعت 09:30 | تماس با پزشک", now)
        val command = result as YaraReminderCommand.Create
        val expected = LocalDateTime.of(2026, 11, 13, 9, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, command.dueAt)
        assertEquals("تماس با پزشک", command.title)
    }

    @Test
    fun parsesPersianDigitsAndAbsoluteDate() {
        val result = YaraReminderCommandParser.parse("یادآوری ۲۰۲۶-۱۱-۱۳ ۰۹:۳۰ | خرید", now)
        val command = result as YaraReminderCommand.Create
        val expected = LocalDateTime.of(2026, 11, 13, 9, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, command.dueAt)
        assertEquals("خرید", command.title)
    }

    @Test
    fun parsesListAndDeleteCommands() {
        assertEquals(YaraReminderCommand.List, YaraReminderCommandParser.parse("یادآوری‌ها", now))
        assertEquals(YaraReminderCommand.Delete(123), YaraReminderCommandParser.parse("حذف یادآوری ۱۲۳", now))
    }

    @Test
    fun rejectsPastOrMalformedReminders() {
        assertNull(YaraReminderCommandParser.parse("یادآوری امروز ساعت 09:00 | تماس", now))
        assertNull(YaraReminderCommandParser.parse("یادآوری فردا 09:30", now))
        assertNull(YaraReminderCommandParser.parse("یادآوری فردا ساعت 09:30 | x", now))
    }
}
