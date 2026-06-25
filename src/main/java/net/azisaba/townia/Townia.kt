package net.azisaba.townia

import net.azisaba.townia.command.*
import net.azisaba.townia.config.TowniaConfig
import net.azisaba.townia.database.DatabaseManager
import net.azisaba.townia.listener.PlayerJoinListener
import net.azisaba.townia.listener.PlayerMoveListener
import net.azisaba.townia.listener.PlotProtectionListener
import net.azisaba.townia.manager.*
import net.milkbowl.vault.economy.Economy
import org.bukkit.command.CommandExecutor
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin

import java.sql.SQLException
import java.util.logging.Level

class  Townia : JavaPlugin() {
    lateinit var towniaConfig: TowniaConfig
        private set
    lateinit var databaseManager: DatabaseManager
        private set
    lateinit var messageManager: MessageManager
        private set
    lateinit var residentManager: ResidentManager
        private set
    lateinit var townManager: TownManager
        private set
    lateinit var nationManager: NationManager
        private set
    lateinit var plotManager: PlotManager
        private set
    var economy: Economy? = null
        private set
    var nextUpkeepTime: Long = 0
        private set

    override fun onEnable() {
        instance = this

        saveDefaultConfig()
        towniaConfig = TowniaConfig(this)

        databaseManager = DatabaseManager(this)
        try {
            databaseManager.initialize()
        } catch (e: SQLException) {
            logger.log(Level.SEVERE, "Failed to initialise the database. Disabling Townia.", e)
            server.pluginManager.disablePlugin(this)
            return
        }

        messageManager = MessageManager(this)

        residentManager = ResidentManager(this, databaseManager)
        townManager = TownManager(this, databaseManager, residentManager)
        nationManager = NationManager(this, databaseManager, townManager)
        plotManager = PlotManager(this, databaseManager, townManager)

        hookVault()

        registerCommands()

        server.pluginManager.registerEvents(PlayerJoinListener(this), this)
        server.pluginManager.registerEvents(PlayerMoveListener(this), this)
        server.pluginManager.registerEvents(PlotProtectionListener(this), this)

        for (p in server.onlinePlayers) {
            residentManager.getOrCreate(p)
        }


        val upkeepTicks = 1728000L
        nextUpkeepTime = System.currentTimeMillis() + upkeepTicks * 50L
        DailyTask(this).runTaskTimer(this, upkeepTicks, upkeepTicks)
        
        ActionBarTask(this).runTaskTimer(this, 40L, 40L)

        logger.info("Townia " + description.version + " enabled.")
    }

    override fun onDisable() {
        if (::databaseManager.isInitialized) {
            databaseManager.close()
        }
        logger.info("Townia disabled.")
    }

    private fun hookVault() {
        if (server.pluginManager.getPlugin("Vault") == null) {
            logger.warning("Vault not found. Economy features (deposit/withdraw/buy plot) will be unavailable.")
            return
        }
        val rsp = server.servicesManager.getRegistration(Economy::class.java)
        if (rsp == null) {
            logger.warning("Vault is present but no economy provider is registered. Economy features disabled.")
            return
        }
        economy = rsp.provider
        logger.info("Hooked into Vault economy: " + economy!!.name)
    }

    private fun registerCommands() {
        val townCmd = TownCommand(this)
        val nationCmd = NationCommand(this)
        val plotCmd = PlotCommand(this)
        val resCmd = ResidentCommand(this)
        val tCmd = TowniaCommand(this)
        val taCmd = TowniaAdminCommand(this)
        val twCmd = TowniaWorldCommand(this)
        val invCmd = InviteCommand(this)
        val tcCmd = TownConfigCommand(this)

        bind("town", townCmd, townCmd)
        bind("nation", nationCmd, nationCmd)
        bind("plot", plotCmd, plotCmd)
        bind("resident", resCmd, resCmd)
        bind("townia", tCmd, tCmd)
        bind("towniaadmin", taCmd, taCmd)
        bind("towniaworld", twCmd, twCmd)
        bind("invite", invCmd, invCmd)
        bind("townconfig", tcCmd, tcCmd)
    }

    private fun bind(name: String, executor: CommandExecutor?, completer: TabCompleter?) {
        val cmd = getCommand(name) ?: throw NullPointerException("Command '$name' not registered in plugin.yml")
        cmd.setExecutor(executor)
        cmd.tabCompleter = completer
    }

    fun hasEconomy(): Boolean {
        return economy != null
    }

    fun resetNextUpkeepTime() {
        nextUpkeepTime = System.currentTimeMillis() + 1728000L * 50L
    }

    companion object {
        lateinit var instance: Townia
            private set
    }
}