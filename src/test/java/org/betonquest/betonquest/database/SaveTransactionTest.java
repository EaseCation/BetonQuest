package org.betonquest.betonquest.database;

import org.betonquest.betonquest.api.logger.BetonQuestLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SaveTransactionTest {
    @TempDir Path directory;

    @Test void replacementRollsBackWhenInsertFails() throws Exception {
        final TestDatabase db = database();
        db.saveRecords("initial", List.of(new Saver.Record(UpdateType.ADD_POINTS, "p", "contribution", "100")));
        assertThrows(SQLException.class, () -> db.saveRecords("failed", List.of(
                new Saver.Record(UpdateType.REMOVE_POINTS, "p", "contribution"),
                new Saver.Record(UpdateType.ADD_POINTS, "p", "contribution", null))));
        assertEquals(100, points(db, "p"));
        db.closeConnection();
    }

    @Test void committedResponseLossDoesNotReplayWrites() throws Exception {
        final TestDatabase db = database();
        db.loseCommitResponse.set(true);
        final List<Saver.Record> records = List.of(new Saver.Record(UpdateType.ADD_POINTS, "p", "contribution", "200"));
        db.saveRecords("same-operation", records);
        db.saveRecords("same-operation", records);
        assertEquals(200, points(db, "p"));
        db.closeConnection();
    }

    @Test void failedProfileDoesNotBlockOtherProfilesAndCannotReceiveConfirmation() throws Exception {
        final TestDatabase db = database();
        final AsyncSaver saver = new AsyncSaver(mock(BetonQuestLogger.class), db);
        saver.start();
        try {
            final var failed = saver.save(List.of(new Saver.Record(UpdateType.ADD_POINTS, "broken", "contribution", null)));
            assertThrows(Exception.class, () -> failed.get(3, TimeUnit.SECONDS));
            saver.save(List.of(new Saver.Record(UpdateType.ADD_POINTS, "healthy", "contribution", "300"))).get(3, TimeUnit.SECONDS);
            saver.checkpoint("healthy").get(3, TimeUnit.SECONDS);
            assertEquals(300, points(db, "healthy"));
            assertThrows(Exception.class, () -> saver.checkpoint("broken").get(3, TimeUnit.SECONDS));
        } finally { saver.end(); db.closeConnection(); }
        assertFalse(saver.isAlive());
    }

    @Test void shutdownDrainsAcceptedWritesBeforeClosing() throws Exception {
        final TestDatabase db = database();
        final AsyncSaver saver = new AsyncSaver(mock(BetonQuestLogger.class), db);
        final var pending = saver.save(List.of(new Saver.Record(UpdateType.ADD_POINTS, "p", "contribution", "500")));
        assertFalse(pending.isDone());
        saver.start();
        saver.end();
        pending.get(3, TimeUnit.SECONDS);
        assertEquals(500, points(db, "p"));
        assertTrue(saver.checkpoint("p").isCompletedExceptionally());
        db.closeConnection();
    }

    @Test void recoveredConnectionReplaysRetainedWritesInOrder() throws Exception {
        final TestDatabase db = database();
        final AsyncSaver saver = new AsyncSaver(mock(BetonQuestLogger.class), db);
        saver.start();
        try {
            db.unavailable.set(true);
            assertThrows(Exception.class, () -> saver.save(List.of(new Saver.Record(UpdateType.ADD_POINTS, "p", "contribution", "100"))).get(3, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> saver.save(List.of(new Saver.Record(UpdateType.REMOVE_POINTS, "p", "contribution"),
                    new Saver.Record(UpdateType.ADD_POINTS, "p", "contribution", "200"))).get(3, TimeUnit.SECONDS));
            db.unavailable.set(false);
            saver.checkpoint("p").get(3, TimeUnit.SECONDS);
            assertEquals(200, points(db, "p"));
            saver.save(List.of(new Saver.Record(UpdateType.REMOVE_POINTS, "p", "contribution"),
                    new Saver.Record(UpdateType.ADD_POINTS, "p", "contribution", "300"))).get(3, TimeUnit.SECONDS);
            assertEquals(300, points(db, "p"));
        } finally { saver.end(); db.closeConnection(); }
    }

    @Test void mixedProfileFailureBlocksBothOwnersButNotUnrelatedPlayers() throws Exception {
        final TestDatabase db = database();
        final AsyncSaver saver = new AsyncSaver(mock(BetonQuestLogger.class), db);
        saver.start();
        try {
            assertThrows(Exception.class, () -> saver.save(List.of(
                    new Saver.Record(UpdateType.ADD_POINTS, "a", "contribution", "100"),
                    new Saver.Record(UpdateType.ADD_POINTS, "b", "contribution", null))).get(3, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> saver.checkpoint("a").get(3, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> saver.checkpoint("b").get(3, TimeUnit.SECONDS));
            saver.save(List.of(new Saver.Record(UpdateType.ADD_POINTS, "c", "contribution", "300"))).get(3, TimeUnit.SECONDS);
            assertEquals(300, points(db, "c"));
        } finally { saver.end(); db.closeConnection(); }
    }

    @Test void conversationFailureIsAttributedToPlayerNotConversationText() throws Exception {
        final TestDatabase db = database();
        final AsyncSaver saver = new AsyncSaver(mock(BetonQuestLogger.class), db);
        saver.start();
        try {
            // The test schema deliberately has no player table, so this write fails permanently.
            assertThrows(Exception.class, () -> saver.save(List.of(
                    new Saver.Record(UpdateType.UPDATE_CONVERSATION, "conversation-state", "p"))).get(3, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> saver.checkpoint("p").get(3, TimeUnit.SECONDS));
            saver.checkpoint("conversation-state").get(3, TimeUnit.SECONDS);
        } finally { saver.end(); db.closeConnection(); }
    }

    private TestDatabase database() throws Exception {
        final TestDatabase db = new TestDatabase(System.getenv().getOrDefault("EC_SAVE_TEST_MYSQL_URL", "jdbc:sqlite:" + directory.resolve("quest.db")));
        db.createTables();
        try (var connection = db.openIndependentConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + db.getTablePrefix() + "points(profileID VARCHAR(36), category VARCHAR(64), count INT NOT NULL, PRIMARY KEY(profileID,category))");
        }
        return db;
    }

    private int points(TestDatabase db, String profile) throws Exception {
        try (var connection = db.openIndependentConnection(); var statement = connection.prepareStatement("SELECT count FROM " + db.getTablePrefix() + "points WHERE profileID=?")) {
            statement.setString(1, profile);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
        }
    }

    private static final class TestDatabase extends Database {
        private final String url;
        final AtomicBoolean loseCommitResponse = new AtomicBoolean();
        final AtomicBoolean unavailable = new AtomicBoolean();
        TestDatabase(String url) { super(mock(BetonQuestLogger.class), null, "q" + java.util.UUID.randomUUID().toString().replace("-", "") + "_", "default"); this.url = url; }
        @Override protected Connection openConnection() throws SQLException {
            if (unavailable.get()) throw new SQLException("Injected temporary outage", "08006");
            final Connection connection = DriverManager.getConnection(url);
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, method, args) -> {
                try {
                    Object result = method.invoke(connection, args);
                    if (method.getName().equals("commit") && loseCommitResponse.compareAndSet(true, false)) {
                        throw new SQLException("Injected commit response loss", "08006");
                    }
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        @Override protected SortedMap<MigrationKey, DatabaseUpdate> getMigrations() { return new TreeMap<>(); }
        @Override protected Set<MigrationKey> queryExecutedMigrations(Connection connection) { return Set.of(); }
        @Override protected void markMigrationExecuted(Connection connection, MigrationKey key) { }
    }
}
