package org.betonquest.betonquest.database;

import org.betonquest.betonquest.api.logger.BetonQuestLogger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 用真实 PostgreSQL 验证持久回执、事务回滚和并发序列去重；不触碰运行数据库。 */
@EnabledIfEnvironmentVariable(named = "BETONQUEST_PG_TEST_URL", matches = ".+")
final class PostgreSQLIntegrationTest {
    private static final String PROFILE = "00000000-0000-0000-0000-000000000201";
    private PostgreSQL database;
    private String schema;
    private String url;
    private String user;
    private String password;

    @BeforeEach
    void initialize() throws Exception {
        url = System.getenv("BETONQUEST_PG_TEST_URL");
        assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:5433/sb_migration_validation"),
                "Integration tests are restricted to the isolated migration database");
        user = System.getenv("BETONQUEST_PG_TEST_USER");
        password = System.getenv("BETONQUEST_PG_TEST_PASSWORD");
        schema = "bq_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE SCHEMA " + schema);
        }
        database = new PostgreSQL(mock(BetonQuestLogger.class), mock(Plugin.class),
                url + "?currentSchema=" + schema, user, password, "bq_", "default");
        database.createTables();
        try (Connection connection = database.openConnection(); var statement = connection.prepareStatement(
                "INSERT INTO bq_profile(profileID) VALUES (?)")) {
            statement.setString(1, PROFILE);
            statement.executeUpdate();
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        if (database != null) database.closeConnection();
        if (schema != null) {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void acknowledgedWritesReplayWithoutDuplicateProgress() throws Exception {
        final String operation = UUID.randomUUID().toString();
        final List<Saver.Record> records = List.of(new Saver.Record(UpdateType.ADD_POINTS, PROFILE, "farming", "17"));
        database.saveRecords(operation, records);
        database.saveRecords(operation, records);
        assertEquals(17, scalar("SELECT count FROM bq_points WHERE category='farming'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM bq_save_receipts"));
    }

    @Test
    void failedBatchRollsBackDataAndReceipt() throws Exception {
        assertThrows(SQLException.class, () -> database.saveRecords(UUID.randomUUID().toString(), List.of(
                new Saver.Record(UpdateType.ADD_POINTS, PROFILE, "rollback", "9"),
                new Saver.Record(UpdateType.ADD_TAGS, "00000000-0000-0000-0000-000000000999", "invalid-profile"))));
        assertEquals(0, scalar("SELECT COUNT(*) FROM bq_points"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM bq_save_receipts"));
    }

    @Test
    void concurrentReplayLocksTheActualObjectiveRow() throws Exception {
        database.saveRecords(UUID.randomUUID().toString(), List.of(
                new Saver.Record(UpdateType.ADD_OBJECTIVES, PROFILE, "test.harvest", "0/5")));
        final AtomicInteger mutations = new AtomicInteger();
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final var executor = Executors.newFixedThreadPool(2);
        try {
            final java.util.concurrent.Callable<DomainEventProgressResult> action = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return database.commitDomainEventProgress(PROFILE, "test.harvest", 10, old -> {
                    mutations.incrementAndGet();
                    return "1/5";
                });
            };
            final var first = executor.submit(action);
            final var second = executor.submit(action);
            final var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(r -> r.status() == DomainEventProgressResult.Status.APPLIED).count());
            assertEquals(1, results.stream().filter(r -> r.status() == DomainEventProgressResult.Status.REPLAYED).count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, mutations.get());
    }

    @Test
    void journalUsesGeneratedIdentityAndReadableTimestamp() throws Exception {
        assertTrue(database.usesFormattedJournalDates());
        database.saveRecords(UUID.randomUUID().toString(), List.of(
                new Saver.Record(UpdateType.ADD_JOURNAL, PROFILE, "test.intro", "2026-10-02 12:00:00")));
        try (Connection connection = database.openConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,date FROM bq_journal")) {
            assertTrue(rows.next());
            assertTrue(rows.getLong("id") > 0);
            assertNotNull(rows.getTimestamp("date"));
        }
    }

    private long scalar(final String query) throws SQLException {
        try (Connection connection = database.openConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
