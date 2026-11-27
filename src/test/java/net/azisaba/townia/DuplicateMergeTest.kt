package net.azisaba.townia

import net.azisaba.townia.data.TownRank
import net.azisaba.townia.data.TowniaPlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/**
 * The live server holds two records for thousands of players: one under their real UUID and a
 * second one of the same resident under the offline UUID of their name (same registration time).
 * Merging the two used to give a player without a town the town of the second record, which put
 * everyone who had left a town back into it.
 */
class DuplicateMergeTest {

    private fun offline(name: String) = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
    private val real = UUID.fromString("9a5fe0c6-040e-4048-bf66-295a3b89e0c4")

    private fun record(uuid: UUID, name: String, town: UUID?, rank: TownRank, registered: Long, seen: Long) =
        TowniaPlayer(uuid, name, town, rank, seen, null).also { it.registeredAt = registered }

    private fun town(env: TowniaTestEnv, name: String): UUID {
        val mayor = env.mockPlayer(UUID.randomUUID(), "MayorOf$name")
        env.residentManager.getOrCreate(mayor)
        return env.townManager.createTown(name, mayor.uniqueId).id!!
    }

    private fun named(env: TowniaTestEnv, name: String) = env.residentManager.allResidents.filter { it.name == name }

    @Test
    fun `a copy does not put a player who left back into the town`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val alpha = town(env, "alpha")
            env.residentManager.cacheResident(record(offline("Kai"), "Kai", alpha, TownRank.RESIDENT, registered = 1_000, seen = 2_000))
            env.residentManager.cacheResident(record(real, "Kai", null, TownRank.RESIDENT, registered = 1_000, seen = 5_000))

            val kai = env.residentManager.getOrCreate(real, "Kai")

            assertNull(kai.townUuid, "he left; the older copy must not undo that")
            assertEquals(real, named(env, "Kai").single().uuid, "the copy is gone")
        }
    }

    @Test
    fun `the record used last is the one that counts`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val alpha = town(env, "alpha")
            // He last played under the offline UUID and joined alpha then.
            env.residentManager.cacheResident(record(offline("Kai"), "Kai", alpha, TownRank.ASSISTANT, registered = 1_000, seen = 9_000))
            env.residentManager.cacheResident(record(real, "Kai", null, TownRank.RESIDENT, registered = 1_000, seen = 5_000))

            val kai = env.residentManager.getOrCreate(real, "Kai")

            assertEquals(alpha, kai.townUuid)
            assertEquals(TownRank.ASSISTANT, kai.rank)
            assertEquals(1, named(env, "Kai").size)
        }
    }

    @Test
    fun `a player seen here for the first time still gets the town they had in Towny`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val alpha = town(env, "alpha")
            env.residentManager.cacheResident(record(offline("Kai"), "Kai", alpha, TownRank.RESIDENT, registered = 1_000, seen = 2_000))
            // A record made here (looked up by a command, say) before he ever logged in.
            env.residentManager.cacheResident(record(real, "Kai", null, TownRank.RESIDENT, registered = 7_000, seen = 7_000))

            val kai = env.residentManager.getOrCreate(real, "Kai")

            assertEquals(alpha, kai.townUuid)
            assertEquals(1, named(env, "Kai").size)
        }
    }

    @Test
    fun `a mayor whose town names the copy keeps the town after the merge`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            // The town row still names the copy as its mayor.
            env.residentManager.cacheResident(record(offline("Kai"), "Kai", null, TownRank.RESIDENT, registered = 1_000, seen = 2_000))
            val beta = env.townManager.createTown("beta", offline("Kai")).id!!
            env.residentManager.getResident(offline("Kai")).get().registeredAt = 1_000
            env.residentManager.cacheResident(record(real, "Kai", beta, TownRank.MAYOR, registered = 1_000, seen = 5_000))

            env.residentManager.repairMayors()
            assertEquals(TownRank.MAYOR, env.residentManager.getResident(real).get().rank, "the two records are one person: no demotion")

            env.residentManager.mergeOfflineDuplicates { _, _ -> }
            val kai = named(env, "Kai").single()
            assertEquals(real, kai.uuid)
            assertEquals(beta, kai.townUuid)
            assertEquals(TownRank.MAYOR, kai.rank)
            assertEquals(real, env.townManager.getTown(beta).get().mayorUuid)
        }
    }

    @Test
    fun `a mayor who was wrongly demoted gets the rank back when the copy is merged`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            env.residentManager.cacheResident(record(offline("Kai"), "Kai", null, TownRank.RESIDENT, registered = 1_000, seen = 2_000))
            val beta = env.townManager.createTown("beta", offline("Kai")).id!!
            env.residentManager.getResident(offline("Kai")).get().registeredAt = 1_000
            env.residentManager.cacheResident(record(real, "Kai", beta, TownRank.RESIDENT, registered = 1_000, seen = 5_000))

            val kai = env.residentManager.getOrCreate(real, "Kai")

            assertEquals(TownRank.MAYOR, kai.rank)
            assertEquals(real, env.townManager.getTown(beta).get().mayorUuid)
            assertEquals(1, named(env, "Kai").size)
        }
    }

    @Test
    fun `a second holder of the mayor rank loses it where the town has its mayor`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val alpha = town(env, "alpha")
            val extra = UUID.randomUUID()
            env.residentManager.cacheResident(record(extra, "Extra", alpha, TownRank.MAYOR, registered = 1_000, seen = 2_000))

            env.residentManager.repairMayors()

            assertEquals(TownRank.RESIDENT, env.residentManager.getResident(extra).get().rank)
            assertEquals(TownRank.MAYOR, env.residentManager.getResident(env.townManager.getTown(alpha).get().mayorUuid!!).get().rank)
        }
    }

    @Test
    fun `the only one who can run a town keeps the mayor rank when the town names somebody who is not there`(@TempDir tempDir: Path) {
        TowniaTestEnv(tempDir).use { env ->
            val alpha = town(env, "alpha")
            val beta = town(env, "beta")
            // An import left alpha naming a record nobody has, and beta naming a mayor who lives in alpha.
            val acting = UUID.randomUUID()
            env.residentManager.cacheResident(record(acting, "Acting", alpha, TownRank.MAYOR, registered = 1_000, seen = 2_000))
            env.townManager.getTown(alpha).get().mayorUuid = UUID.randomUUID()
            val moved = UUID.randomUUID()
            env.residentManager.cacheResident(record(moved, "Moved", alpha, TownRank.RESIDENT, registered = 1_000, seen = 2_000))
            val betaActing = UUID.randomUUID()
            env.residentManager.cacheResident(record(betaActing, "BetaActing", beta, TownRank.MAYOR, registered = 1_000, seen = 2_000))
            env.townManager.getTown(beta).get().mayorUuid = moved

            env.residentManager.repairMayors()

            assertEquals(TownRank.MAYOR, env.residentManager.getResident(acting).get().rank, "alpha names nobody known")
            assertEquals(TownRank.MAYOR, env.residentManager.getResident(betaActing).get().rank, "beta names somebody who is not a member")
        }
    }
}
