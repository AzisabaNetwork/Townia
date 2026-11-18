package net.azisaba.townia

import net.azisaba.townia.manager.MessageManager
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.whenever
import java.io.File
import java.io.FileInputStream
import java.nio.file.Path

class MessageAuditTest {

    private fun flattenKeys(file: File): Map<String, String> {
        val cfg = YamlConfiguration.loadConfiguration(file)
        val out = LinkedHashMap<String, String>()
        for (key in cfg.getKeys(true)) {
            if (!cfg.isConfigurationSection(key)) {
                out[key] = cfg.get(key)?.toString() ?: ""
            }
        }
        return out
    }

    private fun placeholders(text: String): Set<String> =
        Regex("\\{([a-zA-Z0-9_.-]+)\\}").findAll(text).map { it.groupValues[1] }.toSet()

    @Test
    fun `lang_en and lang_ja have matching keys and placeholders`() {
        val en = flattenKeys(File("src/main/resources/messages/lang_en.yml"))
        val ja = flattenKeys(File("src/main/resources/messages/lang_ja.yml"))

        assertEquals(en.keys, ja.keys, "Key mismatch between lang_en.yml and lang_ja.yml")

        for (key in en.keys) {
            assertEquals(
                placeholders(en.getValue(key)),
                placeholders(ja.getValue(key)),
                "Placeholder mismatch for key '$key'"
            )
        }
    }

    @Test
    fun `no message key is parsed as a YAML boolean`() {
        // Unquoted yes/no/on/off/true/false keys become "true"/"false" under YAML 1.1
        for (lang in listOf("en", "ja")) {
            val keys = flattenKeys(File("src/main/resources/messages/lang_$lang.yml")).keys
            val bad = keys.filter { it.substringAfterLast('.') in setOf("true", "false") }
            assertTrue(bad.isEmpty(), "Boolean-parsed keys in lang_$lang.yml (quote them): $bad")
        }
    }

    @Test
    fun `every message key referenced in code is defined`() {
        val keyLiteral = Regex("\"([a-z][a-z0-9_-]*\\.[a-z0-9_.-]+)\"")
        val referenced = File("src/main/java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .flatMap { it.readLines().asSequence() }
            .filter { "messageManager." in it || "TowniaException(" in it }
            .flatMap { line -> keyLiteral.findAll(line).map { it.groupValues[1] } }
            .toSet()

        for (lang in listOf("en", "ja")) {
            val defined = flattenKeys(File("src/main/resources/messages/lang_$lang.yml")).keys
            val sections = defined.map { it.substringBefore('.') }.toSet()
            val missing = referenced
                .filter { it.substringBefore('.') in sections }
                .filter { key -> key !in defined && defined.none { it.startsWith("$key.") } }
                .sorted()
            assertTrue(missing.isEmpty(), "Keys missing from lang_$lang.yml: $missing")
        }
    }

    @Test
    fun `MessageManager replaces placeholders cleanly in both languages`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            whenever(env.plugin.getResource("messages/lang_en.yml"))
                .thenAnswer { FileInputStream("src/main/resources/messages/lang_en.yml") }
            whenever(env.plugin.getResource("messages/lang_ja.yml"))
                .thenAnswer { FileInputStream("src/main/resources/messages/lang_ja.yml") }

            val mm = MessageManager(env.plugin)

            val checks = listOf(
                Triple("nation.created", arrayOf("nation", "Azisaba"), "Azisaba"),
                Triple("nation.joined", arrayOf("nation", "Azisaba"), "Azisaba"),
                Triple("nation.left", arrayOf("nation", "Azisaba"), "Azisaba"),
                Triple("town.reslist-header", arrayOf("town", "berry_town", "count", "28"), "berry_town"),
                Triple("town.withdraw-insufficient", arrayOf("balance", "100.00"), "100.00"),
                Triple("admin.rank-set", arrayOf("town", "berry_town", "player", "Alice", "rank", "mayor"), "Alice"),
                Triple("admin.resident-added", arrayOf("town", "berry_town", "player", "Bob"), "Bob"),
                Triple("error.town-not-found", arrayOf("town", "berry_town"), "berry_town"),
                Triple("error.player-not-found", arrayOf("player", "Bob"), "Bob"),
                Triple("common.yes", arrayOf<String>(), ""),
                Triple("common.no", arrayOf<String>(), ""),
                Triple("error.unknown", arrayOf<String>(), "")
            )

            for ((key, args, expectedSubstring) in checks) {
                val plain = mm.getPlainMessage(null, key, *args)
                assertFalse(plain.startsWith("Missing message key"), "Missing key: $key")
                assertFalse(Regex("\\{[a-zA-Z0-9_.-]+\\}").containsMatchIn(plain), "Unreplaced placeholder in $key: $plain")
                assertTrue(plain.contains(expectedSubstring), "Expected '$expectedSubstring' in '$plain' for $key")
            }
        }
    }
}
