package com.herasgarden.gardenpost;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardenpost.storage.PostSchema;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class GardenPost extends JavaPlugin {
    private GardenPlatform platform;
    private MailService mailService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<GardenPlatform> registration =
                getServer().getServicesManager().getRegistration(GardenPlatform.class);
        if (registration == null || registration.getProvider() == null) {
            getLogger().severe("GardenCore platform service is unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        platform = registration.getProvider();

        RegisteredServiceProvider<PropertyDirectory> propertyRegistration =
                getServer().getServicesManager().getRegistration(PropertyDirectory.class);
        if (propertyRegistration == null || propertyRegistration.getProvider() == null) {
            getLogger().severe("GardenLands property directory service is unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        PropertyDirectory properties = propertyRegistration.getProvider();

        try {
            PostSchema.ensure(platform.storage());
        } catch (SQLException exception) {
            getLogger().severe("GardenPost could not prepare storage: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        long postage = getConfig().getLong("postage.letter-cost", 2L);
        long courierReward = Math.max(0L, getConfig().getLong("courier.base-reward", 3L));
        long timeoutMinutes = Math.max(1L, getConfig().getLong("courier.assignment-timeout-minutes", 30L));
        mailService = new MailService(this, platform, properties, postage, courierReward, timeoutMinutes * 60_000L);

        MailCommand mailCommand = new MailCommand(mailService);
        PluginCommand mail = getCommand("mail");
        if (mail != null) {
            mail.setExecutor(mailCommand);
            mail.setTabCompleter(mailCommand);
        }

        getServer().getPluginManager().registerEvents(new MailDeliveryListener(mailService), this);

        getLogger().info("GardenPost enabled. Postage, courier routes, and physical mailbox delivery are active.");
    }

    public MailService mail() {
        return mailService;
    }
}
