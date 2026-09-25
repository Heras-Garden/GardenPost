package com.herasgarden.gardenpost;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenpost.model.MailRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class MailCommand implements CommandExecutor, TabCompleter {
    private final MailService mail;
    private final ParcelSessionManager parcels;

    public MailCommand(MailService mail, ParcelSessionManager parcels) {
        this.mail = mail;
        this.parcels = parcels;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "Mail commands must be used in-game.");
            return true;
        }
        if (!player.hasPermission("gardenpost.mail")) {
            send(player, "You do not have permission to use Garden mail.");
            return true;
        }
        if (args.length == 0) {
            help(player);
            return true;
        }

        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "send" -> sendMail(player, args);
                case "parcel" -> parcel(player, args);
                case "status" -> status(player);
                case "primary" -> primary(player);
                case "route" -> route(player);
                case "routes" -> routes(player);
                default -> {
                    help(player);
                    yield true;
                }
            };
        } catch (IllegalArgumentException exception) {
            send(player, exception.getMessage());
        } catch (SQLException exception) {
            send(player, "The mail system could not update right now.");
        }
        return true;
    }

    private boolean sendMail(Player sender, String[] args) throws SQLException {
        if (args.length < 3) {
            send(sender, "Use /mail send <player> <message>.");
            return true;
        }

        Optional<MailService.Recipient> recipient = mail.resolveRecipient(args[1]);
        if (recipient.isEmpty()) {
            send(sender, "That player has not joined The Garden SMP before.");
            return true;
        }

        String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        MailRecord record = mail.send(sender, recipient.get(), message);
        send(sender, "Letter " + record.id().toString().substring(0, 8)
                + " accepted for " + record.recipientName() + " at " + record.address()
                + ". Postage: " + mail.postageCost() + " Obols.");
        return true;
    }

    private boolean parcel(Player sender, String[] args) throws SQLException {
        if (args.length < 3) {
            send(sender, "Use /mail parcel <player> <message>.");
            return true;
        }
        Optional<MailService.Recipient> recipient = mail.resolveRecipient(args[1]);
        if (recipient.isEmpty()) {
            send(sender, "That player has not joined The Garden SMP before.");
            return true;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        parcels.open(sender, recipient.get(), message);
        return true;
    }

    private boolean primary(Player player) throws SQLException {
        var block = player.getTargetBlockExact(6);
        if (block == null) {
            send(player, "Look directly at your registered mailbox chest first.");
            return true;
        }
        MailService.Mailbox mailbox = mail.setPrimaryMailbox(player, block);
        send(player, "Primary mailing address set to " + mailbox.address() + ".");
        return true;
    }

    private boolean status(Player player) throws SQLException {
        List<MailRecord> records = mail.sentBy(player.getUniqueId(), 8);
        player.sendMessage(Component.text("[Server] Recent mail", NamedTextColor.GRAY));
        if (records.isEmpty()) {
            player.sendMessage(Component.text("You have not sent any mail yet.", NamedTextColor.WHITE));
            return true;
        }

        for (MailRecord record : records) {
            player.sendMessage(Component.text(
                    record.id().toString().substring(0, 8) + " | "
                            + record.recipientName() + " | "
                            + record.status().name().toLowerCase(Locale.ROOT),
                    NamedTextColor.WHITE));
        }
        return true;
    }

    private boolean route(Player player) throws SQLException {
        if (!player.hasPermission("gardenpost.mailman") && !player.hasPermission("gardenpost.admin")) {
            send(player, "Only Garden mailmen can claim delivery routes.");
            return true;
        }

        Optional<MailRecord> record = mail.claimRoute(player);
        if (record.isEmpty()) {
            send(player, "There is no mail waiting for a courier.");
            return true;
        }

        ItemStack parcel = mail.routeItem(record.get());
        var leftovers = player.getInventory().addItem(parcel);
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));

        send(player, "Courier route: " + record.get().recipientName() + ", "
                + record.get().address() + ". Deliver the Garden Mail item to that registered mailbox.");
        return true;
    }

    private boolean routes(Player player) throws SQLException {
        if (!player.hasPermission("gardenpost.mailman") && !player.hasPermission("gardenpost.admin")) {
            send(player, "Only Garden mailmen can view courier routes.");
            return true;
        }

        List<MailRecord> records = mail.assignedTo(player.getUniqueId());
        if (records.isEmpty()) {
            send(player, "You do not have an assigned mail route.");
            return true;
        }

        player.sendMessage(Component.text("[Server] Courier routes", NamedTextColor.GRAY));
        for (MailRecord record : records) {
            player.sendMessage(Component.text(
                    record.id().toString().substring(0, 8) + " | "
                            + record.recipientName() + " | " + record.address(),
                    NamedTextColor.WHITE));
        }
        return true;
    }

    private void help(Player player) {
        send(player, "/mail send <player> <message>, /mail parcel <player> <message>, /mail status, /mail primary"
                + (player.hasPermission("gardenpost.mailman") || player.hasPermission("gardenpost.admin")
                ? ", /mail route, /mail routes" : ""));
    }

    private void send(CommandSender sender, String message) {
        GardenMessages.send(sender, message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = sender.hasPermission("gardenpost.mailman") || sender.hasPermission("gardenpost.admin")
                    ? List.of("send", "parcel", "status", "primary", "route", "routes")
                    : List.of("send", "parcel", "status", "primary");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return values.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("send") || args[0].equalsIgnoreCase("parcel"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Arrays.stream(Bukkit.getOfflinePlayers())
                    .map(p -> p.getName())
                    .filter(name -> name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .distinct()
                    .limit(25)
                    .toList();
        }
        return List.of();
    }
}
