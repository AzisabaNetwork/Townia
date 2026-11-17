package net.azisaba.townia

import net.azisaba.townia.command.InviteCommand
import net.azisaba.townia.command.TownCommand
import net.azisaba.townia.data.Invite
import org.bukkit.Bukkit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Path
import java.util.UUID

class TownInviteBugTest {

    @Test
    fun `invite sends real player and town name and notifies target`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val target = env.mockPlayer(targetUuid, "Bob")

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(target)
            val town = env.townManager.createTown("berry_town", mayorUuid)

            val townCmd = TownCommand(env.plugin)
            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getPlayer(targetUuid) }.thenReturn(target)
                bukkit.`when`<Any> { Bukkit.getPlayerExact("Bob") }.thenReturn(target)

                townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("invite", "Bob"))
            }

            val sentToMayor = env.sentMessages.filter { it.recipient === mayor }
            assertTrue(sentToMayor.any { it.key == "town.invite-sent" && it.replacements == listOf("player", "Bob", "town", "berry_town") })
            assertFalse(sentToMayor.any { it.replacements.contains("Unknown") })

            val sentToTarget = env.sentMessages.filter { it.recipient === target }
            assertTrue(sentToTarget.any { it.key == "town.invite-received" && it.replacements == listOf("town", "berry_town", "inviter", "MayorAlice") })

            val dbInvite = env.databaseManager.getInvite(targetUuid, town.id!!)
            assertTrue(dbInvite.isPresent)
        }
    }

    @Test
    fun `re-inviting after expired invite replaces it instead of sending Unknown`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val target = env.mockPlayer(targetUuid, "Bob")

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(target)
            val town = env.townManager.createTown("berry_town", mayorUuid)

            // Simulate an expired invite left in DB from earlier
            val expiredAt = System.currentTimeMillis() - (env.towniaConfig.inviteTimeout + 60) * 1000L
            env.databaseManager.addInvite(Invite(0, targetUuid, town.id, mayorUuid, expiredAt))

            val townCmd = TownCommand(env.plugin)
            val inviteCmd = InviteCommand(env.plugin)
            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getPlayer(targetUuid) }.thenReturn(target)
                bukkit.`when`<Any> { Bukkit.getPlayerExact("Bob") }.thenReturn(target)

                townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("invite", "Bob"))

                val sentToMayor = env.sentMessages.filter { it.recipient === mayor }
                assertTrue(
                    sentToMayor.any { it.key == "town.invite-sent" && it.replacements == listOf("player", "Bob", "town", "berry_town") },
                    "Expected real player/town name, got: $sentToMayor"
                )
                assertFalse(sentToMayor.any { it.replacements.contains("Unknown") })

                val sentToTarget = env.sentMessages.filter { it.recipient === target }
                assertTrue(sentToTarget.any { it.key == "town.invite-received" })

                // Target can now accept the fresh invite
                inviteCmd.onCommand(target, env.dummyCommand("invite"), "invite", arrayOf("accept", "berry_town"))
            }

            val updatedTarget = env.residentManager.getResident(targetUuid).orElseThrow()
            assertEquals(town.id, updatedTarget.townUuid)
        }
    }

    @Test
    fun `re-inviting with pending invite refreshes invite and notifies target`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val mayorUuid = UUID.randomUUID()
            val targetUuid = UUID.randomUUID()
            val mayor = env.mockPlayer(mayorUuid, "MayorAlice")
            val target = env.mockPlayer(targetUuid, "Bob")

            env.residentManager.getOrCreate(mayor)
            env.residentManager.getOrCreate(target)
            val town = env.townManager.createTown("berry_town", mayorUuid)

            val townCmd = TownCommand(env.plugin)
            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Any> { Bukkit.getPlayer(targetUuid) }.thenReturn(target)
                bukkit.`when`<Any> { Bukkit.getPlayerExact("Bob") }.thenReturn(target)

                townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("invite", "Bob"))
                env.sentMessages.clear()
                townCmd.onCommand(mayor, env.dummyCommand("town"), "town", arrayOf("invite", "Bob"))
            }

            val sentToMayor = env.sentMessages.filter { it.recipient === mayor }
            assertTrue(sentToMayor.any { it.key == "town.invite-sent" && it.replacements == listOf("player", "Bob", "town", "berry_town") })
            assertFalse(sentToMayor.any { it.replacements.contains("Unknown") })
            assertTrue(env.sentMessages.any { it.recipient === target && it.key == "town.invite-received" })
            assertEquals(1, env.databaseManager.getInvitesByTarget(targetUuid).size)
        }
    }
}
