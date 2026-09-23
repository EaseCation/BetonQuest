package org.betonquest.betonquest.database;

import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.api.logger.BetonQuestLogger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Ordered content writes with confirmed transactions and bounded failure handling. */
public class AsyncSaver extends Thread implements Saver {
    private static final int MAX_ATTEMPTS = 3;
    private static final long SHUTDOWN_MILLIS = 5000;
    private final BetonQuestLogger log;
    private final Database database;
    private final LinkedBlockingQueue<Write> queue = new LinkedBlockingQueue<>();
    // Only the saver thread accesses this ordered backlog. A failed write is never discarded on retry.
    private final List<Write> pending = new ArrayList<>();
    private volatile Write active;
    private volatile boolean running = true;

    public AsyncSaver(final BetonQuestLogger log) {
        this(log, BetonQuest.getInstance().getDB());
    }

    AsyncSaver(final BetonQuestLogger log, final Database database) {
        super("BetonQuest-Saver");
        this.log = log;
        this.database = database;
        setDaemon(true);
    }

    @Override
    public void add(final Record record) {
        save(List.of(record));
    }

    @Override
    public synchronized CompletableFuture<Void> save(final List<Record> records) {
        if (records.isEmpty()) throw new IllegalArgumentException("Empty database write");
        final Set<String> owners = new HashSet<>();
        records.forEach(record -> owners.addAll(owners(record)));
        return enqueue(new Write(UUID.randomUUID().toString(), Set.copyOf(owners), List.copyOf(records), new CompletableFuture<>()));
    }

    @Override
    public synchronized CompletableFuture<Void> checkpoint(final String profileID) {
        return enqueue(new Write("", Set.of(profileID), List.of(), new CompletableFuture<>()));
    }

    private CompletableFuture<Void> enqueue(final Write write) {
        if (!running) return CompletableFuture.failedFuture(new IllegalStateException("Database saver is stopping"));
        queue.add(write);
        return write.completion();
    }

    @Override
    public void run() {
        while (running || !queue.isEmpty()) {
            try {
                final Write write = queue.poll(100, TimeUnit.MILLISECONDS);
                if (write == null) continue;
                active = write;
                Throwable failure = recover(write.owners());
                if (failure == null) failure = commit(write);
                if (failure == null) {
                    write.completion().complete(null);
                } else {
                    if (!write.records().isEmpty()) pending.add(write);
                    write.completion().completeExceptionally(failure);
                    log.error("Content save not confirmed for " + write.owners() + "; retained for the next write or checkpoint.", failure);
                }
                active = null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!Thread.currentThread().isInterrupted()) recover(Set.of("*"));
        if (!pending.isEmpty()) log.error("Database saver stopped with " + pending.size() + " unconfirmed writes");
        failOutstanding();
    }

    /** Retry predecessors before allowing later changes to any of their affected profiles. */
    private Throwable recover(final Set<String> requested) {
        final Set<String> needed = new HashSet<>(requested);
        for (int index = pending.size() - 1; index >= 0; index--) {
            final Write write = pending.get(index);
            if (overlaps(needed, write.owners())) needed.addAll(write.owners());
        }
        final Set<String> blocked = new HashSet<>();
        Throwable failure = null;
        final var iterator = pending.iterator();
        while (iterator.hasNext()) {
            final Write write = iterator.next();
            if (!overlaps(needed, write.owners())) continue;
            if (overlaps(blocked, write.owners())) { blocked.addAll(write.owners()); continue; }
            final Throwable error = commit(write);
            if (error == null) iterator.remove();
            else { blocked.addAll(write.owners()); failure = error; }
        }
        return failure;
    }

    private Throwable commit(final Write write) {
        if (write.records().isEmpty()) return null;
        for (int attempt = 1; ; attempt++) {
            try {
                database.saveRecords(write.id(), write.records());
                return null;
            } catch (SQLException | RuntimeException error) {
                if (attempt == MAX_ATTEMPTS || !(error instanceof SQLException sql) || !transientFailure(sql) || !running) return error;
                try { Thread.sleep(attempt * 100L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return interrupted; }
            }
        }
    }

    private void failOutstanding() {
        final IllegalStateException failure = new IllegalStateException("Content write was not confirmed before shutdown");
        final Write current = active;
        if (current != null) current.completion().completeExceptionally(failure);
        Write remaining;
        while ((remaining = queue.poll()) != null) {
            remaining.completion().completeExceptionally(failure);
        }
    }

    @Override
    public void end() {
        synchronized (this) { running = false; }
        if (Thread.currentThread() == this) return;
        try {
            join(SHUTDOWN_MILLIS);
            if (isAlive()) {
                log.error("Database saver shutdown timed out; pending writes were not confirmed");
                interrupt();
                failOutstanding();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.error("Interrupted before content saves drained", interrupted);
        }
    }

    private static boolean transientFailure(final SQLException failure) {
        final String state = failure.getSQLState();
        return failure instanceof java.sql.SQLTransientException || failure instanceof java.sql.SQLRecoverableException
                || state != null && (state.startsWith("08") || state.startsWith("40"))
                || failure.getErrorCode() == 5 || failure.getErrorCode() == 6;
    }

    private static boolean overlaps(final Set<String> left, final Set<String> right) {
        return !left.isEmpty() && !right.isEmpty()
                && (left.contains("*") || right.contains("*") || !Collections.disjoint(left, right));
    }

    private static Set<String> owners(final Record record) {
        final String[] args = record.args();
        return switch (record.type()) {
            case ADD_OBJECTIVES, ADD_TAGS, ADD_POINTS, ADD_JOURNAL, ADD_BACKPACK, ADD_PROFILE,
                    REMOVE_OBJECTIVES, REMOVE_TAGS, REMOVE_POINTS, REMOVE_JOURNAL, REMOVE_PROFILE, REMOVE_PLAYER_PROFILE,
                    DELETE_OBJECTIVES, DELETE_TAGS, DELETE_POINTS, DELETE_JOURNAL, DELETE_BACKPACK, DELETE_PLAYER,
                    INSERT_OBJECTIVE, INSERT_TAG, INSERT_POINT, INSERT_PROFILE, INSERT_ASSET_SEQUENCE_CURSOR -> Set.of(args[0]);
            case INSERT_JOURNAL, INSERT_BACKPACK, UPDATE_PROFILE_NAME, UPDATE_PLAYER_LANGUAGE, UPDATE_CONVERSATION -> Set.of(args[1]);
            case ADD_PLAYER, INSERT_PLAYER, ADD_PLAYER_PROFILE, INSERT_PLAYER_PROFILE,
                    UPDATE_PLAYERS_OBJECTIVES, UPDATE_PLAYERS_TAGS, UPDATE_PLAYERS_POINTS,
                    UPDATE_PLAYERS_JOURNAL, UPDATE_PLAYERS_BACKPACK -> new HashSet<>(List.of(args[0], args[1]));
            case ADD_GLOBAL_TAGS, ADD_GLOBAL_POINTS, REMOVE_GLOBAL_TAGS, REMOVE_GLOBAL_POINTS,
                    DELETE_GLOBAL_TAGS, DELETE_GLOBAL_POINTS, INSERT_GLOBAL_TAG, INSERT_GLOBAL_POINT,
                    RENAME_ALL_GLOBAL_POINTS -> Set.of("global");
            default -> Set.of("*");
        };
    }

    private record Write(String id, Set<String> owners, List<Record> records, CompletableFuture<Void> completion) { }
}
