package net.azisaba.townia.manager

import net.azisaba.townia.Townia
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.logging.Level

class MessageManager(private val plugin: Townia) {

    private val allMessages = HashMap<String, Map<String, Any>>()
    private lateinit var defaultLanguage: String
    private val miniMessage = MiniMessage.miniMessage()

    init {
        loadAllMessages()
    }

    fun loadAllMessages() {
        allMessages.clear()
        defaultLanguage = plugin.config.getString("default-language", "ja") ?: "ja"

        val messagesDir = File(plugin.dataFolder, "messages")
        if (!messagesDir.exists()) messagesDir.mkdirs()

        for (code in listOf("ja", "en")) {
            val targetFile = File(messagesDir, "lang_$code.yml")
            if (!targetFile.exists()) {
                try {
                    plugin.getResource("messages/lang_$code.yml")?.use { inputStream ->
                        targetFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                } catch (e: IOException) {
                    plugin.logger.log(Level.WARNING, "Failed to extract lang_$code.yml", e)
                }
            }
        }

        val langFiles = messagesDir.listFiles { _, name -> name.matches(Regex("lang_\\w+\\.yml")) }
        if (langFiles == null) return

        for (langFile in langFiles) {
            val langCode = langFile.name.replace("lang_", "").replace(".yml", "")

            val messages = LinkedHashMap<String, Any>()
            try {
                InputStreamReader(FileInputStream(langFile), StandardCharsets.UTF_8).use { reader ->
                    val cfg = YamlConfiguration.loadConfiguration(reader)
                    for (key in cfg.getKeys(true)) {
                        if (!cfg.isConfigurationSection(key)) {
                            cfg.get(key)?.let { messages.put(key, it) }
                        }
                    }
                }
            } catch (e: IOException) {
                plugin.logger.log(Level.WARNING, "Failed to load ${langFile.name}", e)
            }

            try {
                plugin.getResource("messages/lang_$langCode.yml")?.use { input ->
                    InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                        val internalCfg = YamlConfiguration.loadConfiguration(reader)
                        for (key in internalCfg.getKeys(true)) {
                            if (!internalCfg.isConfigurationSection(key) && !messages.containsKey(key)) {
                                internalCfg.get(key)?.let { messages.put(key, it) }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                plugin.logger.log(Level.WARNING, "Failed to load internal lang_$langCode.yml", e)
            }

            allMessages[langCode] = messages
            plugin.logger.info("Loaded language file: ${langFile.name} (${messages.size} keys)")
        }
    }

    fun sendMessage(sender: CommandSender, key: String, vararg replacements: String) {
        val language = getLanguage(sender)
        val mesageObject = getMessageObject(language, key)

        val lines: List<*> = when (mesageObject) {
            is List<*> -> mesageObject
            else -> listOf(mesageObject)
        }

        val prefix = plugin.towniaConfig.prefix
        for (line in lines) {
            val raw = replaceLegacyColors(prefix + replacePlaceholders(line.toString(), *replacements))
            sender.sendMessage(miniMessage.deserialize(raw))
        }
    }

    fun sendActionBar(player: Player, key: String, vararg replacements: String) {
        val language = getLanguage(player)
        val messageObject = getMessageObject(language, key)

        val raw = if (messageObject is List<*> && messageObject.isNotEmpty()) {
            messageObject.first().toString()
        } else {
            messageObject.toString()
        }

        val processedRaw = replaceLegacyColors(replacePlaceholders(raw, *replacements))
        player.sendActionBar(miniMessage.deserialize(processedRaw))
    }

    fun getPlainMessage(sender: CommandSender, key: String, vararg replacements: String): String {
        val language = getLanguage(sender)
        val obj = getMessageObject(language, key)

        val raw = if (obj is List<*> && obj.isNotEmpty()) {
            obj.first().toString()
        } else {
            obj.toString()
        }

        val processedRaw = replaceLegacyColors(replacePlaceholders(raw, *replacements))
        val component = miniMessage.deserialize(processedRaw)
        return PlainTextComponentSerializer.plainText().serialize(component)
    }


    private fun getLanguage(sender: CommandSender): String {
        if (sender is Player) {
            val clientLanguage = sender.locale().language.lowercase()
            if (allMessages.containsKey(clientLanguage)) return clientLanguage
        }
        return defaultLanguage
    }

    private fun getMessageObject(language: String, key: String): Any? {
        val messages = allMessages[language]
        val defaultMessages = allMessages[defaultLanguage]

        if (messages != null && messages.containsKey(key)) {
            return messages[key] ?: "<red>Missing message key: $key"
        }

        if (defaultMessages != null && defaultMessages.containsKey(key)) {
            return defaultMessages[key] ?: "<red>Missing message key: $key"
        }

        return "<red>Missing message key: $key"
    }

    private fun replacePlaceholders(text: String, vararg replacements: String): String {
        require(replacements.size % 2 == 0) { "Replacements must be in pairs (key, value, key, value, ...)" }
        var result = text
        replacements.asSequence().chunked(2).forEach { pair ->
            if (pair.size == 2) {
                val rawKey = pair[0]
                val value = pair[1]
                val formattedKey = if (rawKey.startsWith("{") && rawKey.endsWith("}")) rawKey else "{${rawKey}}"
                result = result.replace(formattedKey, value)
            }
        }
        return result
    }

    private fun replaceLegacyColors(text: String): String {
        return text.replace(Regex("[&§]([0-9a-fk-orA-FK-OR])")) { match ->
            when (match.groupValues[1].lowercase()) {
                "0" -> "<black>"
                "1" -> "<dark_blue>"
                "2" -> "<dark_green>"
                "3" -> "<dark_aqua>"
                "4" -> "<dark_red>"
                "5" -> "<dark_purple>"
                "6" -> "<gold>"
                "7" -> "<gray>"
                "8" -> "<dark_gray>"
                "9" -> "<blue>"
                "a" -> "<green>"
                "b" -> "<aqua>"
                "c" -> "<red>"
                "d" -> "<light_purple>"
                "e" -> "<yellow>"
                "f" -> "<white>"
                "k" -> "<obfuscated>"
                "l" -> "<bold>"
                "m" -> "<strikethrough>"
                "n" -> "<underlined>"
                "o" -> "<italic>"
                "r" -> "<reset>"
                else -> match.value
            }
        }
    }
}
