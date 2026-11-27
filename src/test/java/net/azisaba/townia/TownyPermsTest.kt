package net.azisaba.townia

import net.azisaba.townia.command.NationCommand
import net.azisaba.townia.command.TownCommand
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.migration.TownyFlatfileMigrator
import net.azisaba.townia.permission.Perm
import org.bukkit.Bukkit
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Path
import java.util.UUID

/** Ranks and permission nodes behave as Towny 0.96's townyperms.yml describes. */
class TownyPermsTest {

    private class Cast(env: TowniaTestEnv) {
        val mayor: Player = env.mockPlayer(UUID.randomUUID(), "Mayor")
        val assistant: Player = env.mockPlayer(UUID.randomUUID(), "Assistant")
        val subMayor: Player = env.mockPlayer(UUID.randomUUID(), "SubMayor")
        val resident: Player = env.mockPlayer(UUID.randomUUID(), "Resident")
        val nomad: Player = env.mockPlayer(UUID.randomUUID(), "Nomad")
        val townId: UUID

        init {
            listOf(mayor, assistant, subMayor, resident, nomad).forEach { env.residentManager.getOrCreate(it) }
            townId = env.townManager.createTown("berry_town", mayor.uniqueId).id!!
            env.residentManager.setTown(assistant.uniqueId, townId, TownRank.ASSISTANT)
            env.residentManager.setTown(subMayor.uniqueId, townId, TownRank.SUB_MAYOR)
            env.residentManager.setTown(resident.uniqueId, townId, TownRank.RESIDENT)
        }
    }

    @Test
    fun `each standing gets the nodes of the default townyperms`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val c = Cast(env)

            // Townless players found or join a town, look around and use every /resident command (as in 1.3.3).
            assertTrue(Perm.has(c.nomad, "towny.command.town.new"))
            assertTrue(Perm.has(c.nomad, "towny.command.town.list"))
            assertTrue(Perm.has(c.nomad, "towny.command.town.reslist"))
            assertTrue(Perm.has(c.nomad, "towny.command.towny.top"))
            assertTrue(Perm.has(c.nomad, "towny.town.spawn.public"))
            assertTrue(Perm.has(c.nomad, "towny.command.town.othertown"), "default-true node")
            assertFalse(Perm.has(c.nomad, "towny.command.town.leave"))
            assertTrue(Perm.has(c.nomad, "towny.command.resident.friend"))

            // Ordinary members: their own plots and the bank, but no town management.
            assertTrue(Perm.has(c.resident, "towny.command.town.deposit"))
            assertTrue(Perm.has(c.resident, "towny.command.plot.claim"))
            assertTrue(Perm.has(c.resident, "towny.command.plot.toggle.pvp"))
            assertTrue(Perm.has(c.resident, "towny.command.resident.friend"))
            assertTrue(Perm.has(c.resident, "towny.command.resident.spawn"), "granted through resident.*")
            assertTrue(Perm.has(c.resident, "towny.command.town.bankhistory"))
            assertTrue(Perm.has(c.resident, "towny.command.plot.set.name"), "their own plot")
            assertFalse(Perm.has(c.resident, "towny.command.town.claim.town"))
            assertFalse(Perm.has(c.resident, "towny.command.plot.asmayor"))
            assertFalse(Perm.has(c.resident, "towny.command.town.withdraw"))
            assertFalse(Perm.has(c.resident, "towny.tax_exempt"))

            // The assistant of 1.3.3: claims and unclaims, spawn, perms, outlaws and jail, invites
            // and the town's plots - but no public toggle, ranks, tax exemption or private plots.
            assertTrue(Perm.has(c.assistant, "towny.command.town.claim.town"))
            assertTrue(Perm.has(c.assistant, "towny.command.town.claim.outpost"))
            assertTrue(Perm.has(c.assistant, "towny.command.town.unclaim"))
            assertTrue(Perm.has(c.assistant, "towny.command.town.unclaim.all"))
            assertTrue(Perm.has(c.assistant, "towny.command.town.set.spawn"))
            assertTrue(Perm.has(c.assistant, "towny.command.town.outlaw"))
            assertTrue(Perm.has(c.assistant, "towny.command.town.invite.add"))
            assertTrue(Perm.has(c.assistant, "towny.command.plot.asmayor"))
            assertTrue(Perm.has(c.assistant, "towny.command.plot.evict"))
            assertFalse(Perm.has(c.assistant, "towny.command.town.toggle.public"))
            assertFalse(Perm.has(c.assistant, "towny.command.town.rank.helper"))
            assertFalse(Perm.has(c.assistant, "towny.command.town.toggle.pvp"))
            assertFalse(Perm.has(c.assistant, "towny.command.town.kick"))
            assertFalse(Perm.has(c.assistant, "towny.tax_exempt"))
            assertFalse(Perm.holds(c.assistant, "towny.claimed.owntown.switch.CHEST"), "no key to the residents' chests")

