package com.yara.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YaraTaskCommandParserTest {
    @Test
    fun parsesNewTask() {
        assertEquals(
            YaraTaskCommand.Add("خرید نان"),
            YaraTaskCommandParser.parse("کار جدید: خرید نان")
        )
    }

    @Test
    fun parsesTaskLists() {
        assertEquals(YaraTaskCommand.ListPending, YaraTaskCommandParser.parse("کارها"))
        assertEquals(YaraTaskCommand.ListCompleted, YaraTaskCommandParser.parse("کارهای انجام‌شده"))
        assertEquals(YaraTaskCommand.ListCompleted, YaraTaskCommandParser.parse("کارهای انجام شده"))
    }

    @Test
    fun parsesCompleteAndDeleteWithPersianDigits() {
        assertEquals(YaraTaskCommand.Complete(123), YaraTaskCommandParser.parse("انجام شد ۱۲۳"))
        assertEquals(YaraTaskCommand.Delete(456), YaraTaskCommandParser.parse("حذف کار ۴۵۶"))
    }

    @Test
    fun rejectsEmptyOrUnrecognizedTaskCommands() {
        assertNull(YaraTaskCommandParser.parse("کار جدید:"))
        assertNull(YaraTaskCommandParser.parse("سلام"))
        assertNull(YaraTaskCommandParser.parse("انجام شد"))
    }
}
