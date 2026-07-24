package org.betonquest.betonquest.database;

import org.betonquest.betonquest.api.logger.BetonQuestLogger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

final class AssetSequenceDatabaseTest {
    private static final String PROFILE_ID = "00000000-0000-0000-0000-000000000201";
    private static final String OBJECTIVE_ID = "test.pickup";

    @TempDir
    private Path temporaryDirectory;

    private TestDatabase database;

    @BeforeEach
    void setUp() throws SQLException {
        database = new TestDatabase(mock(BetonQuestLogger.class), mock(Plugin.class),
                temporaryDirectory.resolve("asset-sequence.db"));
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE objectives (profileID TEXT NOT NULL, objective TEXT NOT NULL, "
                    + "instructions TEXT NOT NULL, PRIMARY KEY (profileID, objective))");
            statement.executeUpdate("CREATE TABLE asset_sequence_cursor (profileID TEXT NOT NULL, "
                    + "objective TEXT NOT NULL, asset_sequence INTEGER NOT NULL, "
                    + "PRIMARY KEY (profileID, objective))");
        }
    }

    @Test
    void completedProgressSurvivesCrashBeforeRewardFinalization() throws SQLException {
        insertObjective("4/5");
        AtomicInteger mutations = new AtomicInteger();

        DomainEventProgressResult applied = database.commitDomainEventProgress(
                PROFILE_ID, OBJECTIVE_ID, 10, ignored -> {
                    mutations.incrementAndGet();
                    return "5/5";
                });
        DomainEventProgressResult replayed = database.commitDomainEventProgress(
                PROFILE_ID, OBJECTIVE_ID, 10, ignored -> {
                    mutations.incrementAndGet();
                    return "must-not-run";
                });

        assertEquals(DomainEventProgressResult.Status.APPLIED, applied.status());
        assertEquals("5/5", applied.instructions());
        assertEquals(DomainEventProgressResult.Status.REPLAYED, replayed.status());
        assertEquals("5/5", replayed.instructions());
        assertEquals(1, mutations.get());
        assertEquals(10L, cursor());
    }

    @Test
    void nonPersistentCompletionFinalizesOnceAndReplayIsHarmless() throws SQLException {
        insertObjective("4/5");
        DomainEventProgressResult applied = database.commitDomainEventProgress(
                PROFILE_ID, OBJECTIVE_ID, 20, ignored -> "5/5");

        assertTrue(database.finalizeObjectiveCompletion(
                PROFILE_ID, OBJECTIVE_ID, 20, applied.instructions(), null));
        assertFalse(database.finalizeObjectiveCompletion(
                PROFILE_ID, OBJECTIVE_ID, 20, applied.instructions(), null));
        DomainEventProgressResult replayed = database.commitDomainEventProgress(
                PROFILE_ID, OBJECTIVE_ID, 20, ignored -> "must-not-run");

        assertEquals(DomainEventProgressResult.Status.REPLAYED, replayed.status());
        assertNull(replayed.instructions());
        assertEquals(20L, cursor());
    }

    @Test
    void persistentCompletionResetsOnlyOnceAcrossAckReplay() throws SQLException {
        insertObjective("4/5");
        DomainEventProgressResult applied = database.commitDomainEventProgress(
                PROFILE_ID, OBJECTIVE_ID, 30, ignored -> "5/5");
        assertTrue(database.finalizeObjectiveCompletion(
                PROFILE_ID, OBJECTIVE_ID, 30, applied.instructions(), "0/5"));

        AtomicInteger replayMutations = new AtomicInteger();
        DomainEventProgressResult replayed = database.commitDomainEventProgress(
                PROFILE_ID, OBJECTIVE_ID, 30, ignored -> {
                    replayMutations.incrementAndGet();
                    return "must-not-run";
                });
        DomainEventProgressResult nextEvent = database.commitDomainEventProgress(
                PROFILE_ID, OBJECTIVE_ID, 31, ignored -> "1/5");

        assertEquals(DomainEventProgressResult.Status.REPLAYED, replayed.status());
        assertEquals("0/5", replayed.instructions());
        assertEquals(0, replayMutations.get());
        assertEquals(DomainEventProgressResult.Status.APPLIED, nextEvent.status());
        assertEquals("1/5", nextEvent.instructions());
        assertEquals(31L, cursor());
    }

    private void insertObjective(String instructions) throws SQLException {
        try (Connection connection = database.openConnection(); var statement = connection.prepareStatement(
                "INSERT INTO objectives (profileID, objective, instructions) VALUES (?, ?, ?)")) {
            statement.setString(1, PROFILE_ID);
            statement.setString(2, OBJECTIVE_ID);
            statement.setString(3, instructions);
            statement.executeUpdate();
        }
    }

    private long cursor() throws SQLException {
        try (Connection connection = database.openConnection(); var statement = connection.prepareStatement(
                "SELECT asset_sequence FROM asset_sequence_cursor WHERE profileID = ? AND objective = ?")) {
            statement.setString(1, PROFILE_ID);
            statement.setString(2, OBJECTIVE_ID);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : -1L;
            }
        }
    }

    private static final class TestDatabase extends Database {
        private final String jdbcUrl;

        private TestDatabase(BetonQuestLogger log, Plugin plugin, Path databasePath) {
            super(log, plugin, "", "");
            jdbcUrl = "jdbc:sqlite:" + databasePath;
        }

        @Override
        protected Connection openConnection() throws SQLException {
            return DriverManager.getConnection(jdbcUrl);
        }

        @Override
        protected SortedMap<MigrationKey, DatabaseUpdate> getMigrations() {
            return new TreeMap<>();
        }

        @Override
        protected Set<MigrationKey> queryExecutedMigrations(Connection connection) {
            return Set.of();
        }

        @Override
        protected void markMigrationExecuted(Connection connection, MigrationKey migrationKey) {
        }
    }
}