            // Townia's sub-mayor keeps what it could do before nodes existed.
            assertTrue(Perm.has(c.subMayor, "towny.command.town.unclaim"))
            assertTrue(Perm.has(c.subMayor, "towny.command.town.unclaim.all"))
            assertTrue(Perm.has(c.subMayor, "towny.command.town.set.spawn"))
            assertTrue(Perm.has(c.subMayor, "towny.command.town.outlaw"))
            assertFalse(Perm.has(c.subMayor, "towny.command.town.kick"))
            assertFalse(Perm.has(c.subMayor, "towny.command.town.withdraw"))
            assertFalse(Perm.holds(c.subMayor, "towny.claimed.owntown.build.STONE"), "only the mayor builds in every plot of the town")

            // The mayor has the whole town tree - except claiming several chunks at once,
            // which Towny switches off under town.claim.* until an admin grants it.
            assertTrue(Perm.has(c.mayor, "towny.command.town.kick"))
            assertTrue(Perm.has(c.mayor, "towny.command.town.set.taxes"))
            assertTrue(Perm.has(c.mayor, "towny.command.town.toggle.pvp"))
            assertTrue(Perm.has(c.mayor, "towny.command.town.rank.sheriff"))
            assertTrue(Perm.has(c.mayor, "towny.command.town.bankhistory"))
            assertTrue(Perm.has(c.mayor, "towny.command.nation.new"))
            assertTrue(Perm.has(c.mayor, "towny.command.nation.bankhistory"))
            assertTrue(Perm.has(c.mayor, "towny.tax_exempt"))
            assertFalse(Perm.has(c.mayor, "towny.command.town.claim.town.multiple"))
            assertFalse(Perm.has(c.mayor, "towny.command.nation.kick"))
        }
    }

    @Test
    fun `an admin grant from the permission plugin wins over townyperms`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val c = Cast(env)
            assertFalse(Perm.has(c.mayor, "towny.command.town.claim.town.multiple"))
            whenever(c.mayor.isPermissionSet("towny.command.town.claim.town.multiple")).thenReturn(true)
            whenever(c.mayor.hasPermission("towny.command.town.claim.town.multiple")).thenReturn(true)
            assertTrue(Perm.has(c.mayor, "towny.command.town.claim.town.multiple"))

            // Operators and holders of towny.admin may do everything.
            whenever(c.nomad.isOp).thenReturn(true)
            assertTrue(Perm.has(c.nomad, "towny.command.town.delete"))
            whenever(c.resident.hasPermission("towny.admin")).thenReturn(true)
            assertTrue(Perm.has(c.resident, "towny.command.town.delete"))
            // ...but build overrides are not handed out by being an admin.
            assertFalse(Perm.holds(c.resident, "towny.claimed.alltown.build.STONE"))
            // Bukkit answers yes for an operator to every node nobody registered, wildcards included.
            whenever(c.nomad.hasPermission(org.mockito.kotlin.any<String>())).thenReturn(true)
            assertFalse(Perm.holds(c.nomad, "towny.claimed.alltown.build.STONE"), "an operator still needs /towniaadmin bypass to build")
            // A wildcard the permission plugin really granted does count.
            whenever(c.nomad.isPermissionSet("towny.claimed.alltown.build.*")).thenReturn(true)
            assertTrue(Perm.holds(c.nomad, "towny.claimed.alltown.build.STONE"))
        }
    }

    @Test
    fun `town commands follow the nodes`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val c = Cast(env)
            val cmd = TownCommand(env.plugin)
            // Standing in the wilderness: an unclaim that is let through finds nothing to free.
            val chunk = mock<org.bukkit.Chunk>()
            whenever(chunk.world).thenReturn(env.world)
            whenever(env.world.getChunkAt(org.mockito.kotlin.any<org.bukkit.Location>())).thenReturn(chunk)
            fun run(who: Player, vararg args: String): List<String> {
                env.sentMessages.clear()
                cmd.onCommand(who, env.dummyCommand("town"), "town", arrayOf(*args))
                return env.sentMessages.filter { it.recipient === who }.map { it.key }
            }

            assertFalse("error.no-permission" in run(c.resident, "reslist"))
            assertFalse("error.no-permission" in run(c.mayor, "reslist"))
            assertTrue("town.not-mayor" in run(c.assistant, "set", "board", "hello"))
            assertTrue("town.not-mayor" in run(c.assistant, "toggle", "pvp"))
            assertTrue("town.not-mayor" in run(c.assistant, "toggle", "public"))
            assertFalse("town.not-assistant" in run(c.assistant, "unclaim"))
            assertTrue("town.not-assistant" in run(c.resident, "unclaim"))
            assertTrue("error.no-permission" in run(c.nomad, "leave"))
        }
    }

    @Test
    fun `ranks from townyperms can be granted removed and are saved`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val c = Cast(env)
            val cmd = TownCommand(env.plugin)
            fun run(who: Player, vararg args: String): List<String> {
                env.sentMessages.clear()
                Mockito.mockStatic(Bukkit::class.java).use {
                    cmd.onCommand(who, env.dummyCommand("town"), "town", arrayOf(*args))
                }
                return env.sentMessages.filter { it.recipient === who }.map { it.key }
            }
            val target = env.residentManager.getResident(c.resident.uniqueId).get()

            assertFalse(Perm.holds(c.resident, "towny.claimed.townowned.switch.CHEST"))
            assertTrue("town.rank-set" in run(c.mayor, "rank", "add", "Resident", "helper"))
            assertTrue("helper" in target.townRanks)
            assertTrue(Perm.holds(c.resident, "towny.claimed.townowned.switch.CHEST"), "the helper rank opens town-owned switches")
            assertFalse(Perm.holds(c.resident, "towny.claimed.townowned.build.STONE"))

            // Ranks are the mayor's to give, as in 1.3.3.
            assertTrue("error.no-permission" in run(c.assistant, "rank", "add", "Resident", "vip"))
            assertTrue("error.no-permission" in run(c.assistant, "rank", "add", "Resident", "sheriff"))
            assertTrue("town.rank-set" in run(c.mayor, "rank", "add", "Resident", "vip"))
            assertTrue("error.rank-not-found" in run(c.mayor, "rank", "add", "Resident", "wizard"))
            assertTrue("error.no-permission" in run(c.resident, "rank", "add", "Assistant", "helper"))

            // The sheriff may jail; the built-in assistant rank still goes through the same command.
            assertTrue("town.rank-set" in run(c.mayor, "rank", "add", "Resident", "sheriff"))
            assertTrue(Perm.has(c.resident, "towny.command.town.toggle.jail"))
            assertTrue("town.rank-set" in run(c.mayor, "rank", "add", "Resident", "assistant"))
            assertEquals(TownRank.ASSISTANT, target.rank)

            env.databaseManager.flush()
            val stored = env.databaseManager.getResident(c.resident.uniqueId).get()
            assertEquals(setOf("helper", "vip", "sheriff"), stored.townRanks.toSet())
            assertEquals(TownRank.ASSISTANT, stored.rank)

            assertTrue("town.rank-removed" in run(c.mayor, "rank", "remove", "Resident", "sheriff"))
            assertFalse("sheriff" in target.townRanks)
            assertTrue(Perm.has(c.resident, "towny.command.town.toggle.jail"), "an assistant jails anyway, as in 1.3.3")
            // Without a rank, everything goes.
            run(c.mayor, "rank", "remove", "Resident")
            assertTrue(target.townRanks.isEmpty())
            assertEquals(TownRank.RESIDENT, target.rank)

            // Leaving the town drops whatever is left.
            target.townRanks.add("helper")
            env.residentManager.clearTown(c.resident.uniqueId)
            assertTrue(target.townRanks.isEmpty())
            assertFalse(Perm.holds(c.resident, "towny.claimed.townowned.switch.CHEST"))
        }
    }

    @Test
    fun `only the king may change the nation and nation ranks add nodes`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val c = Cast(env)
            val otherMayor = env.mockPlayer(UUID.randomUUID(), "OtherMayor")
            env.residentManager.getOrCreate(otherMayor)
            val otherTown = env.townManager.createTown("plum_town", otherMayor.uniqueId)
            env.nationManager.createNation("Azisaba", c.townId, c.mayor.uniqueId)
            val nation = env.nationManager.getNationByName("Azisaba").orElseThrow()
            env.nationManager.addTownToNation(nation.id, otherTown.id)

            val cmd = NationCommand(env.plugin)
            fun run(who: Player, vararg args: String): List<String> {
                env.sentMessages.clear()
                Mockito.mockStatic(Bukkit::class.java).use {
                    cmd.onCommand(who, env.dummyCommand("nation"), "nation", arrayOf(*args))
                }
                return env.sentMessages.filter { it.recipient === who }.map { it.key }
            }

            assertTrue(Perm.has(c.mayor, "towny.command.nation.kick"), "the king holds nation.*")
            assertTrue(Perm.has(c.resident, "towny.command.nation.deposit"), "every nation member may deposit")
            assertFalse(Perm.has(otherMayor, "towny.command.nation.toggle.neutral"))

            // This used to be open to the mayor of any member town.
            assertTrue("nation.not-leader" in run(otherMayor, "toggle", "neutral"))
            assertFalse(nation.isNeutral)
            assertTrue("nation.neutral-set" in run(c.mayor, "toggle", "neutral"))
            assertTrue(nation.isNeutral)

            // A nation assistant may invite towns and manage allies.
            assertFalse(Perm.has(otherMayor, "towny.command.nation.invite.add"))
            assertTrue("town.rank-set" in run(c.mayor, "rank", "add", "OtherMayor", "assistant"))
            assertTrue(Perm.has(otherMayor, "towny.command.nation.invite.add"))
            assertTrue(Perm.has(otherMayor, "towny.command.nation.ally.add"))
            assertFalse(Perm.has(otherMayor, "towny.command.nation.kick"))
            assertTrue("error.no-permission" in run(otherMayor, "rank", "add", "Mayor", "assistant"))
        }
    }

    @Test
    fun `a townyperms file from Towny is read as it is`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val c = Cast(env)
            File(env.dataFolder, "townyperms.yml").writeText(
                """
                nomad:
                    - towny.command.town.new
                towns:
                    default:
                        - towny.command.town.reslist
                    mayor:
                        - towny.command.town.*
                    ranks:
                        builder:
                            - towny.command.town.claim.*
                            - -towny.command.town.claim.outpost
                        guest:
                nations:
                    default:
                    king:
                        - towny.command.nation.*
                    ranks:
                """.trimIndent()
            )
            env.townyPerms.load()

            assertEquals(setOf("builder", "guest", "sub_mayor"), env.townyPerms.townRankNames())
            assertTrue(Perm.has(c.resident, "towny.command.town.reslist"))
            assertFalse(Perm.has(c.resident, "towny.command.town.deposit"), "no longer listed")
            assertFalse(Perm.has(c.nomad, "towny.command.town.list"))

            val resident = env.residentManager.getResident(c.resident.uniqueId).get()
            resident.townRanks.add("builder")
            assertTrue(Perm.has(c.resident, "towny.command.town.claim.town"))
            assertFalse(Perm.has(c.resident, "towny.command.town.claim.outpost"), "taken away with a leading -")
            // The file knows nothing of sub_mayor; the rank must keep working anyway.
            assertTrue(Perm.has(c.subMayor, "towny.command.town.unclaim"))
            // "assistant" is not in this file, so a Townia assistant gets no extra nodes.
            assertFalse(Perm.has(c.assistant, "towny.command.town.claim.town"))
        }
    }

    @Test
    fun `migration keeps every rank a resident held in Towny`(@TempDir tempDir: Path) {
        val data = File(tempDir.toFile(), "Towny/data")
        fun write(path: String, text: String) {
            val file = File(data, path)
            file.parentFile.mkdirs()
            file.writeText(text.trimIndent() + "\n")
        }
        val townId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        write(
            "towns/Alpha.txt", """
            name=Alpha
            residents=Ann,Bob,Cid
            mayor=Ann
            uuid=$townId
            """
        )
        write("residents/Ann.txt", "town=Alpha\nfriends=")
        write("residents/Bob.txt", "town=Alpha\ntown-ranks=assistant,helper,Sheriff\nnation-ranks=helper\nfriends=")
        write("residents/Cid.txt", "town=Alpha\ntown-ranks=vip\nfriends=")

        TowniaTestEnv(tempDir.resolve("Townia")).use { env ->
            Mockito.mockStatic(Bukkit::class.java).use {
                TownyFlatfileMigrator.migrateOnly(env.plugin, mock<ConsoleCommandSender>(), "Alpha", null, false, false)
            }
            val bob = env.residentManager.getResidentByName("Bob").get()
            assertEquals(TownRank.ASSISTANT, bob.rank)
            assertEquals(setOf("helper", "sheriff"), bob.townRanks.toSet())
            assertEquals(setOf("helper"), bob.nationRanks.toSet())
            assertEquals(setOf("vip"), env.residentManager.getResidentByName("Cid").get().townRanks.toSet())
            assertEquals(TownRank.MAYOR, env.residentManager.getResidentByName("Ann").get().rank)
        }
    }
}
