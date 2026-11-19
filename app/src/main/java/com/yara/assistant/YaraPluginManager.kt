package com.yara.assistant

import android.content.Context

enum class YaraPluginStatus { READY, PLANNED }
enum class YaraPluginCategory(val title: String) {
    AI("هوش مصنوعی"),
    MEMORY("حافظه و دانش"),
    VOICE("گفتار و زبان"),
    AVATAR("آواتار"),
    DEVICE("کنترل اندروید"),
    CONNECTIVITY("اینترنت و سرویس‌ها"),
    AUTOMATION("مدیریت و خودکارسازی"),
    SECURITY("امنیت"),
    DEVELOPER("ابزارهای توسعه")
}

data class YaraPluginDefinition(
    val id: String,
    val name: String,
    val description: String,
    val category: YaraPluginCategory,
    val status: YaraPluginStatus,
    val requiresAccount: Boolean = false
)

/**
 * Stable plugin contract. Implementations remain in the app/core modules;
 * remote integrations are accessed through reviewed API/MCP adapters.
 */
interface YaraPlugin {
    val definition: YaraPluginDefinition
    suspend fun initialize(context: Context): Result<Unit>
    suspend fun shutdown()
}

class YaraPluginManager(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("yara_plugins", Context.MODE_PRIVATE)

    fun catalog(): List<YaraPluginDefinition> = CATALOG

    fun isEnabled(pluginId: String): Boolean {
        val plugin = CATALOG.firstOrNull { it.id == pluginId } ?: return false
        if (plugin.status != YaraPluginStatus.READY) return false
        return preferences.getBoolean(pluginId, plugin.id in DEFAULT_ENABLED)
    }

    fun setEnabled(pluginId: String, enabled: Boolean) {
        val plugin = CATALOG.firstOrNull { it.id == pluginId }
            ?: throw IllegalArgumentException("افزونه ناشناخته است.")
        require(plugin.status == YaraPluginStatus.READY) {
            "این افزونه هنوز در نسخه اندروید پیاده‌سازی نشده است."
        }
        preferences.edit().putBoolean(pluginId, enabled).apply()
    }

    fun enabledPlugins(): List<YaraPluginDefinition> =
        CATALOG.filter { it.status == YaraPluginStatus.READY && isEnabled(it.id) }

    companion object {
        private val DEFAULT_ENABLED = setOf(
            "ai.groq", "ai.gemini", "memory.local", "voice.android",
            "avatar.webgl", "automation.reminders", "automation.tasks",
            "web.search"
        )

        private val CATALOG = listOf(
            YaraPluginDefinition("ai.groq", "Groq", "مدل اصلی یا جایگزین از طریق API سازگار", YaraPluginCategory.AI, YaraPluginStatus.READY, true),
            YaraPluginDefinition("ai.gemini", "Google Gemini", "اتصال Gemini از طریق API سازگار", YaraPluginCategory.AI, YaraPluginStatus.READY, true),
            YaraPluginDefinition("ai.router", "مسیریاب چندمدلی", "انتخاب مدل اصلی و انتقال به مدل جایگزین", YaraPluginCategory.AI, YaraPluginStatus.READY),
            YaraPluginDefinition("ai.openai", "OpenAI", "اتصال مستقل به API مدل‌های OpenAI", YaraPluginCategory.AI, YaraPluginStatus.PLANNED, true),
            YaraPluginDefinition("ai.claude", "Claude", "اتصال مستقل به API کلود", YaraPluginCategory.AI, YaraPluginStatus.PLANNED, true),
            YaraPluginDefinition("ai.local", "مدل‌های محلی", "اتصال به موتورهای مدل محلی روی دستگاه یا شبکه", YaraPluginCategory.AI, YaraPluginStatus.PLANNED),
            YaraPluginDefinition("memory.local", "حافظه رمزگذاری‌شده", "حافظه محلی مستقل از ارائه‌دهنده مدل", YaraPluginCategory.MEMORY, YaraPluginStatus.READY),
            YaraPluginDefinition("memory.cloud", "همگام‌سازی ابری", "پشتیبان‌گیری و همگام‌سازی رمزگذاری‌شده", YaraPluginCategory.MEMORY, YaraPluginStatus.PLANNED),
            YaraPluginDefinition("memory.semantic", "جست‌وجوی معنایی", "بازیابی مفهومی و پایگاه برداری", YaraPluginCategory.MEMORY, YaraPluginStatus.PLANNED),
            YaraPluginDefinition("voice.android", "گفتار اندروید", "تشخیص گفتار و خواندن پاسخ با موتور دستگاه", YaraPluginCategory.VOICE, YaraPluginStatus.READY),
            YaraPluginDefinition("voice.wakeword", "عبارت بیدارباش", "فعال‌سازی صوتی در محدوده مجاز اندروید", YaraPluginCategory.VOICE, YaraPluginStatus.PLANNED),
            YaraPluginDefinition("avatar.webgl", "آواتار یارا", "نمایش آواتار و کنترل وضعیت گفتار", YaraPluginCategory.AVATAR, YaraPluginStatus.READY),
            YaraPluginDefinition("avatar.lipsync", "لب‌خوانی پیشرفته", "هماهنگی دقیق واج‌ها و Blend Shapes", YaraPluginCategory.AVATAR, YaraPluginStatus.PLANNED),
            YaraPluginDefinition("device.apps", "اجرای برنامه‌ها", "بازکردن برنامه‌ها از طریق Android Intents", YaraPluginCategory.DEVICE, YaraPluginStatus.PLANNED),
            YaraPluginDefinition("device.notifications", "مدیریت اعلان‌ها", "دسترسی اعلان با مجوز صریح سیستم", YaraPluginCategory.DEVICE, YaraPluginStatus.PLANNED),
            YaraPluginDefinition("device.files", "مدیریت فایل", "دسترسی محدود به فایل‌های انتخاب‌شده", YaraPluginCategory.DEVICE, YaraPluginStatus.PLANNED),
            YaraPluginDefinition("web.search", "جست‌وجوی وب", "جست‌وجوی اینترنتی درون برنامه", YaraPluginCategory.CONNECTIVITY, YaraPluginStatus.READY),
            YaraPluginDefinition("web.browser", "کنترل مرورگر", "گردش‌کارهای مرورگر با تأیید و محدودیت دامنه", YaraPluginCategory.CONNECTIVITY, YaraPluginStatus.PLANNED),
            YaraPluginDefinition("cloud.drive", "Google Drive", "مدیریت فایل‌های فضای ابری", YaraPluginCategory.CONNECTIVITY, YaraPluginStatus.PLANNED, true),
            YaraPluginDefinition("productivity.gmail", "Gmail", "خواندن و مدیریت ایمیل با OAuth", YaraPluginCategory.CONNECTIVITY, YaraPluginStatus.PLANNED, true),
            YaraPluginDefinition("productivity.calendar", "Google Calendar", "تقویم و زمان‌بندی", YaraPluginCategory.CONNECTIVITY, YaraPluginStatus.PLANNED, true),
            YaraPluginDefinition("productivity.todoist", "Todoist", "مدیریت کارها و وظایف", YaraPluginCategory.CONNECTIVITY, YaraPluginStatus.PLANNED, true),
            YaraPluginDefinition("developer.github", "GitHub", "مدیریت مخزن، issue و گردش‌کار توسعه", YaraPluginCategory.DEVELOPER, YaraPluginStatus.PLANNED, true),
            YaraPluginDefinition("knowledge.notion", "Notion", "پایگاه دانش و اسناد", YaraPluginCategory.CONNECTIVITY, YaraPluginStatus.PLANNED, true),
            YaraPluginDefinition("automation.reminders", "یادآورها", "ثبت و زمان‌بندی یادآورها", YaraPluginCategory.AUTOMATION, YaraPluginStatus.READY),
            YaraPluginDefinition("automation.tasks", "مدیریت وظایف", "ثبت، تکمیل و حذف کارها", YaraPluginCategory.AUTOMATION, YaraPluginStatus.READY),
            YaraPluginDefinition("security.approvals", "تأیید عملیات حساس", "درخواست تأیید پیش از اقدام حساس", YaraPluginCategory.SECURITY, YaraPluginStatus.PLANNED),
            YaraPluginDefinition("security.audit", "گزارش امنیتی", "ثبت رخدادها و ردپای اقدامات", YaraPluginCategory.SECURITY, YaraPluginStatus.PLANNED),
            YaraPluginDefinition("developer.mcp", "درگاه MCP", "اتصال استاندارد ابزارها و سرویس‌های سازگار", YaraPluginCategory.DEVELOPER, YaraPluginStatus.PLANNED)
        )
    }
}
