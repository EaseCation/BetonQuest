package org.betonquest.betonquest.database;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The saver is used to save data via records into the database. Implementations should be thread save and must document
 * if they are not.
 */
public interface Saver {
    /**
     * Adds new record to the queue, where it will be saved to the database.
     *
     * @param rec Record to save
     */
    void add(Record rec);

    /** Saves one logical replacement atomically, rather than separate delete and insert operations. */
    CompletableFuture<Void> save(List<Record> records);

    /** Retries retained writes and confirms preceding changes for a profile; queueing is not durable success. */
    CompletableFuture<Void> checkpoint(String profileID);

    /**
     * Ends this saver's job, letting it save all remaining data.
     */
    void end();

    /**
     * Holds the data and the method of saving them to the database.
     */
    record Record(UpdateType type, String... args) {
        /**
         * Creates new Record, which can be saved to the database using
         * {@code Saver.add()}.
         *
         * @param type method used for saving the data
         * @param args list of Strings which will be saved to the database
         */
        public Record(final UpdateType type, final String... args) {
            this.type = type;
            this.args = Arrays.copyOf(args, args.length);
        }

        @Override
        public String[] args() {
            return Arrays.copyOf(args, args.length);
        }
    }
}
