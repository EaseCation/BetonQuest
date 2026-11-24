package org.betonquest.betonquest.database;

import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.Point;
import org.betonquest.betonquest.api.profiles.Profile;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real debit entrypoint and replays the atomic saved batch as a restored profile. */
class StageContributionDebitTest {
    private static final String CATEGORY = "skyloop_farming.farming_contribution";
    private static final String RECEIPT = "skyloop_farming.farming_contribution_spent_r2";

    private static void field(PlayerData data, String name, Object value) throws Exception {
        var field = PlayerData.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(data, value);
    }

    private static PlayerData data(int points, List<String> tags, Saver saver) throws Exception {
        var data = mock(PlayerData.class, CALLS_REAL_METHODS);
        field(data, "profile", mock(Profile.class));
        field(data, "profileID", UUID.randomUUID().toString());
        field(data, "points", new CopyOnWriteArrayList<>(List.of(new Point(CATEGORY, points))));
        field(data, "tags", new CopyOnWriteArrayList<>(tags));
        field(data, "saver", saver);
        return data;
    }

    @Test void overflowAndRestoredReceiptCannotDebitTwice() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<BetonQuest> bq = mockStatic(BetonQuest.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bq.when(BetonQuest::getInstance).thenReturn(mock(BetonQuest.class));
            var writes = new ArrayList<List<Saver.Record>>();
            var saver = mock(Saver.class);
            when(saver.save(anyList())).thenAnswer(call -> {
                writes.add(List.copyOf(call.getArgument(0)));
                return CompletableFuture.completedFuture(null);
            });
            var data = data(750, List.of(), saver);
            assertEquals(150, data.subtractPointsOnce(CATEGORY, 600, RECEIPT, false));
            assertEquals(150, data.subtractPointsOnce(CATEGORY, 600, RECEIPT, false));
            assertEquals(1, writes.size());
            assertEquals(List.of(UpdateType.REMOVE_POINTS, UpdateType.ADD_POINTS, UpdateType.ADD_TAGS),
                    writes.get(0).stream().map(Saver.Record::type).toList());
            assertEquals("150", writes.get(0).get(1).args()[2]);
            assertEquals(RECEIPT, writes.get(0).get(2).args()[1]);
            var restored = data(Integer.parseInt(writes.get(0).get(1).args()[2]),
                    List.of(writes.get(0).get(2).args()[1]), saver);
            assertEquals(150, restored.subtractPointsOnce(CATEGORY, 600, RECEIPT, false));
            assertEquals(1, writes.size());
        }
    }

    @Test void insufficientLiveBalanceIsUnchangedButLegacyCanClamp() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<BetonQuest> bq = mockStatic(BetonQuest.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bq.when(BetonQuest::getInstance).thenReturn(mock(BetonQuest.class));
            var saver = mock(Saver.class);
            var data = data(100, List.of(), saver);
            assertThrows(IllegalStateException.class, () -> data.subtractPointsOnce(CATEGORY, 600, RECEIPT, false));
            assertEquals(100, data.hasPointsFromCategory(CATEGORY));
            assertFalse(data.hasTag(RECEIPT));
            verifyNoInteractions(saver);
            assertEquals(0, data.subtractPointsOnce(CATEGORY, 500, RECEIPT, true));
            assertTrue(data.hasTag(RECEIPT));
        }
    }

    @Test void legacyT3UsesHistoricalTotalRatherThanNewCosts() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<BetonQuest> bq = mockStatic(BetonQuest.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bq.when(BetonQuest::getInstance).thenReturn(mock(BetonQuest.class));
            var data = data(2350, List.of(), mock(Saver.class));
            assertEquals(1850, data.subtractPointsOnce(CATEGORY, 500, RECEIPT, true));
            assertEquals(350, data.subtractPointsOnce(CATEGORY, 1500, RECEIPT + "_r3", true));
            assertEquals(350, data.subtractPointsOnce(CATEGORY, 500, RECEIPT, true));
        }
    }
}
