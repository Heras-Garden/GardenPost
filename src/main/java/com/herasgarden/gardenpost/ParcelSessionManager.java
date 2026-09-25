package com.herasgarden.gardenpost;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenpost.model.MailRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ParcelSessionManager implements Listener {
    private final MailService mail;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public ParcelSessionManager(MailService mail) {
        this.mail = mail;
    }

    public void open(Player sender, MailService.Recipient recipient, String message) {
        cancelExisting(sender);
        Inventory inventory = Bukkit.createInventory(null, 18, Component.text("Garden Parcel", NamedTextColor.DARK_GREEN));
        inventory.setItem(13, button(Material.LIME_WOOL, "Send Parcel", "Postage: " + mail.parcelPostageCost() + " Obols"));
        inventory.setItem(15, button(Material.RED_WOOL, "Cancel", "Return the packed items."));
        inventory.setItem(17, button(Material.PAPER, "Packing", "Put up to 9 item stacks in the top row."));
        sessions.put(sender.getUniqueId(), new Session(inventory, recipient, message));
        sender.openInventory(inventory);
        GardenMessages.send(sender, "Pack up to 9 item stacks in the top row, then click Send Parcel.");
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !event.getView().getTopInventory().equals(session.inventory())) return;

        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            event.setCancelled(true);
            return;
        }

        int raw = event.getRawSlot();
        if (raw >= 9 && raw < 18) {
            event.setCancelled(true);
            if (raw == 13) confirm(player, session);
            else if (raw == 15) player.closeInventory();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !event.getView().getTopInventory().equals(session.inventory())) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= 9 && slot < 18)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !event.getInventory().equals(session.inventory())) return;
        sessions.remove(player.getUniqueId());
        if (!session.sent()) returnItems(player, session.inventory());
    }

    private void confirm(Player player, Session session) {
        List<ItemStack> attachments = attachments(session.inventory());
        if (attachments.isEmpty()) {
            GardenMessages.send(player, "Put at least one item stack in the top row.");
            return;
        }
        try {
            MailRecord record = mail.sendParcel(player, session.recipient(), session.message(), attachments);
            session.sent(true);
            for (int i = 0; i < 9; i++) session.inventory().setItem(i, null);
            GardenMessages.send(player, "Parcel " + record.id().toString().substring(0, 8)
                    + " accepted for " + record.recipientName() + " at " + record.address()
                    + ". Postage: " + mail.parcelPostageCost() + " Obols.");
            player.closeInventory();
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            GardenMessages.send(player, "The parcel could not be saved. Your packed items are still here.");
        }
    }

    private List<ItemStack> attachments(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) items.add(item.clone());
        }
        return items;
    }

    private void cancelExisting(Player player) {
        Session existing = sessions.remove(player.getUniqueId());
        if (existing != null && !existing.sent()) returnItems(player, existing.inventory());
    }

    private void returnItems(Player player, Inventory inventory) {
        for (int i = 0; i < 9; i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            player.getInventory().addItem(item).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            inventory.setItem(i, null);
        }
    }

    private ItemStack button(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.WHITE));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private static final class Session {
        private final Inventory inventory;
        private final MailService.Recipient recipient;
        private final String message;
        private boolean sent;

        private Session(Inventory inventory, MailService.Recipient recipient, String message) {
            this.inventory = inventory;
            this.recipient = recipient;
            this.message = message;
        }
        Inventory inventory() { return inventory; }
        MailService.Recipient recipient() { return recipient; }
        String message() { return message; }
        boolean sent() { return sent; }
        void sent(boolean sent) { this.sent = sent; }
    }
}
