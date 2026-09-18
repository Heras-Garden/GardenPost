package com.herasgarden.gardenpost;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.integration.IntegrationEventType;
import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardencore.api.land.PropertyMailbox;
import com.herasgarden.gardencore.api.order.GardenOrder;
import com.herasgarden.gardencore.api.order.OrderState;
import com.herasgarden.gardencore.api.order.OrderType;
import com.herasgarden.gardenpost.model.MailRecord;
import com.herasgarden.gardenpost.model.MailStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MailService {
    private final JavaPlugin plugin;
    private final GardenPlatform platform;
    private final PropertyDirectory properties;
    private final long postageCost;
    private final long courierReward;
    private final long assignmentTimeoutMillis;
    private final NamespacedKey routeKey;
    private final NamespacedKey deliveredKey;

    public MailService(JavaPlugin plugin, GardenPlatform platform, PropertyDirectory properties,
                       long postageCost, long courierReward, long assignmentTimeoutMillis) {
        this.plugin = plugin;
        this.platform = platform;
        this.properties = properties;
        this.postageCost = Math.max(0L, postageCost);
        this.courierReward = Math.max(0L, courierReward);
        this.assignmentTimeoutMillis = Math.max(60_000L, assignmentTimeoutMillis);
        this.routeKey = new NamespacedKey(plugin, "mail-route-id");
        this.deliveredKey = new NamespacedKey(plugin, "delivered-mail-id");
    }

    public long postageCost() {
        return postageCost;
    }

    public Optional<Recipient> resolveRecipient(String name) throws SQLException {
        if (name == null || name.isBlank()) return Optional.empty();

        Player online = Bukkit.getPlayerExact(name);
        OfflinePlayer player = online;
        if (player == null) {
            player = java.util.Arrays.stream(Bukkit.getOfflinePlayers())
                    .filter(candidate -> candidate.getName() != null && candidate.getName().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
        }
        if (player == null || player.getName() == null) return Optional.empty();

        List<Mailbox> mailboxes = mailboxesFor(player.getUniqueId());
        if (mailboxes.isEmpty()) {
            throw new IllegalArgumentException(player.getName() + " does not have a registered Garden mailbox.");
        }
        Mailbox selected = mailboxes.getFirst();
        if (mailboxes.size() > 1) {
            Optional<Mailbox> primary = primaryMailbox(player.getUniqueId());
            if (primary.isEmpty()) {
                throw new IllegalArgumentException(player.getName()
                        + " owns more than one mailbox and needs to choose a primary mailbox.");
            }
            selected = primary.get();
        }

        return Optional.of(new Recipient(player.getUniqueId(), player.getName(), selected));
    }

    public Mailbox setPrimaryMailbox(Player player, Block block) throws SQLException {
        Mailbox mailbox = mailboxAt(block).orElseThrow(() ->
                new IllegalArgumentException("Look directly at one of your registered mailbox chests."));
        if (!ownsMailbox(player.getUniqueId(), mailbox.propertyId())) {
            throw new IllegalArgumentException("That registered mailbox does not belong to one of your properties.");
        }

        long now = System.currentTimeMillis();
        try (Connection connection = platform.storage().connection()) {
            int changed;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE gp_primary_mailboxes SET property_uuid = ?, updated_at = ? WHERE player_uuid = ?")) {
                update.setString(1, mailbox.propertyId().toString());
                update.setLong(2, now);
                update.setString(3, player.getUniqueId().toString());
                changed = update.executeUpdate();
            }
            if (changed == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO gp_primary_mailboxes (player_uuid, property_uuid, updated_at) VALUES (?, ?, ?)")) {
                    insert.setString(1, player.getUniqueId().toString());
                    insert.setString(2, mailbox.propertyId().toString());
                    insert.setLong(3, now);
                    insert.executeUpdate();
                }
            }
        }
        return mailbox;
    }

    public MailRecord send(Player sender, Recipient recipient, String message) throws SQLException {
        String cleanMessage = cleanMessage(message);
        if (cleanMessage.isBlank()) {
            throw new IllegalArgumentException("A letter needs a message.");
        }
        if (recipient.playerId().equals(sender.getUniqueId())) {
            throw new IllegalArgumentException("You cannot mail a letter to yourself.");
        }

        UUID mailId = UUID.randomUUID();
        GardenOrder order = platform.orders().create(
                OrderType.POSTAGE,
                sender.getUniqueId(),
                "SERVER",
                "garden-post",
                postageCost,
                "gardenpost.mail",
                mailId.toString(),
                "{\"recipientUuid\":\"" + recipient.playerId() + "\"}"
        );
        platform.orders().transition(order.id(), OrderState.READY, "Letter prepared");
        platform.orders().transition(order.id(), OrderState.AWAITING_CONFIRMATION, "Send command confirms postage");
        platform.orders().transition(order.id(), OrderState.PAYMENT_PENDING, "Collecting postage");

        if (!platform.currency().withdraw(sender.getUniqueId(), postageCost)) {
            platform.orders().transition(order.id(), OrderState.PAYMENT_FAILED, "Insufficient Obols for postage");
            throw new IllegalArgumentException("You need " + platform.currency().symbol() + " "
                    + postageCost + " for postage.");
        }

        platform.orders().transition(order.id(), OrderState.PAID, "Postage paid");
        platform.orders().transition(order.id(), OrderState.FULFILLING, "Waiting for courier delivery");

        long now = System.currentTimeMillis();
        Mailbox mailbox = recipient.mailbox();
        MailRecord record = new MailRecord(
                mailId, order.id(), sender.getUniqueId(), sender.getName(),
                recipient.playerId(), recipient.playerName(), mailbox.propertyId(),
                mailbox.worldId(), mailbox.worldName(), mailbox.x(), mailbox.y(), mailbox.z(),
                mailbox.address(), cleanMessage, MailStatus.PENDING, null, now, null, null
        );

        try {
            insert(record);
        } catch (SQLException exception) {
            platform.currency().deposit(sender.getUniqueId(), postageCost);
            try {
                platform.orders().transition(order.id(), OrderState.REFUNDED,
                        "Mail creation failed and postage was returned");
            } catch (Exception ignored) {
            }
            throw exception;
        }

        return record;
    }

    public Optional<MailRecord> claimRoute(Player mailman) throws SQLException {
        releaseStaleAssignments();

        List<MailRecord> existing = assignedTo(mailman.getUniqueId());
        if (!existing.isEmpty()) {
            return Optional.of(existing.getFirst());
        }

        UUID candidate = null;
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT mail_uuid FROM gp_mail WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 1");
             ResultSet result = statement.executeQuery()) {
            if (result.next()) candidate = UUID.fromString(result.getString("mail_uuid"));
        }
        if (candidate == null) return Optional.empty();

        long now = System.currentTimeMillis();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gp_mail SET status = 'ASSIGNED', assigned_mailman_uuid = ?, assigned_at = ? "
                             + "WHERE mail_uuid = ? AND status = 'PENDING'")) {
            statement.setString(1, mailman.getUniqueId().toString());
            statement.setLong(2, now);
            statement.setString(3, candidate.toString());
            if (statement.executeUpdate() != 1) return Optional.empty();
        }

        return find(candidate);
    }

    public List<MailRecord> assignedTo(UUID mailmanId) throws SQLException {
        List<MailRecord> records = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gp_mail WHERE assigned_mailman_uuid = ? AND status = 'ASSIGNED' "
                             + "ORDER BY assigned_at ASC")) {
            statement.setString(1, mailmanId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) records.add(read(result));
            }
        }
        return List.copyOf(records);
    }

    public List<MailRecord> sentBy(UUID senderId, int limit) throws SQLException {
        List<MailRecord> records = new ArrayList<>();
        int safe = Math.max(1, Math.min(limit, 25));
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gp_mail WHERE sender_uuid = ? ORDER BY created_at DESC LIMIT " + safe)) {
            statement.setString(1, senderId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) records.add(read(result));
            }
        }
        return List.copyOf(records);
    }

    public Optional<MailRecord> find(UUID mailId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gp_mail WHERE mail_uuid = ?")) {
            statement.setString(1, mailId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    public ItemStack routeItem(MailRecord record) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Garden Mail: " + record.recipientName(), NamedTextColor.WHITE));
        meta.lore(List.of(
                Component.text(record.address(), NamedTextColor.GRAY),
                Component.text("Deliver to the registered mailbox.", NamedTextColor.GRAY),
                Component.text("Mail " + record.id().toString().substring(0, 8), NamedTextColor.DARK_GRAY)
        ));
        meta.getPersistentDataContainer().set(routeKey, PersistentDataType.STRING, record.id().toString());
        item.setItemMeta(meta);
        return item;
    }

    public Optional<UUID> routeId(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return Optional.empty();
        String raw = item.getItemMeta().getPersistentDataContainer().get(routeKey, PersistentDataType.STRING);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public DeliveryResult deliver(Player mailman, Block clicked, MailRecord record) throws SQLException {
        if (record.status() != MailStatus.ASSIGNED) {
            return DeliveryResult.failure("That route is no longer assigned.");
        }
        if (!mailman.hasPermission("gardenpost.admin")
                && (record.assignedMailman() == null || !record.assignedMailman().equals(mailman.getUniqueId()))) {
            return DeliveryResult.failure("That letter is assigned to another mailman.");
        }
        if (!clicked.getWorld().getUID().equals(record.mailboxWorldId())
                || clicked.getX() != record.mailboxX()
                || clicked.getY() != record.mailboxY()
                || clicked.getZ() != record.mailboxZ()) {
            return DeliveryResult.failure("That is not the destination mailbox.");
        }
        if (!(clicked.getState() instanceof Container container)) {
            return DeliveryResult.failure("The destination mailbox is missing.");
        }

        ItemStack letter = deliveredLetter(record);
        Map<Integer, ItemStack> leftovers = container.getInventory().addItem(letter);
        if (!leftovers.isEmpty()) {
            return DeliveryResult.failure("That mailbox is full.");
        }

        long now = System.currentTimeMillis();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gp_mail SET status = 'DELIVERED', delivered_at = ? "
                             + "WHERE mail_uuid = ? AND status = 'ASSIGNED'")) {
            statement.setLong(1, now);
            statement.setString(2, record.id().toString());
            if (statement.executeUpdate() != 1) {
                container.getInventory().removeItem(letter);
                return DeliveryResult.failure("The delivery changed before it could be completed.");
            }
        }

        boolean rewardPaid = false;
        boolean assignedCourier = record.assignedMailman() != null
                && record.assignedMailman().equals(mailman.getUniqueId());
        if (assignedCourier && courierReward > 0) {
            try {
                rewardPaid = payCourierReward(record.id(), mailman);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Mail " + record.id()
                        + " was delivered, but the courier reward could not be recorded: " + exception.getMessage());
            }
        }

        try {
            String detail = "Letter delivered to mailbox";
            if (rewardPaid) {
                detail += "; courier reward " + platform.currency().symbol() + " " + courierReward
                        + " paid to " + mailman.getName();
            } else if (assignedCourier && courierReward > 0) {
                detail += "; courier reward was not paid";
            } else if (!assignedCourier) {
                detail += "; completed by administrative bypass";
            }
            platform.orders().transition(record.orderId(), OrderState.COMPLETED, detail);
        } catch (Exception exception) {
            plugin.getLogger().warning("Mail " + record.id()
                    + " delivered, but its order journal could not be completed: " + exception.getMessage());
        }

        try {
            platform.integrations().publish(
                    IntegrationEventType.MAIL_DELIVERED,
                    "mail",
                    record.id().toString(),
                    "{\"sender\":\"" + json(record.senderName()) + "\""
                            + ",\"recipient\":\"" + json(record.recipientName()) + "\""
                            + ",\"address\":\"" + json(record.address()) + "\""
                            + ",\"mailman\":\"" + json(mailman.getName()) + "\""
                            + ",\"courierReward\":" + (rewardPaid ? courierReward : 0L) + "}"
            );
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not queue delivered-mail event for Iris: " + exception.getMessage());
        }

        return DeliveryResult.delivered(rewardPaid ? courierReward : 0L, platform.currency().symbol());
    }

    private ItemStack deliveredLetter(MailRecord record) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Letter from " + record.senderName(), NamedTextColor.WHITE));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("To: " + record.recipientName(), NamedTextColor.GRAY));
        lore.add(Component.text(record.address(), NamedTextColor.GRAY));
        lore.add(Component.empty());
        for (String line : wrap(record.message(), 42)) {
            lore.add(Component.text(line, NamedTextColor.WHITE));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(deliveredKey, PersistentDataType.STRING, record.id().toString());
        item.setItemMeta(meta);
        return item;
    }

    private Optional<Mailbox> primaryMailbox(UUID playerId) throws SQLException {
        UUID propertyId = null;
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT property_uuid FROM gp_primary_mailboxes WHERE player_uuid = ? LIMIT 1")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    propertyId = UUID.fromString(result.getString("property_uuid"));
                }
            }
        }
        if (propertyId == null || !properties.isOwnedBy(propertyId, playerId)) {
            return Optional.empty();
        }
        return properties.mailbox(propertyId).map(this::mailbox);
    }

    private Optional<Mailbox> mailboxAt(Block block) throws SQLException {
        if (block == null) return Optional.empty();
        return properties.mailboxAt(
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ()
        ).map(this::mailbox);
    }

    private boolean ownsMailbox(UUID playerId, UUID propertyId) throws SQLException {
        return properties.isOwnedBy(propertyId, playerId);
    }

    private Mailbox mailbox(PropertyMailbox mailbox) {
        return new Mailbox(
                mailbox.propertyId(),
                mailbox.worldId(),
                mailbox.worldName(),
                mailbox.x(),
                mailbox.y(),
                mailbox.z(),
                mailbox.address()
        );
    }

    private List<Mailbox> mailboxesFor(UUID playerId) throws SQLException {
        return properties.mailboxesOwnedBy(playerId).stream().map(this::mailbox).toList();
    }

    private void insert(MailRecord record) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gp_mail "
                             + "(mail_uuid, order_uuid, sender_uuid, sender_name, recipient_uuid, recipient_name, "
                             + "property_uuid, mailbox_world_uuid, mailbox_world_name, mailbox_x, mailbox_y, mailbox_z, "
                             + "address, message, status, assigned_mailman_uuid, created_at, assigned_at, delivered_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, NULL)")) {
            statement.setString(1, record.id().toString());
            statement.setString(2, record.orderId().toString());
            statement.setString(3, record.senderId().toString());
            statement.setString(4, record.senderName());
            statement.setString(5, record.recipientId().toString());
            statement.setString(6, record.recipientName());
            statement.setString(7, record.propertyId().toString());
            statement.setString(8, record.mailboxWorldId().toString());
            statement.setString(9, record.mailboxWorldName());
            statement.setInt(10, record.mailboxX());
            statement.setInt(11, record.mailboxY());
            statement.setInt(12, record.mailboxZ());
            statement.setString(13, record.address());
            statement.setString(14, record.message());
            statement.setString(15, record.status().name());
            statement.setLong(16, record.createdAt());
            statement.executeUpdate();
        }
    }

    private boolean payCourierReward(UUID mailId, Player mailman) throws SQLException {
        long claimedAt = System.currentTimeMillis();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gp_courier_rewards "
                             + "(mail_uuid, mailman_uuid, amount, claimed_at, paid_at) VALUES (?, ?, ?, ?, NULL)")) {
            statement.setString(1, mailId.toString());
            statement.setString(2, mailman.getUniqueId().toString());
            statement.setLong(3, courierReward);
            statement.setLong(4, claimedAt);
            try {
                statement.executeUpdate();
            } catch (SQLException duplicateOrFailure) {
                if (courierRewardExists(mailId)) {
                    return false;
                }
                throw duplicateOrFailure;
            }
        }

        if (!platform.currency().deposit(mailman.getUniqueId(), courierReward)) {
            try (Connection connection = platform.storage().connection();
                 PreparedStatement cleanup = connection.prepareStatement(
                         "DELETE FROM gp_courier_rewards WHERE mail_uuid = ? AND paid_at IS NULL")) {
                cleanup.setString(1, mailId.toString());
                cleanup.executeUpdate();
            }
            return false;
        }

        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gp_courier_rewards SET paid_at = ? WHERE mail_uuid = ? AND paid_at IS NULL")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, mailId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            // Keep the unique reward reservation if the audit timestamp cannot be updated.
            // The Obols were already deposited, so removing it could allow a duplicate payout.
            plugin.getLogger().warning("Courier reward for mail " + mailId
                    + " was paid, but its paid_at audit timestamp could not be updated: " + exception.getMessage());
        }
        return true;
    }

    private boolean courierRewardExists(UUID mailId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM gp_courier_rewards WHERE mail_uuid = ? LIMIT 1")) {
            statement.setString(1, mailId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void releaseStaleAssignments() throws SQLException {
        long cutoff = System.currentTimeMillis() - assignmentTimeoutMillis;
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gp_mail SET status = 'PENDING', assigned_mailman_uuid = NULL, assigned_at = NULL "
                             + "WHERE status = 'ASSIGNED' AND assigned_at < ?")) {
            statement.setLong(1, cutoff);
            statement.executeUpdate();
        }
    }

    private MailRecord read(ResultSet result) throws SQLException {
        Object assignedAt = result.getObject("assigned_at");
        Object deliveredAt = result.getObject("delivered_at");
        String mailman = result.getString("assigned_mailman_uuid");
        return new MailRecord(
                UUID.fromString(result.getString("mail_uuid")),
                UUID.fromString(result.getString("order_uuid")),
                UUID.fromString(result.getString("sender_uuid")),
                result.getString("sender_name"),
                UUID.fromString(result.getString("recipient_uuid")),
                result.getString("recipient_name"),
                UUID.fromString(result.getString("property_uuid")),
                UUID.fromString(result.getString("mailbox_world_uuid")),
                result.getString("mailbox_world_name"),
                result.getInt("mailbox_x"),
                result.getInt("mailbox_y"),
                result.getInt("mailbox_z"),
                result.getString("address"),
                result.getString("message"),
                MailStatus.valueOf(result.getString("status")),
                mailman == null ? null : UUID.fromString(mailman),
                result.getLong("created_at"),
                assignedAt == null ? null : result.getLong("assigned_at"),
                deliveredAt == null ? null : result.getLong("delivered_at")
        );
    }

    private String cleanMessage(String message) {
        if (message == null) return "";
        String clean = message.trim().replaceAll("\\s+", " ");
        return clean.length() <= 800 ? clean : clean.substring(0, 800);
    }

    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    public record Mailbox(UUID propertyId, UUID worldId, String worldName, int x, int y, int z, String address) {}
    public record Recipient(UUID playerId, String playerName, Mailbox mailbox) {}
    public record DeliveryResult(boolean success, String message) {
        public static DeliveryResult delivered(long reward, String symbol) {
            return reward > 0
                    ? new DeliveryResult(true, "Delivery complete. + " + symbol + " " + reward + ".")
                    : new DeliveryResult(true, "Mail delivered.");
        }

        public static DeliveryResult failure(String message) { return new DeliveryResult(false, message); }
    }
}
