package net.azisaba.townia

import net.azisaba.townia.api.TowniaAPI
import net.azisaba.townia.api.TowniaApiImpl
import net.azisaba.townia.command.*
import net.azisaba.townia.config.TowniaConfig
import net.azisaba.townia.database.DatabaseManager
import net.azisaba.townia.listener.*
import net.azisaba.townia.manager.*
import net.milkbowl.vault.economy.Economy
import org.bukkit.command.CommandExecutor
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.ServicePriority
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
    lateinit var nameHistory: NameHistory
        private set
    lateinit var townManager: TownManager
        private set
    lateinit var nationManager: NationManager
        private set
    lateinit var plotManager: PlotManager
        private set
    lateinit var townyPerms: net.azisaba.townia.permission.TownyPerms
        private set
    lateinit var townTimers: net.azisaba.townia.manager.TownTimers
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

        // Before residents are cached, so nobody stays "in" a town that no longer exists.
        try {
            val (members, jailed) = databaseManager.clearMissingTownReferences()
            if (members > 0 || jailed > 0) {
                logger.warning("Cleared references to missing towns: $members town membership(s), $jailed jail entr(y/ies).")
            }
        } catch (e: SQLException) {
            logger.log(Level.WARNING, "Could not clear references to missing towns", e)
        }

        nameHistory = NameHistory(this)
        residentManager = ResidentManager(this, databaseManager)
        townManager = TownManager(this, databaseManager, residentManager)
        nationManager = NationManager(this, databaseManager, townManager)
        plotManager = PlotManager(this, databaseManager, townManager)
        townyPerms = net.azisaba.townia.permission.TownyPerms(this)
        residentManager.repairMayors().takeIf { it > 0 }?.let { logger.info("Repaired $it mayor record(s).") }
        plotManager.allPlots.count { townManager.getTown(it.townUuid).isEmpty }.takeIf { it > 0 }?.let {
            logger.warning(
                "$it claimed chunk(s) belong to a town that no longer exists. They are not protected and cannot be " +
                    "claimed; stand in one and use /towniaadmin forceunclaim to free it."
            )
        }
        server.servicesManager.register(TowniaAPI::class.java, TowniaApiImpl(this), this, ServicePriority.Normal)

        hookVault()

        registerCommands()

        server.pluginManager.registerEvents(PlayerJoinListener(this), this)
        server.pluginManager.registerEvents(PlayerMoveListener(this), this)
        val plotProtectionListener = PlotProtectionListener(this)
        server.pluginManager.registerEvents(plotProtectionListener, this)
        net.azisaba.townia.hook.ShopChestHook.register(this, plotProtectionListener)
        server.pluginManager.registerEvents(JailCommandListener(this), this)
        server.pluginManager.registerEvents(TownMenuListener(this), this)
        server.pluginManager.registerEvents(net.azisaba.townia.listener.DeathCostListener(this), this)
        townTimers = net.azisaba.townia.manager.TownTimers(this)
        townTimers.restart()

        for (p in server.onlinePlayers) {
            residentManager.getOrCreate(p)
            townyPerms.refresh(p)
        }


        val newDayTime = towniaConfig.rules.newDayTime
        if (newDayTime != null) {
            scheduleNewDay(newDayTime)
        } else {
            val upkeepTicks = 1728000L
            nextUpkeepTime = System.currentTimeMillis() + upkeepTicks * 50L
            DailyTask(this).runTaskTimer(this, upkeepTicks, upkeepTicks)
        }
        
        ActionBarTask(this).runTaskTimer(this, 40L, 40L)

        logger.info("Townia " + pluginMeta.version + " enabled.")
    }

    override fun onDisable() {
        if (::townyPerms.isInitialized) server.onlinePlayers.forEach(townyPerms::forget)
        if (::townTimers.isInitialized) townTimers.stop()
        server.servicesManager.unregisterAll(this)
        if (::databaseManager.isInitialized) {
            databaseManager.close()
        }
        logger.info("Townia disabled.")
    }

    /**
     * Towny's new_day_time: taxes and upkeep are collected at a fixed time of day, so a server
     * that restarts daily still gets its new day (a timer counted from start-up never would).
     */
    private fun scheduleNewDay(time: java.time.LocalTime, after: java.time.ZonedDateTime = java.time.ZonedDateTime.now()) {
        val next = DailyTask.nextNewDay(time, after)
        nextUpkeepTime = next.toInstant().toEpochMilli()
        val ticks = (java.time.Duration.between(java.time.ZonedDateTime.now(), next).toMillis() / 50L).coerceAtLeast(20L)
        server.scheduler.runTaskLater(this, Runnable {
            // Ticks run slow on a lagging server; never start the day early.
            if (System.currentTimeMillis() + 1000L < nextUpkeepTime) {
                scheduleNewDay(time, after)
                return@Runnable
            }
            DailyTask(this).run()
            // The day after the one just run, counted from that day's start. The task may fire a
            // moment early; counted from "now", today's start was then still ahead, and the same
            // day ran a second time one second later (taxes and upkeep taken twice).
            scheduleNewDay(time, next)
        }, ticks)
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
        if (towniaConfig.rules.newDayTime != null) return
        nextUpkeepTime = System.currentTimeMillis() + 1728000L * 50L
    }

    companion object {
        lateinit var instance: Townia
            private set
    }
}
