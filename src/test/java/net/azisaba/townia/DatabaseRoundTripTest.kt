package net.azisaba.townia

import net.azisaba.townia.data.BankTransaction
import net.azisaba.townia.data.Invite
import net.azisaba.townia.data.Nation
import net.azisaba.townia.data.Plot
import net.azisaba.townia.data.PlotType
import net.azisaba.townia.data.Town
import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaJailCell
import net.azisaba.townia.data.TowniaOutpost
import net.azisaba.townia.data.TowniaPlayer
import net.azisaba.townia.data.TransactionType
import net.azisaba.townia.database.DatabaseManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/**
 * Everything the database keeps is written, read back by a database opened afresh (so nothing
 * comes from a cache), and deleted again - with the background writer on and off.
 */
class DatabaseRoundTripTest {

    private fun town(id: UUID, name: String, mayor: UUID, nation: UUID? = null) = Town(
        id, name, mayor, nation, 123.5, 10, 2, true, 1_000L, "board of $name", 4.0, 7.0,
        true, false, true, false, "world", 1.0, 64.0, 2.0, 90f, 0f
    )

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `everything written comes back after a restart, and deleting cleans up`(asyncWrites: Boolean, @TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.config["database.async-writes"] = asyncWrites
            env.databaseManager.close()
            fun open() = DatabaseManager(env.plugin).also { it.initialize() }

            val alphaId = UUID.randomUUID()
            val betaId = UUID.randomUUID()
            val nationId = UUID.randomUUID()
            val mayor = UUID.randomUUID()
            val member = UUID.randomUUID()
            val friend = UUID.randomUUID()
            val outlaw = UUID.randomUUID()

            var db = open()
            val alpha = town(alphaId, "アルファ町", mayor)
            alpha.tag = "ALP"
            alpha.isTaxPercent = true
            alpha.plotTax = 1.5
            alpha.shopPrice = 2.5
            alpha.shopTax = 3.5
            alpha.embassyPrice = 4.5
            alpha.embassyTax = 5.5
            alpha.spawnCost = 6.5
            db.saveTown(alpha)
            db.saveTown(town(betaId, "beta", outlaw))
            for ((uuid, name, townId, rank) in listOf(
                Quad(mayor, "Mayor", alphaId, TownRank.MAYOR), Quad(member, "Member", alphaId, TownRank.ASSISTANT),
                Quad(friend, "Friend", null, TownRank.RESIDENT), Quad(outlaw, "Outlaw", betaId, TownRank.MAYOR)
            )) {
                val p = TowniaPlayer(uuid, name, townId, rank, 5_000L, "ja")
                if (uuid == member) {
                    p.townRanks.add("sheriff")
                    p.nationRanks.add("chancellor")
                    p.about = "こんにちは"
                }
                db.saveResident(p)
            }
            db.addFriend(member, friend)
            db.addTownOutlaw(alphaId, outlaw)
            db.saveTownOutpost(alphaId, TowniaOutpost(0, "world", 10.0, 70.0, 10.0, 0f, 0f, true, "北"))
            db.saveTownJailCell(alphaId, TowniaJailCell(0, "cell", "world", 1.0, 60.0, 1.0, 0f, 0f))
            db.savePlot(Plot("world", 0, 0, alphaId, member, PlotType.SHOP, true, 99.0, "店", true, false, false, true, false,
                "B", "S", "", "I", "BD"))
            db.savePlot(Plot("world", 1, 0, alphaId, null, PlotType.DEFAULT, false, 0.0, null, false, false, false, false))
            val nation = Nation(nationId, "北の国", alphaId, mayor, 50.0, "nation board", 2.0)
            nation.tag = "NOR"
            nation.isPublic = true
            nation.isOpen = true
            nation.spawnCost = 8.0
            nation.titles[member] = "将軍"
            db.saveNation(nation)
            db.saveNationTitles(nation)
            alpha.nationUuid = nationId
            db.saveTown(alpha)
            db.addInvite(Invite(0, friend, alphaId, mayor, System.currentTimeMillis()))
            db.addInvite(Invite(0, member, betaId, outlaw, 1L))   // long expired
            db.recordBankTransaction("TOWN", alphaId, mayor, 25.0, "deposit", 148.5)
            db.recordBankTransaction("TOWN", alphaId, mayor, -10.0, "withdraw", 138.5)
            db.flush()
            db.close()

            db = open()
            val t = db.getTown(alphaId).get()
            assertEquals(listOf("アルファ町", "ALP", true, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5, nationId),
                listOf(t.name, t.tag, t.isTaxPercent, t.plotTax, t.shopPrice, t.shopTax, t.embassyPrice, t.embassyTax, t.spawnCost, t.nationUuid))
            assertEquals(alphaId, db.getTownByName("アルファ町").get().id)
            db.loadTownOutposts(t)
            assertEquals("北", t.outposts.single()!!.name)
            db.loadTownOutlaws(t)
            assertEquals(setOf(outlaw), t.outlaws.toSet())
            db.loadTownJailCells(t)
            assertEquals("cell", t.jailCells.single()!!.name)

            val m = db.getResident(member).get()
            assertEquals(listOf(alphaId, TownRank.ASSISTANT, setOf("sheriff"), setOf("chancellor"), "こんにちは"),
                listOf(m.townUuid, m.rank, m.townRanks, m.nationRanks, m.about))
            assertEquals(member, db.getResidentByName("member").get().uuid)
            assertEquals(setOf(mayor, member), db.getResidentsByTown(alphaId).map { it!!.uuid }.toSet())
            assertEquals(listOf(friend.toString()), db.getFriends(member))

            val shop = db.getPlot("world", 0, 0).get()
            assertEquals(listOf(member, PlotType.SHOP, true, 99.0, "店", true, true, "B", "S", "", "I", "BD"),
                listOf(shop.ownerUuid, shop.plotType, shop.isForSale, shop.price, shop.name, shop.hasPvp(), shop.hasFire(),
                    shop.permsResident, shop.permsAlly, shop.permsOutsider, shop.permsNation, shop.permsFriend))
            assertEquals(2, db.getPlotsByTown(alphaId).size)
            assertEquals(2, db.countPlotsByTown(alphaId))

            val n = db.getNation(nationId).get()
            assertEquals(listOf("北の国", "NOR", true, true, 8.0, alphaId), listOf(n.name, n.tag, n.isPublic, n.isOpen, n.spawnCost, n.capitalTownUuid))
            assertEquals(nationId, db.getNationByName("北の国").get().id)
            db.loadNationTitles(n)
            assertEquals("将軍", n.titles[member])

            assertEquals(listOf(friend), db.getInvitesByTown(alphaId).map { it.targetUuid })
            assertTrue(db.getInvite(friend, alphaId).isPresent)
            assertEquals(1, db.getInvitesByTarget(member).size)
            db.purgeExpiredInvites(60)
            db.flush()
            assertTrue(db.getInvitesByTarget(member).isEmpty(), "the expired invite is purged")

            val history = db.getBankHistory(alphaId, 10, 0)
            assertEquals(2, db.countBankHistory(alphaId))
            assertEquals(setOf(TransactionType.DEPOSIT, TransactionType.WITHDRAW), history.map { it.type }.toSet())
            assertEquals(2, db.getBankTransactions("TOWN", alphaId, 10).size)

            // Changing and removing.
            db.removeFriend(member, friend)
            db.removeTownOutlaw(alphaId, outlaw)
            db.deleteTownOutpost(t.outposts.single()!!.id)
            db.deleteTownJailCell(t.jailCells.single()!!.id)
            db.deletePlot("world", 1, 0)
            db.deleteInvitesByTarget(friend)
            db.flush()
            db.close()

            db = open()
            val t2 = db.getTown(alphaId).get()
            db.loadTownOutposts(t2)
            db.loadTownOutlaws(t2)
            db.loadTownJailCells(t2)
            assertTrue(t2.outposts.filterNotNull().isEmpty() && t2.outlaws.isEmpty() && t2.jailCells.filterNotNull().isEmpty())
            assertTrue(db.getFriends(member).isEmpty())
            assertFalse(db.getPlot("world", 1, 0).isPresent)
            assertFalse(db.getInvite(friend, alphaId).isPresent)

            // A deleted town takes its plots, outposts, outlaws, cells and invites with it.
            db.addTownOutlaw(alphaId, outlaw)
            db.addInvite(Invite(0, friend, alphaId, mayor, System.currentTimeMillis()))
            db.saveTown(alpha.also { it.nationUuid = null })
            db.deleteNation(nationId)
            db.deleteTown(alphaId)
            db.flush()
            db.close()

            db = open()
            assertFalse(db.getTown(alphaId).isPresent)
            assertFalse(db.getNation(nationId).isPresent)
            assertEquals(0, db.countPlotsByTown(alphaId))
            assertTrue(db.getInvitesByTown(alphaId).isEmpty())
            assertTrue(db.getTown(betaId).isPresent, "the other town stays")
            val cleared = db.clearMissingTownReferences()
            db.flush()
            assertNull(db.getResident(member).get().townUuid, "residents of a deleted town are cleared (${cleared})")
            assertEquals(betaId, db.getResident(outlaw).get().townUuid)
            db.close()
        }
    }

    private data class Quad(val a: UUID, val b: String, val c: UUID?, val d: TownRank)
}
