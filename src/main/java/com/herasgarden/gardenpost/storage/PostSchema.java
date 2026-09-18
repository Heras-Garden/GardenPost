package com.herasgarden.gardenpost.storage;

import com.herasgarden.gardencore.api.storage.GardenStorage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class PostSchema {
    private PostSchema() {}

    public static void ensure(GardenStorage storage) throws SQLException {
        try (Connection connection = storage.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gp_mail ("
                    + "mail_uuid VARCHAR(36) PRIMARY KEY,"
                    + "order_uuid VARCHAR(36) NOT NULL,"
                    + "sender_uuid VARCHAR(36) NOT NULL,"
                    + "sender_name VARCHAR(32) NOT NULL,"
                    + "recipient_uuid VARCHAR(36) NOT NULL,"
                    + "recipient_name VARCHAR(32) NOT NULL,"
                    + "property_uuid VARCHAR(36) NOT NULL,"
                    + "mailbox_world_uuid VARCHAR(36) NOT NULL,"
                    + "mailbox_world_name VARCHAR(128) NOT NULL,"
                    + "mailbox_x INTEGER NOT NULL,"
                    + "mailbox_y INTEGER NOT NULL,"
                    + "mailbox_z INTEGER NOT NULL,"
                    + "address VARCHAR(192) NOT NULL,"
                    + "message TEXT NOT NULL,"
                    + "status VARCHAR(24) NOT NULL,"
                    + "assigned_mailman_uuid VARCHAR(36) NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "assigned_at BIGINT NULL,"
                    + "delivered_at BIGINT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gp_mail_status "
                    + "ON gp_mail (status, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gp_mail_recipient "
                    + "ON gp_mail (recipient_uuid, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gp_mail_sender "
                    + "ON gp_mail (sender_uuid, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gp_mail_mailman "
                    + "ON gp_mail (assigned_mailman_uuid, status, assigned_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gp_primary_mailboxes ("
                    + "player_uuid VARCHAR(36) PRIMARY KEY,"
                    + "property_uuid VARCHAR(36) NOT NULL,"
                    + "updated_at BIGINT NOT NULL)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gp_courier_rewards ("
                    + "mail_uuid VARCHAR(36) PRIMARY KEY,"
                    + "mailman_uuid VARCHAR(36) NOT NULL,"
                    + "amount BIGINT NOT NULL,"
                    + "claimed_at BIGINT NOT NULL,"
                    + "paid_at BIGINT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gp_courier_rewards_mailman "
                    + "ON gp_courier_rewards (mailman_uuid, claimed_at)");
        }
    }
}
