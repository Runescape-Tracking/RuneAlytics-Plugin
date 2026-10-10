package com.runealytics;

/**
 * Rough overhead measurement (not a JMH benchmark): cost of the client-thread
 * offer() call and of background aggregation per observation. Run manually:
 * java -cp ... com.runealytics.TelemetryOverheadBenchmark
 */
public final class TelemetryOverheadBenchmark
{
    private TelemetryOverheadBenchmark()
    {
    }

    public static void main(String[] args)
    {
        final int n = 2_000_000;
        final String[] skills = {"construction", "hitpoints", "attack", "strength"};
        TrainingTelemetryCollector c = new TrainingTelemetryCollector(60_000, 90_000);
        long t0 = 1_800_000_000_000L;
        c.sessionStart("bench", "regular", t0);
        c.process(t0);

        // Warm up.
        for (int i = 0; i < 200_000; i++)
        {
            c.offer(skills[i & 3], i, t0 + i * 600L, false);
            if ((i & 1023) == 0)
            {
                c.process(t0 + i * 600L);
            }
        }
        c.process(t0 + 200_000 * 600L);

        long offerNs = 0;
        long processNs = 0;
        long buckets = 0;
        long base = t0 + 200_000 * 600L;
        for (int i = 0; i < n; i++)
        {
            long at = base + i * 600L; // one observation per game tick
            long s = System.nanoTime();
            c.offer(skills[i & 3], 1_000_000 + i, at, false);
            offerNs += System.nanoTime() - s;
            if ((i & 1023) == 0)
            {
                long p = System.nanoTime();
                buckets += c.process(at).size();
                processNs += System.nanoTime() - p;
            }
        }
        long p = System.nanoTime();
        buckets += c.process(base + n * 600L).size();
        processNs += System.nanoTime() - p;

        System.out.printf("offer(): %.1f ns/observation (client thread)%n", offerNs / (double) n);
        System.out.printf("process(): %.1f ns/observation (background)%n", processNs / (double) n);
        System.out.printf("buckets emitted: %d for %.1f simulated hours; dropped events: %d%n",
                buckets, n * 0.6 / 3600, c.droppedEvents());
    }
}
