package org.betonquest.betonquest.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RewardJournalTest {
    @TempDir Path directory;

    @Test void completedRewardCannotBeRepreparedAfterReloadOrConfigurationChange() throws Exception {
        final String prefix = "q" + UUID.randomUUID().toString().replace("-", "") + "_";
        final RewardJournal journal = journal(prefix);
        final UUID player = UUID.randomUUID(), profile = UUID.randomUUID();
        final RewardJournal.Entry prepared = journal.prepare(player, profile, "farming:t1", "hoe + 200 coins");
        RewardJournal.Entry row = journal.advance(prepared, RewardJournal.Stage.INVENTORY_CONFIRMED);
        row = journal.advance(row, RewardJournal.Stage.COINS_CONFIRMED);
        journal.advance(row, RewardJournal.Stage.COMPLETED);

        final RewardJournal reloaded = journal(prefix);
        final RewardJournal.Entry repeated = reloaded.prepare(player, profile, "farming:t1", "hoe + 500 coins");
        assertEquals(RewardJournal.Stage.COMPLETED, repeated.stage());
        assertEquals(prepared.inventoryToken(), repeated.inventoryToken());
        assertEquals("hoe + 200 coins", repeated.definition());
        assertTrue(reloaded.pending(player).isEmpty());
        assertThrows(SQLException.class, () -> reloaded.prepare(player, UUID.randomUUID(), "farming:t1", "different profile"));
    }

    @Test void interruptedRewardResumesFromConfirmedInventoryWithItsOriginalToken() throws Exception {
        final String prefix = "q" + UUID.randomUUID().toString().replace("-", "") + "_";
        final RewardJournal journal = journal(prefix);
        final UUID player = UUID.randomUUID(), profile = UUID.randomUUID();
        final RewardJournal.Entry prepared = journal.prepare(player, profile, "farming:cake", "cake -> 300 coins");
        journal.advance(prepared, RewardJournal.Stage.INVENTORY_CONFIRMED);

        final RewardJournal reloaded = journal(prefix);
        final var pending = reloaded.pending(player);
        assertEquals(1, pending.size());
        assertEquals(RewardJournal.Stage.INVENTORY_CONFIRMED, pending.get(0).stage());
        assertEquals(prepared.inventoryToken(), pending.get(0).inventoryToken());
        // A late callback cannot rewind progress to prepared or create a second reward.
        assertEquals(RewardJournal.Stage.INVENTORY_CONFIRMED,
                reloaded.advance(prepared, RewardJournal.Stage.INVENTORY_CONFIRMED).stage());
        assertEquals(prepared.inventoryToken(), reloaded.prepare(player, profile, "farming:cake", "changed").inventoryToken());
    }

    @Test void uncertainInventoryStaysPendingAndCannotAutomaticallyAdvance() throws Exception {
        final String prefix = "q" + UUID.randomUUID().toString().replace("-", "") + "_";
        final RewardJournal journal = journal(prefix);
        final UUID player = UUID.randomUUID(), profile = UUID.randomUUID();
        final RewardJournal.Entry prepared = journal.prepare(player, profile, "farming:t2", "hoe + 500 coins");
        journal.markInventoryUncertain(prepared);
        final RewardJournal reloaded = journal(prefix);
        final RewardJournal.Entry pending = reloaded.pending(player).get(0);
        assertEquals(RewardJournal.Stage.INVENTORY_UNCERTAIN, pending.stage());
        assertThrows(IllegalArgumentException.class, () -> reloaded.advance(pending, RewardJournal.Stage.COMPLETED));
        assertEquals(prepared.inventoryToken(), reloaded.prepare(player, profile, "farming:t2", "retry").inventoryToken());
        assertThrows(SQLException.class, () -> reloaded.resetForTesting(player));
        assertEquals(RewardJournal.Stage.INVENTORY_UNCERTAIN, reloaded.pending(player).get(0).stage());
    }

    @Test void explicitResetAllowsNewClaimWithoutChangingAnotherPlayer() throws Exception {
        final String prefix = "q" + UUID.randomUUID().toString().replace("-", "") + "_";
        final RewardJournal journal = journal(prefix);
        final UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        final RewardJournal.Entry original = journal.prepare(player, player, "farming:t1", "first run");
        RewardJournal.Entry row = journal.advance(original, RewardJournal.Stage.INVENTORY_CONFIRMED);
        row = journal.advance(row, RewardJournal.Stage.COINS_CONFIRMED);
        journal.advance(row, RewardJournal.Stage.COMPLETED);
        journal.prepare(player, player, "farming:t2", "unfinished claim");
        final RewardJournal.Entry untouched = journal.prepare(other, other, "farming:t1", "other player");

        assertEquals(2, journal.resetForTesting(player).size());
        assertTrue(journal.pending(player).isEmpty());
        assertNull(journal.find(player, "farming:t1"));
        assertEquals(untouched, journal.find(other, "farming:t1"));
        assertTrue(journal.resetForTesting(player).isEmpty());

        final RewardJournal.Entry fresh = journal.prepare(player, player, "farming:t1", "second run");
        assertEquals(RewardJournal.Stage.PREPARED, fresh.stage());
        assertNotEquals(original.inventoryToken(), fresh.inventoryToken());
        assertEquals("second run", fresh.definition());
        assertThrows(SQLException.class, () -> journal.advance(original, RewardJournal.Stage.INVENTORY_CONFIRMED));
        assertEquals(fresh, journal.find(player, "farming:t1"), "A late callback from before reset must not advance the new claim");
    }

    private RewardJournal journal(final String prefix) throws Exception {
        final String url = System.getenv().getOrDefault("EC_SAVE_TEST_MYSQL_URL", "jdbc:sqlite:" + directory.resolve("rewards.db"));
        final RewardJournal journal = new RewardJournal(() -> DriverManager.getConnection(url), prefix);
        journal.initialize();
        return journal;
    }
}
