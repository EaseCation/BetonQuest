package org.betonquest.betonquest.database;

import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.api.logger.BetonQuestLogger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.UnaryOperator;

/**
 * Abstract Database class, serves as a base for any connection method (MySQL,
 * SQLite, etc.)
 */
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
public abstract class Database {
    protected final Plugin plugin;

    protected final String prefix;

    protected final String profileInitialName;

    /**
     * Custom {@link BetonQuestLogger} instance for this class.
     */
    private final BetonQuestLogger log;

    @Nullable
    protected Connection con;

    protected Database(final BetonQuestLogger log, final BetonQuest plugin) {
        this(log, plugin, plugin.getPluginConfig().getString("mysql.prefix", ""),
                plugin.getPluginConfig().getString("profiles.initial_name", ""));
    }

    /**
     * Creates a database backend with an explicit schema prefix and initial profile name.
     *
     * <p>This constructor keeps the transactional persistence layer independently testable without constructing the
     * full Bukkit plugin lifecycle. Production backends continue to use {@link #Database(BetonQuestLogger, BetonQuest)}.
     * </p>
     *
     * @param log                database logger
     * @param plugin             owning Bukkit plugin
     * @param prefix             table prefix
     * @param profileInitialName initial profile name
     */
    protected Database(final BetonQuestLogger log, final Plugin plugin, final String prefix,
                       final String profileInitialName) {
        this.log = log;
        this.plugin = plugin;
        this.prefix = prefix;
        this.profileInitialName = profileInitialName;
    }

    public Connection getConnection() {
        try {
            if (con == null || con.isClosed() || isConnectionBroken(con)) {
                con = openConnection();
            }
        } catch (final SQLException e) {
            log.error("Failed opening database connection!", e);
        }
        if (con == null) {
            throw new IllegalStateException("Not able to create a database connection!");
        }
        return con;
    }

    private boolean isConnectionBroken(final Connection connection) throws SQLException {
        try {
            try (PreparedStatement stmnt = connection.prepareStatement("SELECT 1");
                 ResultSet result = stmnt.executeQuery()) {
                return !result.next();
            }
        } catch (final SQLException e) {
            return true;
        }
    }

    protected abstract Connection openConnection() throws SQLException;

    /** Opens a caller-owned connection for acknowledged content operations. */
    public final Connection openIndependentConnection() throws SQLException {
        return Objects.requireNonNull(openConnection(), "Database connection unavailable");
    }

    /** @return configured prefix for extension tables */
    public final String getTablePrefix() { return prefix; }

    /**
     * Commits a content update and its operation ID together. Retrying a lost commit response with the
     * same ID never replays SQL. Receipts confirm storage operations; they do not own quest progression.
     */
    public final void saveRecords(final String operation, final List<Saver.Record> records) throws SQLException {
        try (Connection connection = openIndependentConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement receipt = connection.prepareStatement("INSERT INTO " + prefix
                        + "save_receipts (operation_id, created_at) VALUES (?, ?)")) {
                    receipt.setString(1, operation);
                    receipt.setLong(2, System.currentTimeMillis());
                    receipt.executeUpdate();
                }
                for (final Saver.Record record : records) {
                    try (PreparedStatement statement = connection.prepareStatement(record.type().createSql(prefix))) {
                        final String[] args = record.args();
                        for (int index = 0; index < args.length; index++) statement.setString(index + 1, args[index]);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                try (Connection verify = openIndependentConnection(); PreparedStatement receipt = verify.prepareStatement(
                        "SELECT operation_id FROM " + prefix + "save_receipts WHERE operation_id = ?")) {
                    receipt.setString(1, operation);
                    try (ResultSet rows = receipt.executeQuery()) { if (rows.next()) return; }
                } catch (SQLException verification) { failure.addSuppressed(verification); }
                throw failure;
            }
        }
    }

    public void closeConnection() {
        if (con != null) {
            try {
                con.close();
            } catch (final SQLException e) {
                log.error("Failed to close the database connection!", e);
            }
        }
        con = null;
    }

