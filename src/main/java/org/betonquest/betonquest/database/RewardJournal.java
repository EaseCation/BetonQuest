package org.betonquest.betonquest.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stores incomplete content-reward settlements in the configured BetonQuest database.
 * Frozen definitions describe a reward operation, not a second copy of quest progression.
 * All methods perform IO and must be called outside the server thread.
 */
public final class RewardJournal {
    private final Connections connections;
    private final String table;

    /** Opens an independently owned connection for each operation. */
    public RewardJournal(final Database database) {
        this(database::openIndependentConnection, database.getTablePrefix());
    }

    RewardJournal(final Connections connections, final String prefix) {
        if (prefix == null || !prefix.matches("[A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid table prefix");
        this.connections = connections;
        this.table = prefix + "reward_journal";
    }

    /** Creates only the reward journal; existing quest tables and player data are retained. */
    public void initialize() throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "player_id VARCHAR(36) NOT NULL, reward_key VARCHAR(128) NOT NULL, profile_id VARCHAR(36) NOT NULL, "
                + "definition TEXT NOT NULL, inventory_token VARCHAR(36) NOT NULL, stage VARCHAR(32) NOT NULL, "
                + "revision BIGINT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, "
                + "PRIMARY KEY(player_id, reward_key))")) {
            statement.executeUpdate();
        }
    }

    /**
     * Reserves a stable account reward. A retry returns the first frozen definition and inventory token.
     * A caller must use the returned definition; it cannot replace a pending reward by editing configuration.
     * @return existing or newly prepared operation
     */
    public Entry prepare(final UUID player, final UUID profile, final String key, final String definition) throws SQLException {
        if (key == null || !key.matches("[A-Za-z0-9:._-]{1,128}")) throw new IllegalArgumentException("Invalid reward key");
        if (definition == null || definition.isBlank() || definition.length() > 1_048_576) {
            throw new IllegalArgumentException("Invalid reward definition");
        }
        try (Connection connection = connections.open()) {
            final Entry previous = find(connection, player, key);
            if (previous != null) return requireProfile(previous, profile);
            final long now = System.currentTimeMillis();
            final Entry entry = new Entry(player, profile, key, definition, UUID.randomUUID(), Stage.PREPARED, 0, now, now);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table
                    + " (player_id, reward_key, profile_id, definition, inventory_token, stage, revision, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, player.toString());
                insert.setString(2, key);
                insert.setString(3, profile.toString());
                insert.setString(4, definition);
                insert.setString(5, entry.inventoryToken().toString());
                insert.setString(6, entry.stage().name());
                insert.setLong(7, entry.revision());
                insert.setLong(8, now);
                insert.setLong(9, now);
                insert.executeUpdate();
                return entry;
            } catch (SQLException failure) {
                // An ambiguous insert or a concurrent duplicate is resolved by the primary key, never by inventing a new key.
                try (Connection verification = connections.open()) {
                    final Entry saved = find(verification, player, key);
                    if (saved != null) return requireProfile(saved, profile);
                } catch (SQLException verificationFailure) { failure.addSuppressed(verificationFailure); }
                throw failure;
            }
        }
    }

    /** Advances exactly one confirmed asset or content step using a revision comparison. */
    public Entry advance(final Entry before, final Stage next) throws SQLException {
        if (before.stage() == Stage.INVENTORY_UNCERTAIN || next == Stage.INVENTORY_UNCERTAIN) {
            throw new IllegalArgumentException("Uncertain inventory requires manual reconciliation");
        }
        if (next.ordinal() != before.stage().ordinal() + 1) throw new IllegalArgumentException("Reward stages must advance once");
        try (Connection connection = connections.open(); PreparedStatement update = connection.prepareStatement("UPDATE " + table
                + " SET stage = ?, revision = revision + 1, updated_at = ?"
                + " WHERE player_id = ? AND reward_key = ? AND revision = ? AND stage = ? AND inventory_token = ?")) {
            update.setString(1, next.name());
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, before.playerId().toString());
            update.setString(4, before.key());
            update.setLong(5, before.revision());
            update.setString(6, before.stage().name());
            update.setString(7, before.inventoryToken().toString());
            update.executeUpdate();
            final Entry saved = find(connection, before.playerId(), before.key());
            if (saved == null || saved.stage() == Stage.INVENTORY_UNCERTAIN
                    || !saved.inventoryToken().equals(before.inventoryToken()) || saved.stage().ordinal() < next.ordinal()) {
                throw new SQLException("Reward journal revision changed unexpectedly");
            }
            return saved;
        }
    }

    /** Stops automatic delivery when an inventory write and its restoration could not be verified. */
    public Entry markInventoryUncertain(final Entry before) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement update = connection.prepareStatement("UPDATE " + table
                + " SET stage = ?, revision = revision + 1, updated_at = ?"
                + " WHERE player_id = ? AND reward_key = ? AND inventory_token = ? AND stage = ?")) {
            update.setString(1, Stage.INVENTORY_UNCERTAIN.name());
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, before.playerId().toString());
            update.setString(4, before.key());
            update.setString(5, before.inventoryToken().toString());
            update.setString(6, Stage.PREPARED.name());
            update.executeUpdate();
            final Entry saved = find(connection, before.playerId(), before.key());
            if (saved == null || saved.stage() != Stage.INVENTORY_UNCERTAIN) throw new SQLException("Uncertain inventory could not be recorded");
            return saved;
        }
    }

    /** @return unfinished operations to resume after account and BQ data are loaded */
    public List<Entry> pending(final UUID player) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement select = connection.prepareStatement("SELECT * FROM " + table
                + " WHERE player_id = ? AND stage <> ? ORDER BY created_at, reward_key")) {
            select.setString(1, player.toString());
            select.setString(2, Stage.COMPLETED.name());
            final List<Entry> entries = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) { while (rows.next()) entries.add(read(rows)); }
            return List.copyOf(entries);
        }
    }

    /** @return the persisted account reward, or null if it has never been prepared */
    public Entry find(final UUID player, final String key) throws SQLException {
        try (Connection connection = connections.open()) { return find(connection, player, key); }
    }

    /**
     * Removes one test player's reward operations as part of an explicit full character reset.
     * The caller must exclude in-flight claims until the rest of the reset finishes. Money receipts
     * remain an audit of previous credits; new claims must use new, persisted money operation IDs.
     * Uncertain inventory must be reconciled before resetting, rather than hiding an unresolved transfer.
     * @return removed operations, for clearing this player's matching inventory receipts
     */
    public List<Entry> resetForTesting(final UUID player) throws SQLException {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                final List<Entry> entries = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement("SELECT * FROM " + table + " WHERE player_id = ?")) {
                    select.setString(1, player.toString());
                    try (ResultSet rows = select.executeQuery()) { while (rows.next()) entries.add(read(rows)); }
                }
                if (entries.stream().anyMatch(entry -> entry.stage() == Stage.INVENTORY_UNCERTAIN)) {
                    throw new SQLException("Uncertain reward inventory must be reconciled before resetting");
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE player_id = ?")) {
                    delete.setString(1, player.toString());
                    delete.executeUpdate();
                }
                connection.commit();
                return List.copyOf(entries);
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    private Entry find(final Connection connection, final UUID player, final String key) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM " + table + " WHERE player_id = ? AND reward_key = ?")) {
            select.setString(1, player.toString());
            select.setString(2, key);
            try (ResultSet rows = select.executeQuery()) { return rows.next() ? read(rows) : null; }
        }
    }

    private static Entry requireProfile(final Entry entry, final UUID profile) throws SQLException {
        if (!entry.profileId().equals(profile)) throw new SQLException("Reward belongs to a different BQ profile");
        return entry;
    }

    private static Entry read(final ResultSet rows) throws SQLException {
        return new Entry(UUID.fromString(rows.getString("player_id")), UUID.fromString(rows.getString("profile_id")),
                rows.getString("reward_key"), rows.getString("definition"), UUID.fromString(rows.getString("inventory_token")),
                Stage.valueOf(rows.getString("stage")), rows.getLong("revision"), rows.getLong("created_at"), rows.getLong("updated_at"));
    }

    /** Each stage records a confirmed effect; it is not an instruction to repeat that effect. */
    public enum Stage { PREPARED, INVENTORY_CONFIRMED, COINS_CONFIRMED, COMPLETED, INVENTORY_UNCERTAIN }

    /** Frozen operation persisted alongside the existing BQ data. */
    public record Entry(UUID playerId, UUID profileId, String key, String definition, UUID inventoryToken,
                        Stage stage, long revision, long createdAt, long updatedAt) { }

    @FunctionalInterface
    interface Connections { Connection open() throws SQLException; }
}
