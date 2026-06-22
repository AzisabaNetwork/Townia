package net.azisaba.townia;

import net.azisaba.townia.command.*;
import net.azisaba.townia.config.TowniaConfig;
import net.azisaba.townia.database.DatabaseManager;
import net.azisaba.townia.listener.PlayerJoinListener;
import net.azisaba.townia.listener.PlayerMoveListener;
import net.azisaba.townia.listener.PlotProtectionListener;
import net.azisaba.townia.manager.*;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;

public final class Townia extends JavaPlugin {

    private static Townia instance;

    private TowniaConfig towniaConfig;
    private DatabaseManager databaseManager;
    private MessageManager messageManager;
    private ResidentManager residentManager;
    private TownManager townManager;
    private NationManager nationManager;
    private PlotManager plotManager;
    private Economy economy;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        towniaConfig = new TowniaConfig(this);

        // Database
        databaseManager = new DatabaseManager(this);
        try {
            databaseManager.initialize();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to initialise the database. Disabling Townia.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Messages
        messageManager = new MessageManager(this);

        residentManager = new ResidentManager(this, databaseManager);
        townManager     = new TownManager(this, databaseManager, residentManager);
        nationManager   = new NationManager(this, databaseManager, townManager);
        plotManager     = new PlotManager(this, databaseManager, townManager);

        hookVault();

        registerCommands();

        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(
                new PlotProtectionListener(this), this);

        new net.azisaba.townia.manager.ActionBarTask(this).runTaskTimer(this, 40L, 40L);

        new net.azisaba.townia.manager.DailyTask(this).runTaskTimer(this, 1728000L, 1728000L);

        getLogger().info("Townia " + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("Townia disabled.");
    }

    private void hookVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found. Economy features (deposit/withdraw/buy plot) will be unavailable.");
            return;
        }
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("Vault is present but no economy provider is registered. Economy features disabled.");
            return;
        }
        economy = rsp.getProvider();
        getLogger().info("Hooked into Vault economy: " + economy.getName());
    }

    private void registerCommands() {
        TownCommand townCmd     = new TownCommand(this);
        NationCommand nationCmd = new NationCommand(this);
        PlotCommand plotCmd     = new PlotCommand(this);
        ResidentCommand resCmd  = new ResidentCommand(this);
        TowniaCommand tCmd      = new TowniaCommand(this);
        TowniaAdminCommand taCmd= new TowniaAdminCommand(this);
        TowniaWorldCommand twCmd= new TowniaWorldCommand(this);
        InviteCommand invCmd    = new InviteCommand(this);

        bind("town",        townCmd,   townCmd);
        bind("nation",      nationCmd, nationCmd);
        bind("plot",        plotCmd,   plotCmd);
        bind("resident",    resCmd,    resCmd);
        bind("townia",      tCmd,      tCmd);
        bind("towniaadmin", taCmd,     taCmd);
        bind("towniaworld", twCmd,     twCmd);
        bind("invite",      invCmd,    invCmd);
    }

    private void bind(String name,
                      org.bukkit.command.CommandExecutor executor,
                      org.bukkit.command.TabCompleter completer) {
        var cmd = Objects.requireNonNull(getCommand(name), "Command '" + name + "' not registered in plugin.yml");
        cmd.setExecutor(executor);
        cmd.setTabCompleter(completer);
    }

    public static Townia getInstance() {
        return instance;
    }

    public TowniaConfig getTowniaConfig()       { return towniaConfig; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public MessageManager getMessageManager()   { return messageManager; }
    public ResidentManager getResidentManager() { return residentManager; }
    public TownManager getTownManager()         { return townManager; }
    public NationManager getNationManager()     { return nationManager; }
    public PlotManager getPlotManager()         { return plotManager; }

    public Economy getEconomy()   { return economy; }
    public boolean hasEconomy()   { return economy != null; }
}