    public final void createTables() {
        try {
            final SortedMap<MigrationKey, DatabaseUpdate> migrations = getMigrations();
            final Set<MigrationKey> executedMigrations = queryExecutedMigrations(getConnection());
            executedMigrations.forEach(migrations::remove);

            while (!migrations.isEmpty()) {
                final MigrationKey key = migrations.firstKey();
                final DatabaseUpdate migration = migrations.remove(key);
                migration.executeUpdate(getConnection());
                markMigrationExecuted(getConnection(), key);
            }
            try (Statement statement = getConnection().createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix
                        + "save_receipts (operation_id VARCHAR(36) PRIMARY KEY, created_at BIGINT NOT NULL)");
                statement.executeUpdate("DELETE FROM " + prefix + "save_receipts WHERE created_at < "
                        + (System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000));
            }
        } catch (final SQLException sqlException) {
            log.error("There was an exception with SQL while creating the database tables!", sqlException);
        }
    }

    /**
     * Returns a SortedMap of all migrations with an identifier as {@link MigrationKey} and the migration function as
     * Value.
     *
     * @return the SortedMap of all migrations
     */
    protected abstract SortedMap<MigrationKey, DatabaseUpdate> getMigrations();

    /**
     * Queries the database for all migrations that have been executed. The function have to ensure that the table
     * containing the executed migrations exists.
     *
     * @param connection the connection to the database
     * @return a set of all migrations, in form of {@link MigrationKey}, that have been executed
     * @throws SQLException if something went wrong with the query
     */
    protected abstract Set<MigrationKey> queryExecutedMigrations(Connection connection) throws SQLException;

    /**
     * Marks the migration as executed in the database to have been executed.
     *
     * @param connection   the connection to the database
     * @param migrationKey the specific migration to mark as executed
     * @throws SQLException if the migration could not be marked as executed
     */
    protected abstract void markMigrationExecuted(Connection connection, MigrationKey migrationKey) throws SQLException;

    /**
     * Atomically replaces objective instructions and advances a source-domain event cursor.
     *
     * <p>The mutation is evaluated from the database value while the objective row is locked. Replayed sequences
     * return the previously committed instructions without invoking the mutation again.</p>
     *
     * @param profileID    profile owning the objective
     * @param objectiveID  complete objective identifier
     * @param sourceSequence positive sequence assigned by the source domain's durable outbox
     * @param mutation     pure function producing the next instruction string
     * @return durable processing result
     */
    public final DomainEventProgressResult commitDomainEventProgress(final String profileID,
                                                                      final String objectiveID,
                                                                      final long sourceSequence,
                                                                      final UnaryOperator<String> mutation) {
        if (sourceSequence <= 0) {
            throw new IllegalArgumentException("sourceSequence must be positive");
        }
        Objects.requireNonNull(mutation, "mutation");
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                final String current = selectObjectiveInstructions(connection, profileID, objectiveID);
                final long cursor = selectDomainEventCursor(connection, profileID, objectiveID);
                if (cursor >= sourceSequence) {
                    connection.commit();
                    return new DomainEventProgressResult(DomainEventProgressResult.Status.REPLAYED, current);
                }
                if (current == null) {
                    connection.commit();
                    return new DomainEventProgressResult(DomainEventProgressResult.Status.NOT_ACTIVE, null);
                }
                final String next = Objects.requireNonNull(mutation.apply(current), "objective mutation result");
                try (PreparedStatement statement = connection.prepareStatement("UPDATE " + prefix
                        + "objectives SET instructions = ? WHERE profileID = ? AND objective = ? AND instructions = ?")) {
                    statement.setString(1, next);
                    statement.setString(2, profileID);
                    statement.setString(3, objectiveID);
                    statement.setString(4, current);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Objective changed while its source event sequence was being committed");
                    }
                }
                upsertDomainEventCursor(connection, profileID, objectiveID, sourceSequence);
                connection.commit();
                return new DomainEventProgressResult(DomainEventProgressResult.Status.APPLIED, next);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Could not atomically persist objective progress and source sequence", sqlException);
        }
    }

    /** @deprecated use {@link #commitDomainEventProgress(String, String, long, UnaryOperator)}. */
    @Deprecated(forRemoval = false)
    public final AssetSequenceProgressResult commitObjectiveProgress(final String profileID,
                                                                     final String objectiveID,
                                                                     final long assetSequence,
                                                                     final UnaryOperator<String> mutation) {
        final DomainEventProgressResult result = commitDomainEventProgress(
                profileID, objectiveID, assetSequence, mutation);
        return new AssetSequenceProgressResult(
                AssetSequenceProgressResult.Status.valueOf(result.status().name()), result.instructions());
    }

    /**
     * Raises a newly started objective's cursor to the current source-domain sequence, so events committed before the
     * objective existed can never be counted later.
     *
     * @param profileID profile owning the objective
     * @param objectiveID complete objective identifier
     * @param sourceSequence current confirmed source-domain sequence
     */
    public final void initializeDomainEventCursor(final String profileID, final String objectiveID,
                                                   final long sourceSequence) {
        if (sourceSequence < 0) throw new IllegalArgumentException("sourceSequence cannot be negative");
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                final long current = selectDomainEventCursor(connection, profileID, objectiveID);
                if (current < sourceSequence) {
                    upsertDomainEventCursor(connection, profileID, objectiveID, sourceSequence);
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Could not initialize objective source event cursor", sqlException);
        }
    }

    /** @deprecated use {@link #initializeDomainEventCursor(String, String, long)}. */
    @Deprecated(forRemoval = false)
    public final void initializeAssetSequenceCursor(final String profileID, final String objectiveID,
                                                     final long assetSequence) {
        initializeDomainEventCursor(profileID, objectiveID, assetSequence);
    }

    /**
     * Atomically removes or resets a completed objective after its source-domain completion handling succeeds.
     *
     * <p>The sequence cursor remains in place as the completion receipt. A crash before this transaction leaves the
     * completed instruction row available for deterministic reward replay; a crash after it makes the same source
     * sequence a harmless replay.</p>
     *
     * @param profileID profile owning the objective
     * @param objectiveID complete objective identifier
     * @param sourceSequence source-domain sequence
     * @param expectedInstructions exact completed instructions previously committed with the cursor
     * @param persistentInstructions default instructions for persistent objectives, or {@code null} to remove the row
     * @return {@code true} when this call finalized the row, {@code false} when it was already removed
     */
    public final boolean finalizeDomainEventCompletion(final String profileID, final String objectiveID,
                                                        final long sourceSequence, final String expectedInstructions,
                                                        @Nullable final String persistentInstructions) {
        if (sourceSequence <= 0) throw new IllegalArgumentException("sourceSequence must be positive");
        Objects.requireNonNull(expectedInstructions, "expectedInstructions");
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                final long cursor = selectDomainEventCursor(connection, profileID, objectiveID);
                if (cursor < sourceSequence) {
                    throw new SQLException("Objective completion cursor was not durably committed");
                }
                final String current = selectObjectiveInstructions(connection, profileID, objectiveID);
                if (current == null) {
                    connection.commit();
                    return false;
                }
                if (!current.equals(expectedInstructions)) {
                    throw new SQLException("Objective instructions changed before durable completion finalized");
                }
                final String sql;
                if (persistentInstructions == null) {
                    sql = "DELETE FROM " + prefix
                            + "objectives WHERE profileID = ? AND objective = ? AND instructions = ?";
                } else {
                    sql = "UPDATE " + prefix
                            + "objectives SET instructions = ? WHERE profileID = ? AND objective = ? AND instructions = ?";
                }
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int index = 1;
                    if (persistentInstructions != null) statement.setString(index++, persistentInstructions);
                    statement.setString(index++, profileID);
                    statement.setString(index++, objectiveID);
                    statement.setString(index, expectedInstructions);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Objective changed while durable completion was finalizing");
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException sqlException) {
            throw new IllegalStateException("Could not finalize durable objective completion", sqlException);
        }
    }

    /** @deprecated use {@link #finalizeDomainEventCompletion(String, String, long, String, String)}. */
    @Deprecated(forRemoval = false)
    public final boolean finalizeObjectiveCompletion(final String profileID, final String objectiveID,
                                                      final long assetSequence, final String expectedInstructions,
                                                      @Nullable final String persistentInstructions) {
        return finalizeDomainEventCompletion(
                profileID, objectiveID, assetSequence, expectedInstructions, persistentInstructions);
    }

    @Nullable
    private String selectObjectiveInstructions(final Connection connection, final String profileID,
                                               final String objectiveID) throws SQLException {
        final String sql = "SELECT instructions FROM " + prefix
                + "objectives WHERE profileID = ? AND objective = ?" + selectForUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profileID);
            statement.setString(2, objectiveID);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("instructions") : null;
            }
        }
    }

    private long selectDomainEventCursor(final Connection connection, final String profileID,
                                         final String objectiveID) throws SQLException {
        final String sql = "SELECT asset_sequence FROM " + prefix
                + "asset_sequence_cursor WHERE profileID = ? AND objective = ?" + selectForUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profileID);
            statement.setString(2, objectiveID);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong("asset_sequence") : -1L;
            }
        }
    }

    private void upsertDomainEventCursor(final Connection connection, final String profileID,
                                         final String objectiveID, final long sourceSequence) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + prefix
                + "asset_sequence_cursor SET asset_sequence = ? WHERE profileID = ? AND objective = ?")) {
            update.setLong(1, sourceSequence);
            update.setString(2, profileID);
            update.setString(3, objectiveID);
            if (update.executeUpdate() == 1) return;
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + prefix
                + "asset_sequence_cursor (profileID, objective, asset_sequence) VALUES (?, ?, ?)")) {
            insert.setString(1, profileID);
            insert.setString(2, objectiveID);
            insert.setLong(3, sourceSequence);
            insert.executeUpdate();
        }
    }

    /** MySQL overrides this to lock cursor and objective rows; SQLite serializes writers at update time. */
    protected String selectForUpdateClause() {
        return "";
    }
}
