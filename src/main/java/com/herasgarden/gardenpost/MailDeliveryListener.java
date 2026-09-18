package com.herasgarden.gardenpost;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenpost.model.MailRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public final class MailDeliveryListener implements Listener {
    private final MailService mail;

    public MailDeliveryListener(MailService mail) {
        this.mail = mail;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<UUID> routeId = mail.routeId(hand);
        if (routeId.isEmpty()) {
            return;
        }

        event.setCancelled(true);
        try {
            Optional<MailRecord> record = mail.find(routeId.get());
            if (record.isEmpty()) {
                send(player, "That mail route no longer exists.");
                return;
            }

            MailService.DeliveryResult result = mail.deliver(player, event.getClickedBlock(), record.get());
            send(player, result.message());
            if (result.success()) {
                if (hand.getAmount() <= 1) {
                    player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                } else {
                    hand.setAmount(hand.getAmount() - 1);
                }
            }
        } catch (SQLException exception) {
            send(player, "The delivery could not be saved right now.");
        }
    }

    private void send(Player player, String message) {
        GardenMessages.send(player, message);
    }
}
