package com.redecrystal.economy;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.economy.commands.BalanceCommand;
import com.redecrystal.economy.commands.EconomyAdminCommand;
import com.redecrystal.economy.commands.PayCommand;
import com.redecrystal.economy.gui.BalanceMenu;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RankUP economy plugin. Shows a player's Money/Tokens (GUI-first {@code /saldo}),
 * transfers Money ({@code /pagar}) and lets admins give/set balances
 * ({@code /eco}). All persistence goes through the backend economy API off the
 * main thread. Real Money producers (mining/harvest/kill) arrive in later phases.
 */
public final class CrystalEconomyPlugin extends JavaPlugin {

    private CrystalCore crystal;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());

        BalanceMenu menu = new BalanceMenu(this, crystal);
        getServer().getPluginManager().registerEvents(menu, this);

        getCommand("saldo").setExecutor(new BalanceCommand(menu));
        getCommand("pagar").setExecutor(new PayCommand(this, crystal));
        getCommand("eco").setExecutor(new EconomyAdminCommand(this, crystal));
        getLogger().info("CrystalEconomy enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }
}
