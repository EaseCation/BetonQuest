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
    private final java.util.concurrent.ConcurrentHashMap<String, Write> latest = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Set<String> failedOwners = Set.of();
    private long retryAt;
    private int recoveryAttempts;
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
        final String key = currentKey(records);
        final Write write = new Write(UUID.randomUUID().toString(), Set.copyOf(owners), normalize(records), new CompletableFuture<>(), key);
        if (key != null) {
            Write previous = latest.put(key, write);
            if (previous != null && queue.remove(previous)) write.completion().whenComplete((ignored, failure) -> {
                if (failure == null) previous.completion().complete(null); else previous.completion().completeExceptionally(failure);
            });
        }
        return enqueue(write);
    }

    @Override
    public synchronized CompletableFuture<Void> checkpoint(final String profileID) {
        return enqueue(new Write("", Set.of(profileID), List.of(), new CompletableFuture<>(), null));
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
                if (write == null) {
                    if (!pending.isEmpty() && System.nanoTime() >= retryAt) {
                        Throwable failure = recover(Set.of("*"));
                        recoveryAttempts = failure == null ? 0 : recoveryAttempts + 1;
                        retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(new long[]{1,5,30}[Math.min(Math.max(0,recoveryAttempts-1),2)]);
                    }
                    continue;
                }
                if (superseded(write)) { finishFromLatest(write); continue; }
                active = write;
                pending.removeIf(previous -> previous.key() != null && previous.key().equals(write.key()));
                refreshFailedOwners();
                Throwable failure = recover(write.owners());
                if (failure == null) failure = commit(write);
                if (failure == null) {
                    if (write.key() != null) latest.remove(write.key(), write);
                    write.completion().complete(null);
                } else {
                    if (!write.records().isEmpty() && !superseded(write)) pending.add(write);
                    refreshFailedOwners();
                    retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    write.completion().completeExceptionally(failure);
                    log.error("Content save not confirmed for " + write.owners() + "; latest state retained for background retry.", failure);
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
            if (superseded(write)) { iterator.remove(); continue; }
            if (!overlaps(needed, write.owners())) continue;
            if (overlaps(blocked, write.owners())) { blocked.addAll(write.owners()); continue; }
            final Throwable error = commit(write);
            if (error == null) { iterator.remove(); if (write.key() != null) latest.remove(write.key(),write); }
            else { blocked.addAll(write.owners()); failure = error; }
        }
        refreshFailedOwners();
        return failure;
    }

    private void refreshFailedOwners() {
        Set<String> owners = new HashSet<>(); pending.forEach(write -> owners.addAll(write.owners())); failedOwners = Set.copyOf(owners);
    }

    /** Login may reuse a loaded current snapshot while its save is pending; this query performs no I/O. */
    public boolean hasPending(String profile) {
        Write current = active;
        return failedOwners.contains(profile) || failedOwners.contains("*") || current != null && overlaps(current.owners(),Set.of(profile))
                || queue.stream().anyMatch(write -> overlaps(write.owners(),Set.of(profile)));
    }

    private boolean superseded(Write write) { return write.key() != null && latest.get(write.key()) != write; }
    private void finishFromLatest(Write write) {
        Write current = latest.get(write.key());
        if(current == null) write.completion().complete(null);
        else current.completion().whenComplete((ignored,failure) -> { if(failure == null)write.completion().complete(null);else write.completion().completeExceptionally(failure); });
    }

    /** Coalesce only complete replacements of the same real object. Administrative mixed-owner batches keep order. */
    private static String currentKey(List<Record> records) {
        Record first=records.get(0); String[] args=first.args();
        String family=switch(first.type()) {
            case ADD_POINTS,REMOVE_POINTS -> "points";
            case ADD_TAGS,REMOVE_TAGS -> "tags";
            case ADD_OBJECTIVES,REMOVE_OBJECTIVES -> "objectives";
            case ADD_GLOBAL_POINTS,REMOVE_GLOBAL_POINTS -> "global-points";
            case ADD_GLOBAL_TAGS,REMOVE_GLOBAL_TAGS -> "global-tags";
            case DELETE_BACKPACK -> "backpack";
            case UPDATE_CONVERSATION -> "conversation";
            case UPDATE_PLAYER_LANGUAGE -> "language";
            case UPDATE_PROFILE_NAME -> "profile-name";
            default -> null;
        };
        if(family == null)return null;
        if(family.equals("backpack")) {
            return records.stream().allMatch(record -> record.type()==UpdateType.DELETE_BACKPACK || record.type()==UpdateType.ADD_BACKPACK)
                    && records.stream().allMatch(record -> record.args()[0].equals(args[0])) ? family+":"+args[0] : null;
        }
        if(family.equals("conversation")||family.equals("language")||family.equals("profile-name"))return records.size()==1 ? family+":"+args[1] : null;
        boolean global=family.startsWith("global");
        String owner=global?"global":args[0], object=global?args[0]:args[1];
        for(Record record:records) {
            String[] values=record.args();
            if(global ? !values[0].equals(object) : !values[0].equals(owner)||values.length<2||!values[1].equals(object)) return null;
            if(!record.type().name().endsWith(first.type().name().substring(first.type().name().indexOf('_')+1)))return null;
        }
        return family+":"+owner+":"+object;
    }

    private static List<Record> normalize(List<Record> records) {
        Record first=records.get(0);
        UpdateType remove=switch(first.type()) {
            case ADD_POINTS -> UpdateType.REMOVE_POINTS;
            case ADD_TAGS -> UpdateType.REMOVE_TAGS;
            case ADD_OBJECTIVES -> UpdateType.REMOVE_OBJECTIVES;
            case ADD_GLOBAL_POINTS -> UpdateType.REMOVE_GLOBAL_POINTS;
            case ADD_GLOBAL_TAGS -> UpdateType.REMOVE_GLOBAL_TAGS;
            default -> null;
        };
        if(records.size()!=1 || remove==null)return List.copyOf(records);
        String[] args=first.args();
        return List.of(new Record(remove, java.util.Arrays.copyOf(args,remove==UpdateType.REMOVE_GLOBAL_TAGS||remove==UpdateType.REMOVE_GLOBAL_POINTS?1:2)),first);
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

    private record Write(String id, Set<String> owners, List<Record> records, CompletableFuture<Void> completion, String key) { }
}
