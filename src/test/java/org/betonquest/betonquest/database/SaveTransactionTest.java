package org.betonquest.betonquest.database;

import org.betonquest.betonquest.api.logger.BetonQuestLogger;
import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.Instruction;
import org.betonquest.betonquest.Journal;
import org.betonquest.betonquest.api.CountingObjective;
import org.betonquest.betonquest.api.Objective;
import org.betonquest.betonquest.api.profiles.Profile;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

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
import static org.mockito.Mockito.*;

class SaveTransactionTest {
    @TempDir Path directory;

    private static final String PROFILE_ID = "1b8616bb-babd-36a3-82b8-53fea4737fe7";
    private static final String OBJECTIVE_ID = "skyloop_mining.mining_rec_r1_04";

    @Test void emptyCountingDataResumesWithoutPoisoningLoginCheckpoint() throws Exception {
        final TestDatabase db = objectiveDatabase("");
        final AsyncSaver saver = new AsyncSaver(mock(BetonQuestLogger.class), db);
        saver.start();
        try (var singleton = mockStatic(BetonQuest.class); var bukkit = mockStatic(Bukkit.class)) {
            final Profile profile = questRuntime(singleton, bukkit, saver);
            final RecoveryObjective objective = new RecoveryObjective(objectiveInstruction());
            objective.resumeObjectiveForPlayer(profile, "");
            assertTrue(objective.containsPlayer(profile), "Subclass fields must be ready before normalized data is saved");
            saver.checkpoint(PROFILE_ID).get(3, TimeUnit.SECONDS);
            assertEquals("1/1/1/0;ready", objective.getData(profile));
            assertEquals("1/1/1/0;ready", objectiveRow(db));
        } finally { saver.end(); db.closeConnection(); }
    }

    @Test void newObjectiveReplacesStaleRowWithoutPoisoningLoginCheckpoint() throws Exception {
        final TestDatabase db = objectiveDatabase("stale");
        final AsyncSaver saver = new AsyncSaver(mock(BetonQuestLogger.class), db);
        saver.start();
        try (var singleton = mockStatic(BetonQuest.class); var bukkit = mockStatic(Bukkit.class)) {
            final Profile profile = questRuntime(singleton, bukkit, saver);
            final RecoveryObjective objective = new RecoveryObjective(objectiveInstruction());
            // Login provisioning may start an objective whose durable row still exists but was never resumed.
            objective.newPlayer(profile);
            saver.checkpoint(PROFILE_ID).get(3, TimeUnit.SECONDS);
            assertTrue(objective.containsPlayer(profile));
            assertEquals("10", objectiveRow(db));
        } finally { saver.end(); db.closeConnection(); }
    }

    private TestDatabase objectiveDatabase(final String storedInstruction) throws Exception {
        final TestDatabase db = database();
        try (var connection = db.openIndependentConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + db.getTablePrefix()
                    + "objectives(profileID VARCHAR(36), objective VARCHAR(510), instructions TEXT NOT NULL, PRIMARY KEY(profileID,objective))");
        }
        db.saveRecords("existing-objective", List.of(new Saver.Record(UpdateType.ADD_OBJECTIVES, PROFILE_ID, OBJECTIVE_ID, storedInstruction)));
        return db;
    }

    private Profile questRuntime(final MockedStatic<BetonQuest> singleton, final MockedStatic<Bukkit> bukkit, final AsyncSaver saver) {
        final BetonQuest plugin = mock(BetonQuest.class, RETURNS_DEEP_STUBS);
        singleton.when(BetonQuest::getInstance).thenReturn(plugin);
        when(plugin.getSaver()).thenReturn(saver);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
        final Profile profile = mock(Profile.class);
        when(profile.getProfileUUID()).thenReturn(java.util.UUID.fromString(PROFILE_ID));
        final PlayerData player = mock(PlayerData.class);
        when(player.getJournal()).thenReturn(mock(Journal.class));
        when(plugin.getPlayerData(profile)).thenReturn(player);
        return profile;
    }

    private Instruction objectiveInstruction() throws Exception {
        final Instruction instruction = mock(Instruction.class, RETURNS_DEEP_STUBS);
        when(instruction.getArray(any())).thenReturn(new String[0]);
        when(instruction.getID().getFullID()).thenReturn(OBJECTIVE_ID);
        return instruction;
    }

    private String objectiveRow(final TestDatabase db) throws Exception {
        try (var connection = db.openIndependentConnection(); var statement = connection.prepareStatement(
                "SELECT instructions FROM " + db.getTablePrefix() + "objectives WHERE profileID=? AND objective=?")) {
            statement.setString(1, PROFILE_ID);
            statement.setString(2, OBJECTIVE_ID);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
        }
    }

    private static final class RecoveryObjective extends Objective {
        RecoveryObjective(Instruction instruction) throws org.betonquest.betonquest.exceptions.InstructionParseException {
            super(instruction);
            template = ExtendedCountingData.class;
        }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public String getDefaultDataInstruction() { return "10"; }
        @Override public String getProperty(String name, Profile profile) { return ""; }
    }

    /** Models ContentActivityObjective's extension fields, initialized only after the counting constructor. */
    public static final class ExtendedCountingData extends CountingObjective.CountingData {
        private final List<String> operations = List.of("ready");
        public ExtendedCountingData(String instruction, Profile profile, String objective) {
            super(instruction, profile, objective);
        }
        @Override public String toString() { return super.toString() + ";" + String.join(",", operations); }
    }

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
