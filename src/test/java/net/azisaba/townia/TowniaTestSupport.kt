package net.azisaba.townia

import net.azisaba.townia.config.TowniaConfig
import net.azisaba.townia.database.DatabaseManager
import net.azisaba.townia.manager.MessageManager
import net.azisaba.townia.manager.NameHistory
import net.azisaba.townia.manager.NationManager
import net.azisaba.townia.manager.PlotManager
import net.azisaba.townia.manager.ResidentManager
import net.azisaba.townia.manager.TownManager
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

data class SentMessage(val recipient: CommandSender, val key: String, val replacements: List<String>)

class TowniaTestEnv(tempDir: Path) : AutoCloseable {
    val dataFolder: File = tempDir.toFile()
    val config: YamlConfiguration = YamlConfiguration.loadConfiguration(File("src/main/resources/config.yml"))
    val sentMessages = mutableListOf<SentMessage>()

    val server: Server = mock()
    val scheduler: BukkitScheduler = mock()
    val plugin: Townia = mock()
    val towniaConfig: TowniaConfig
    val databaseManager: DatabaseManager
    val messageManager: MessageManager = Mockito.mock(MessageManager::class.java) { inv ->
        when (inv.method.name) {
            "sendMessage" -> {
                val recipient = inv.arguments[0] as CommandSender
                val key = inv.arguments[1] as String
                val raw = inv.arguments.drop(2)
                val varargs = if (raw.size == 1 && raw[0] is Array<*>) {
                    (raw[0] as Array<*>).map { it?.toString() ?: "" }
                } else {
                    raw.map { it?.toString() ?: "" }
                }
                sentMessages.add(SentMessage(recipient, key, varargs))
                null
            }
            "getRawMessage", "getPlainMessage" -> inv.arguments[1] as String
            else -> Mockito.RETURNS_DEFAULTS.answer(inv)
        }
    }
    val nameHistory: NameHistory = mock()
    val residentManager: ResidentManager
    var townManager: TownManager
    var nationManager: NationManager
    var plotManager: PlotManager

    val world: World = mock()
    val worldSpawn: Location

    init {
        config["database.type"] = "sqlite"
        config["allowed-worlds"] = listOf("world")
        worldSpawn = Location(world, 0.0, 64.0, 0.0)
        whenever(world.name).thenReturn("world")
        whenever(world.spawnLocation).thenReturn(worldSpawn)

        whenever(server.scheduler).thenReturn(scheduler)
        whenever(server.logger).thenReturn(Logger.getLogger("TowniaTest"))
        whenever(server.getWorld("world")).thenReturn(world)
        whenever(scheduler.runTaskAsynchronously(any<Plugin>(), any<Runnable>())).doAnswer { inv ->
            inv.getArgument<Runnable>(1).run()
            mock<BukkitTask>()
        }

        whenever(plugin.dataFolder).thenReturn(dataFolder)
        whenever(plugin.config).thenReturn(config)
        whenever(plugin.logger).thenReturn(Logger.getLogger("TowniaTest"))
        whenever(plugin.server).thenReturn(server)

        Townia::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, plugin)
        }

        towniaConfig = TowniaConfig(plugin)
        whenever(plugin.towniaConfig).thenReturn(towniaConfig)

        databaseManager = DatabaseManager(plugin)
        databaseManager.initialize()
        whenever(plugin.databaseManager).thenReturn(databaseManager)

        // Captured via defaultAnswer on messageManager
        whenever(plugin.messageManager).thenReturn(messageManager)

        whenever(nameHistory.namesForUuid(any())).thenReturn(emptySet<String>())
        whenever(plugin.nameHistory).thenReturn(nameHistory)

        residentManager = ResidentManager(plugin, databaseManager)
        whenever(plugin.residentManager).thenReturn(residentManager)

        townManager = TownManager(plugin, databaseManager, residentManager)
        whenever(plugin.townManager).thenReturn(townManager)

        nationManager = NationManager(plugin, databaseManager, townManager)
        whenever(plugin.nationManager).thenReturn(nationManager)

        plotManager = PlotManager(plugin, databaseManager, townManager)
        whenever(plugin.plotManager).thenReturn(plotManager)
    }

    fun reloadManagers() {
        townManager = TownManager(plugin, databaseManager, residentManager)
        whenever(plugin.townManager).thenReturn(townManager)
        nationManager = NationManager(plugin, databaseManager, townManager)
        whenever(plugin.nationManager).thenReturn(nationManager)
        plotManager = PlotManager(plugin, databaseManager, townManager)
        whenever(plugin.plotManager).thenReturn(plotManager)
    }

    fun mockPlayer(uuid: UUID, name: String, location: Location = Location(world, 0.0, 64.0, 0.0)): Player {
        val player = mock<Player>()
        whenever(player.uniqueId).thenReturn(uuid)
        whenever(player.name).thenReturn(name)
        whenever(player.location).thenReturn(location)
        whenever(player.world).thenReturn(location.world)
        whenever(player.hasPermission(any<String>())).thenReturn(false)
        whenever(player.teleport(any<Location>())).thenReturn(true)
        return player
    }

    fun claimChunk(x: Int, z: Int, townId: UUID) {
        val chunk = mock<Chunk>()
        whenever(chunk.world).thenReturn(world)
        whenever(chunk.x).thenReturn(x)
        whenever(chunk.z).thenReturn(z)
        plotManager.claimChunk(townId, chunk)
    }

    fun dummyCommand(name: String): Command = mock<Command>().also { whenever(it.name).thenReturn(name) }

    override fun close() {
        databaseManager.close()
        Mockito.framework().clearInlineMocks()
    }
}
