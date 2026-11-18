package net.azisaba.townia

import net.azisaba.townia.command.CommandHelp
import net.azisaba.townia.manager.MessageManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.io.File
import java.io.FileInputStream
import java.nio.file.Path

class CommandHelpTest {

    private fun realMessages(env: TowniaTestEnv): MessageManager {
        whenever(env.plugin.getResource("messages/lang_en.yml"))
            .thenAnswer { FileInputStream("src/main/resources/messages/lang_en.yml") }
        whenever(env.plugin.getResource("messages/lang_ja.yml"))
            .thenAnswer { FileInputStream("src/main/resources/messages/lang_ja.yml") }
        return MessageManager(env.plugin)
    }

    /** A console-like sender whose received lines are collected as plain text. */
    private fun capturingSender(lines: MutableList<String>): CommandSender {
        val sender = Mockito.mock(CommandSender::class.java)
        Mockito.doAnswer { inv ->
            lines.add(PlainTextComponentSerializer.plainText().serialize(inv.getArgument<Component>(0)))
            null
        }.whenever(sender).sendMessage(any<Component>())
        return sender
    }

    @Test
    fun `invalid arguments inside a command show that subcommand's usage`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mm = realMessages(env)
            val lines = mutableListOf<String>()
            val sender = capturingSender(lines)

            CommandHelp.withCommand("towniaadmin", arrayOf("addresident")) {
                mm.sendMessage(sender, "error.invalid-args")
            }

            assertTrue(lines.any { "/towniaadmin addresident <" in it }, "usage missing: $lines")
            assertTrue(lines.any { "/towniaadmin help addresident" in it }, "detail hint missing: $lines")
            assertFalse(lines.any { "/town help" in it && "/towniaadmin" !in it }, "still points to /town help: $lines")
        }
    }

    @Test
    fun `invalid arguments outside a command keep the plain message`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mm = realMessages(env)
            val lines = mutableListOf<String>()
            mm.sendMessage(capturingSender(lines), "error.invalid-args")
            assertEquals(1, lines.size)
        }
    }

    @Test
    fun `help detail shows usage, description and extra lines`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mm = realMessages(env)
            val lines = mutableListOf<String>()
            CommandHelp.handleHelp(mm, capturingSender(lines), "town", listOf("claim"))
            assertTrue(lines.any { "/town claim [" in it }, "usage missing: $lines")
            assertTrue(lines.size >= 4, "detail lines missing: $lines")
        }
    }

    @Test
    fun `help detail resolves nested subcommands and aliases`() {
        assertEquals(listOf("set.perm"), CommandHelp.findEntries("town", listOf("set", "perm", "outsider")))
        assertTrue(CommandHelp.findEntries("town", listOf("set")).size > 1)
        assertEquals(listOf("invite"), CommandHelp.findEntries("town", listOf("add", "Bob")))
        assertEquals(listOf("forsale"), CommandHelp.findEntries("plot", listOf("fs", "100")))
        assertEquals(listOf("set"), CommandHelp.findEntries("townconfig", listOf("allowSit")))
        assertTrue(CommandHelp.findEntries("town", listOf("nonsense")).isEmpty())
    }

    @Test
    fun `help list pages render without leftover placeholders in both languages`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mm = realMessages(env)
            for (root in listOf("town", "nation", "plot", "resident", "townia", "towniaadmin", "towniaworld", "invite", "townconfig")) {
                val lines = mutableListOf<String>()
                for (page in 1..5) CommandHelp.sendList(mm, capturingSender(lines), root, page)
                assertTrue(lines.isNotEmpty())
                for (line in lines) {
                    assertFalse(Regex("\\{[a-z_]+\\}").containsMatchIn(line), "placeholder left in /$root help: $line")
                    assertFalse("<click" in line || "<hover" in line || "</" in line, "unparsed tag in /$root help: $line")
                }
            }
        }
    }

    @Test
    fun `every help entry has usage and desc in both languages`() {
        for (lang in listOf("en", "ja")) {
            val cfg = YamlConfiguration.loadConfiguration(File("src/main/resources/messages/lang_$lang.yml"))
            for ((root, ids) in CommandHelp.allEntries()) {
                for (id in ids) {
                    for (field in listOf("usage", "desc")) {
                        assertTrue(
                            !cfg.getString("cmdhelp.$root.$id.$field").isNullOrBlank(),
                            "lang_$lang.yml is missing cmdhelp.$root.$id.$field"
                        )
                    }
                }
            }
        }
    }
}
