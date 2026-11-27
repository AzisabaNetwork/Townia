package net.azisaba.townia

import net.azisaba.townia.data.TownRank
import net.azisaba.townia.migration.TownyFlatfileMigrator
import org.bukkit.Bukkit
import org.bukkit.command.ConsoleCommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Path
import java.util.UUID

/** `/ta migrate only ...`: one town and/or one resident from Towny 0.96 flat files. */
class TownySelectiveMigrationTest {

    private val alphaId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val betaId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val annId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val bobId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002")
    private val caraId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003")

    /** Lays out a small Towny 0.96.1.0 data folder next to Townia's own folder. */
    private fun writeTownyData(pluginsDir: File) {
        val data = File(pluginsDir, "Towny/data")
        fun write(path: String, text: String) {
            val file = File(data, path)
            file.parentFile.mkdirs()
            file.writeText(text.trimIndent() + "\n")
        }
        write(
            "towns/Alpha.txt", """
            name=Alpha
            residents=Ann,Bob
            mayor=Ann
            assistants=Bob
            townBoard=Welcome to Alpha
            tag=ALP
            protectionStatus=residentBuild,residentDestroy,residentSwitch,residentItemUse,allySwitch,pvp,mobs
            bonusBlocks=3
            purchasedBlocks=2
            taxpercent=true
            taxes=5.0
            plotPrice=100.0
            plotTax=7.0
            commercialPlotPrice=250.0
            commercialPlotTax=9.0
            embassyPlotPrice=300.0
            embassyPlotTax=11.0
            open=true
            public=false
            uuid=$alphaId
            registered=1500000000000
            homeBlock=world,0,0
            spawn=world,8.0,64.0,8.0,0.0,90.0
            jailspawns=world,1.0,64.0,1.0,0.0,0.0;
            outlaws=Cara
            """
        )
        write(
            "towns/Beta.txt", """
            name=Beta
            residents=Cara
            mayor=Cara
            protectionStatus=residentBuild
            uuid=$betaId
            registered=1500000000000
            """
        )
        write(
            "residents/Ann.txt", """
            lastOnline=1600000000000
            registered=1500000000000
            town=Alpha
            friends=Bob
            protectionStatus=residentBuild,residentDestroy,outsiderSwitch
            """
        )
        write(
            "residents/Bob.txt", """
            lastOnline=1600000000000
            registered=1500000000000
            town=Alpha
            town-ranks=assistant
            friends=
            protectionStatus=residentBuild
            """
        )
        write(
            "residents/Cara.txt", """
            lastOnline=1600000000000
            registered=1500000000000
            town=Beta
            friends=
            protectionStatus=residentBuild,allyBuild
            """
        )
        // Changed by hand: carries its own permissions and flags.
        write(
            "townblocks/world/0_0_16.data", """
            name=Home
            price=-1.0
            town=Alpha
            type=0
            outpost=false
            permissions=residentBuild,outsiderSwitch,fire
            changed=true
            """
        )
        // Never changed: must follow the town, and is for sale for free.
        write(
            "townblocks/world/1_0_16.data", """
            price=0.0
            town=Alpha
            resident=Bob
            permissions=residentBuild,pvp
            changed=false
            """
        )
        write(
            "townblocks/world/9_9_16.data", """
            price=-1.0
            town=Beta
            resident=Cara
            permissions=residentBuild,explosion
            changed=true
            """
        )
    }

    private fun env(tempDir: Path): TowniaTestEnv {
        writeTownyData(tempDir.toFile())
        val env = TowniaTestEnv(tempDir.resolve("Townia"))
        // The same players, known to the server under their real UUIDs.
        env.residentManager.getOrCreate(annId, "Ann")
        env.residentManager.getOrCreate(bobId, "Bob")
        env.residentManager.getOrCreate(caraId, "Cara")
        return env
    }

    private fun run(env: TowniaTestEnv, town: String?, resident: String?, settingsOnly: Boolean = false) {
        Mockito.mockStatic(Bukkit::class.java).use {
            TownyFlatfileMigrator.migrateOnly(env.plugin, mock<ConsoleCommandSender>(), town, resident, settingsOnly, false)
        }
        env.databaseManager.flush()
    }

