package com.yara.assistant

sealed class YaraMemoryCommand {
    data class Save(val content: String) : YaraMemoryCommand()
    data class Forget(val content: String) : YaraMemoryCommand()
    data object List : YaraMemoryCommand()
}

object YaraMemoryCommandParser {
    fun parse(input: String): YaraMemoryCommand? {
        val clean = input.trim()
        val normalized = clean.lowercase()
        return when {
            normalized.startsWith("به خاطر بسپار") -> clean
                .drop("به خاطر بسپار".length).trim().trimStart(':', '،', ' ')
                .takeIf { it.length >= 3 }?.let(YaraMemoryCommand::Save)
            normalized.startsWith("فراموش کن") -> clean
                .drop("فراموش کن".length).trim().trimStart(':', '،', ' ')
                .takeIf { it.length >= 3 }?.let(YaraMemoryCommand::Forget)
            normalized == "حافظه‌هایم را فهرست کن" || normalized == "فهرست حافظه" ->
                YaraMemoryCommand.List
            else -> null
        }
    }
}
