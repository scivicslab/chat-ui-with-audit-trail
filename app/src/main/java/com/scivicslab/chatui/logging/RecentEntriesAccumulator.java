package com.scivicslab.chatui.logging;

import com.scivicslab.pojoactor.core.accumulator.Accumulator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * An {@link Accumulator} that keeps only the most recent {@code capacity} entries, discarding
 * older ones. Used as a {@code MultiplexerAccumulator} target so a REST endpoint can serve
 * "recent log lines" without the buffer growing without bound ({@code
 * com.scivicslab.pojoactor.core.accumulator.BufferedAccumulator} keeps every entry forever).
 * Entries are added on the owning {@code MultiplexerAccumulatorActor}'s own thread (via {@code
 * tell}), but {@link #recent()} may be called from any thread — both are synchronized on this
 * instance.
 */
public class RecentEntriesAccumulator implements Accumulator {

    /** One accumulated entry, as passed to {@link #add}. */
    public record Entry(long time, String source, String type, String data) {}

    private final int capacity;
    private final Deque<Entry> buffer;

    public RecentEntriesAccumulator(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    @Override
    public synchronized void add(String source, String type, String data) {
        buffer.addLast(new Entry(System.currentTimeMillis(), source, type, data));
        while (buffer.size() > capacity) {
            buffer.pollFirst();
        }
    }

    /** @return the buffered entries, oldest first */
    public synchronized List<Entry> recent() {
        return new ArrayList<>(buffer);
    }

    @Override
    public synchronized String getSummary() {
        return "RecentEntriesAccumulator: " + buffer.size() + "/" + capacity + " entries";
    }

    @Override
    public synchronized int getCount() {
        return buffer.size();
    }

    @Override
    public synchronized void clear() {
        buffer.clear();
    }
}