    @Test
    fun `one town brings its settings members and land and leaves other towns alone`(@TempDir tempDir: Path) {
        env(tempDir).use { env ->
            run(env, "alpha", null)

            val alpha = env.townManager.getTown(alphaId).orElse(null)
            assertNotNull(alpha)
            assertEquals("ALP", alpha.tag)
            assertEquals(annId, alpha.mayorUuid)
            assertTrue(alpha.hasPvp(), "pvp lives in protectionStatus in Towny 0.96")
            assertTrue(alpha.hasMobs())
            assertFalse(alpha.hasFire())
            assertFalse(alpha.hasExplosions())
            assertEquals("BDSI", alpha.permsResident)
            assertEquals("S", alpha.permsAlly)
            assertEquals("", alpha.permsOutsider)
            assertTrue(alpha.isTaxPercent)
            assertTrue(alpha.isOpen)
            assertFalse(alpha.isPublic)
            assertEquals(100.0, alpha.plotPrice)
            assertEquals(7.0, alpha.plotTax)
            assertEquals(250.0, alpha.shopPrice)
            assertEquals(9.0, alpha.shopTax)
            assertEquals(300.0, alpha.embassyPrice)
            assertEquals(11.0, alpha.embassyTax)
            assertEquals(5, alpha.bonusClaims)
            assertTrue(alpha.hasJail())
            assertEquals(1, alpha.jailCells.size)
            assertTrue(alpha.outlaws.contains(caraId))

            assertEquals(alphaId, env.residentManager.getResident(annId).get().townUuid)
            assertEquals(TownRank.MAYOR, env.residentManager.getResident(annId).get().rank)
            assertEquals(TownRank.ASSISTANT, env.residentManager.getResident(bobId).get().rank)

            val home = env.plotManager.getPlot("world", 0, 0).orElse(null)
            assertNotNull(home)
            assertEquals("Home", home.name)
            assertTrue(home.hasFire())
            assertFalse(home.isForSale)
            assertEquals("S", home.permsOutsider)

            val unchanged = env.plotManager.getPlot("world", 1, 0).orElse(null)
            assertNotNull(unchanged)
            assertEquals(bobId, unchanged.ownerUuid)
            assertTrue(unchanged.isForSale, "price 0 is a free plot, -1 is not for sale")
            assertFalse(unchanged.hasPvp(), "an unchanged plot follows its town")
            assertNull(unchanged.permsOutsider)

            // Beta and its people were not asked for.
            assertTrue(env.townManager.getTown(betaId).isEmpty)
            assertNull(env.residentManager.getResident(caraId).get().townUuid)
            assertTrue(env.plotManager.getPlot("world", 9, 9).isEmpty)
        }
    }

    @Test
    fun `running the same town twice does not duplicate jails`(@TempDir tempDir: Path) {
        env(tempDir).use { env ->
            run(env, "Alpha", null)
            env.townManager.getTown(alphaId).get().balance = 1234.0
            run(env, "Alpha", null)
            val alpha = env.townManager.getTown(alphaId).get()
            assertEquals(1, alpha.jailCells.size)
            assertEquals(1234.0, alpha.balance, "a re-run keeps the bank Townia holds")
            env.reloadManagers()
            val reloaded = env.townManager.getTown(alphaId).get()
            assertEquals(1, reloaded.jailCells.size)
            assertEquals(2, env.plotManager.countPlotsByTown(alphaId))
        }
    }

    @Test
    fun `one resident brings only their own settings`(@TempDir tempDir: Path) {
        env(tempDir).use { env ->
            run(env, null, "Ann")

            val ann = env.residentManager.getResident(annId).get()
            assertEquals("BD", ann.defaultPermsFriend)
            assertEquals("S", ann.defaultPermsOutsider)
            // Her town was not migrated, so she must not point at a town Townia does not have.
            assertTrue(env.townManager.getTown(alphaId).isEmpty)
            assertEquals("", env.residentManager.getResident(bobId).get().defaultPermsFriend)
            assertTrue(env.plotManager.allPlots.isEmpty())
        }
    }

    @Test
    fun `settings-only writes the town record and nothing else`(@TempDir tempDir: Path) {
        env(tempDir).use { env ->
            run(env, "Alpha", null, settingsOnly = true)
            assertEquals("ALP", env.townManager.getTown(alphaId).get().tag)
            assertTrue(env.plotManager.allPlots.isEmpty())
            assertNull(env.residentManager.getResident(bobId).get().townUuid)
        }
    }

    @Test
    fun `a town and a resident from another town can be migrated together`(@TempDir tempDir: Path) {
        env(tempDir).use { env ->
            run(env, "Alpha", "Cara")
            assertTrue(env.townManager.getTown(alphaId).isPresent)
            assertEquals("B", env.residentManager.getResident(caraId).get().defaultPermsFriend)
            assertEquals("B", env.residentManager.getResident(caraId).get().defaultPermsAlly)
            assertTrue(env.townManager.getTown(betaId).isEmpty)
        }
    }
}
