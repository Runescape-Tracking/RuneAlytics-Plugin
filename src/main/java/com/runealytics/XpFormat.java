package com.runealytics;

/**
 * Small formatting helpers for the RuneAlytics XP Tracker.
 *
 * <p>Kept dependency-free (no RuneLite / Swing types) so it can be unit-tested
 * and reused from every XP panel component.</p>
 *
 * <ul>
 *   <li>{@link #comma(long)}       — {@code 1,250}</li>
 *   <li>{@link #compact(long)}     — {@code 42.1k} / {@code 1.4m}</li>
 *   <li>{@link #compactUpper(long)}— {@code 42.1K} / {@code 1.40M} (UI accent style)</li>
 *   <li>{@link #duration(long)}    — {@code 02:14:33}</li>
 *   <li>{@link #timeToLevel(long)} — {@code 3h 24m}</li>
 * </ul>
 */
final class XpFormat
{
    private XpFormat() {}

    /** {@code 1250 -> "1,250"} */
    static String comma(long n)
    {
        return String.format("%,d", n);
    }

    /**
     * Lower-case compact form: {@code 1250 -> "1.3k"}, {@code 1_400_000 -> "1.4m"}.
     * Mirrors RuneLite's own short XP style.
     */
    static String compact(long n)
    {
        long a = Math.abs(n);
        if (a < 1_000)         return Long.toString(n);
        if (a < 1_000_000)     return trim(n / 1_000.0) + "k";
        if (a < 1_000_000_000) return trim(n / 1_000_000.0) + "m";
        return trim(n / 1_000_000_000.0) + "b";
    }

    /**
     * Upper-case compact form used for the value accents in the panel mockups:
     * {@code 873_000 -> "873K"}, {@code 8_860_000 -> "8.86M"}, {@code 12_450_000 -> "12.45M"}.
     *
     * <p>Thousands render as a whole-number {@code K}; millions/billions render
     * with two decimals so large session totals stay readable.</p>
     */
    static String compactUpper(long n)
    {
        long a = Math.abs(n);
        if (a < 1_000)         return Long.toString(n);
        if (a < 1_000_000)     return (n / 1_000) + "K";
        if (a < 1_000_000_000) return String.format("%.2fM", n / 1_000_000.0);
        return String.format("%.2fB", n / 1_000_000_000.0);
    }

    /** Drops a trailing {@code .0} so {@code 42.0 -> "42"} but {@code 42.1 -> "42.1"}. */
    private static String trim(double v)
    {
        if (v == Math.floor(v) && !Double.isInfinite(v))
        {
            return Long.toString((long) v);
        }
        return String.format("%.1f", v);
    }

    /** Milliseconds -> {@code "02:14:33"} (always HH:MM:SS, hours uncapped). */
    static String duration(long ms)
    {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1_000L;
        long h = totalSec / 3_600L;
        long m = (totalSec % 3_600L) / 60L;
        long s = totalSec % 60L;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    /**
     * Milliseconds -> human "time to level" string: {@code "3h 24m"}, {@code "24m 10s"},
     * {@code "45s"}. Returns {@code "—"} (em dash) for unknown / non-positive input.
     */
    static String timeToLevel(long ms)
    {
        if (ms <= 0) return "—";
        long totalSec = ms / 1_000L;
        long h = totalSec / 3_600L;
        long m = (totalSec % 3_600L) / 60L;
        long s = totalSec % 60L;

        if (h >= 100) return ">99h";
        if (h > 0)    return h + "h " + m + "m";
        if (m > 0)    return m + "m " + s + "s";
        return s + "s";
    }

    /**
     * Short "time ago" label for recent XP drops: {@code "just now"},
     * {@code "3 min ago"}, {@code "2h ago"}.
     */
    static String ago(long elapsedMs)
    {
        if (elapsedMs < 0) elapsedMs = 0;
        long sec = elapsedMs / 1_000L;
        if (sec < 5)   return "just now";
        if (sec < 60)  return sec + "s ago";
        long min = sec / 60L;
        if (min < 60)  return min + " min ago";
        long h = min / 60L;
        return h + "h ago";
    }
}
