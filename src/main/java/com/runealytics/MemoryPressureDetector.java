package com.runealytics;

import lombok.extern.slf4j.Slf4j;

/**
 * Detects memory pressure (JVM heap usage) to warn when the system is running
 * low on memory. Used to adjust sync behavior or warn the user.
 *
 * <p>Monitors heap usage as a percentage and categorizes pressure into levels:
 * LOW (<50%), MODERATE (50-75%), HIGH (75-90%), CRITICAL (>90%).</p>
 *
 * <p>This is a best-effort detector and should not be relied upon for hard
 * guarantees, but helps identify when memory is becoming constrained.</p>
 */
@Slf4j
public class MemoryPressureDetector
{
    public enum MemoryPressure
    {
        LOW(50),           // < 50% heap used
        MODERATE(75),      // 50-75% heap used
        HIGH(90),          // 75-90% heap used
        CRITICAL(100);     // > 90% heap used

        public final int thresholdPercent;

        MemoryPressure(int thresholdPercent)
        {
            this.thresholdPercent = thresholdPercent;
        }
    }

    private static final long PRESSURE_CHECK_INTERVAL_MS = 30_000; // Check every 30 seconds
    private volatile long lastPressureCheckMs = System.currentTimeMillis();
    private volatile MemoryPressure lastPressureLevel = MemoryPressure.LOW;
    private volatile boolean hasWarnedCritical = false;

    /**
     * @return current memory pressure level
     */
    public MemoryPressure getCurrentPressure()
    {
        return detectPressure();
    }

    /**
     * @return current heap usage as percentage (0-100)
     */
    public int getHeapUsagePercent()
    {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();

        if (maxMemory == 0) return 0;
        return (int) ((100L * usedMemory) / maxMemory);
    }

    /**
     * Detects current memory pressure and logs if significant change occurs.
     *
     * @return current memory pressure level
     */
    private MemoryPressure detectPressure()
    {
        long now = System.currentTimeMillis();
        int heapPercent = getHeapUsagePercent();

        MemoryPressure pressure;
        if (heapPercent > MemoryPressure.CRITICAL.thresholdPercent)
        {
            pressure = MemoryPressure.CRITICAL;
        }
        else if (heapPercent > MemoryPressure.HIGH.thresholdPercent)
        {
            pressure = MemoryPressure.HIGH;
        }
        else if (heapPercent > MemoryPressure.MODERATE.thresholdPercent)
        {
            pressure = MemoryPressure.MODERATE;
        }
        else
        {
            pressure = MemoryPressure.LOW;
            hasWarnedCritical = false; // Reset critical warning when pressure drops
        }

        // Check interval and only warn about changes, not every check
        if (now - lastPressureCheckMs > PRESSURE_CHECK_INTERVAL_MS)
        {
            lastPressureCheckMs = now;

            if (pressure != lastPressureLevel)
            {
                log.debug("[memory] Memory pressure changed: {} → {} ({}% heap used)",
                    lastPressureLevel, pressure, heapPercent);
                lastPressureLevel = pressure;
            }

            // Warn loudly if we hit critical
            if (pressure == MemoryPressure.CRITICAL && !hasWarnedCritical)
            {
                log.warn("[memory] CRITICAL memory pressure detected: {}% heap used. " +
                    "Sync operations may be slow or fail. Consider closing other applications.",
                    heapPercent);
                hasWarnedCritical = true;
            }
        }

        return pressure;
    }

    /**
     * @return true if memory pressure is at CRITICAL level (>90% heap used)
     */
    public boolean isCritical()
    {
        return getHeapUsagePercent() > MemoryPressure.CRITICAL.thresholdPercent;
    }

    /**
     * @return true if memory pressure is at HIGH level (75-90% heap used)
     */
    public boolean isHigh()
    {
        MemoryPressure pressure = getCurrentPressure();
        return pressure == MemoryPressure.HIGH || pressure == MemoryPressure.CRITICAL;
    }

    /**
     * Resets the critical warning flag (call when user acknowledges warning).
     */
    public void resetCriticalWarning()
    {
        hasWarnedCritical = false;
    }
}
