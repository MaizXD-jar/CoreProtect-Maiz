package net.coreprotect.consumer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight counters describing what the write path is actually doing, so /co status can show
 * whether the database is keeping up with the server. Updates happen once per commit (not per row),
 * so this stays to plain counters - no timers, no allocation on the logging hot path.
 */
public final class ConsumerMetrics {

    private static final long RATE_WINDOW_MS = 5000;

    private static final AtomicLong totalRows = new AtomicLong();
    private static final AtomicLong totalFlushes = new AtomicLong();
    private static final AtomicLong totalFlushNanos = new AtomicLong();
    private static volatile long lastFlushNanos = 0;

    private static long rateWindowStart = System.currentTimeMillis();
    private static long rateWindowRows = 0;
    private static double rowsPerSecond = 0;

    private ConsumerMetrics() {
        throw new IllegalStateException("Utility class");
    }

    public static void recordFlush(int rows, long durationNanos) {
        if (rows > 0) {
            totalRows.addAndGet(rows);
        }
        totalFlushes.incrementAndGet();
        totalFlushNanos.addAndGet(durationNanos);
        lastFlushNanos = durationNanos;

        synchronized (ConsumerMetrics.class) {
            rateWindowRows += rows;
            long elapsed = System.currentTimeMillis() - rateWindowStart;
            if (elapsed >= RATE_WINDOW_MS) {
                rowsPerSecond = (rateWindowRows * 1000.0) / elapsed;
                rateWindowRows = 0;
                rateWindowStart = System.currentTimeMillis();
            }
        }
    }

    public static long getTotalRows() {
        return totalRows.get();
    }

    /**
     * Rows written per second, averaged over the last few seconds. Reads the still-open window when
     * no commit has closed one recently, so an idle server reports zero rather than a stale rate.
     */
    public static double getRowsPerSecond() {
        synchronized (ConsumerMetrics.class) {
            long elapsed = System.currentTimeMillis() - rateWindowStart;
            if (elapsed >= RATE_WINDOW_MS) {
                return elapsed > 0 ? (rateWindowRows * 1000.0) / elapsed : 0;
            }
            return rowsPerSecond;
        }
    }

    public static double getLastFlushMillis() {
        return lastFlushNanos / 1000000.0;
    }

    public static double getAverageFlushMillis() {
        long flushes = totalFlushes.get();
        return flushes > 0 ? (totalFlushNanos.get() / (double) flushes) / 1000000.0 : 0;
    }
}
