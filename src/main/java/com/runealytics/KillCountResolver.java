package com.runealytics;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for <b>authoritative game kill counts</b> parsed from
 * chat, replacing the ad-hoc (Whisperer-only) KC handling.
 *
 * <h2>Why</h2>
 * <p>The game announces the real KC in chat for nearly every boss, raid,
 * Barrows, the Gauntlet, Wintertodt and Tempoross, in several distinct
 * grammatical forms. Historically the plugin parsed only
 * {@code "Your X kill count is: N"} — and then discarded the value for every
 * boss except the Whisperer, so almost all kills were numbered by the local
 * counter even when the game had just told us the true KC.</p>
 *
 * <h2>Message forms recognised</h2>
 * <ul>
 *   <li>{@code Your Vorkath kill count is: 100.}</li>
 *   <li>{@code Your Barrows chest count is: 279.}</li>
 *   <li>{@code Your Gauntlet completion count is: 12.}</li>
 *   <li>{@code Your herbiboar harvest count is: 50.}</li>
 *   <li>{@code Your completed Chambers of Xeric count is: 57.}</li>
 *   <li>{@code Your subdued Wintertodt count is: 245.}</li>
 *   <li>Fallback: {@code Your <anything> count is: N.} (e.g. Lunar Chest)</li>
 * </ul>
 *
 * <h2>Correlation model</h2>
 * <p>Each parsed message becomes a per-boss <em>pending observation</em>.
 * The kill write path ({@code LootTrackerManager.recordKill}) consumes the
 * observation when it records a kill for the same normalised boss within
 * {@value #CONSUME_WINDOW_MS} ms — regardless of whether the chat message or
 * the loot event arrived first. An observation is consumed <b>exactly once</b>
 * so multiple KC signals can never increment a kill twice.</p>
 *
 * <h2>Safety rules</h2>
 * <ul>
 *   <li>Monotonic per boss: an observation lower than the highest KC already
 *       seen this session is rejected (stale / out-of-order message).</li>
 *   <li>State is session-scoped: {@link #clear()} must be called on logout or
 *       account switch so one account's KC can never bleed into another.</li>
 *   <li>Pure Java, no client dependencies — trivially unit-testable and
 *       incapable of crashing an event handler.</li>
 * </ul>
 */
public class KillCountResolver
{
    /**
     * How long a parsed KC stays claimable by a kill record for the same boss.
     *
     * <p>Ground-drop bosses record within ticks of the message, but chest and
     * raid sources announce the KC on completion while the reward is only
     * read when the player opens the chest — potentially a minute or more
     * later. Two minutes comfortably covers that walk while staying far below
     * the fastest possible repeat of any chest/raid source, so an unconsumed
     * observation can never be mistaken for the <em>next</em> completion's KC
     * (and even then, the raise-only guards make a stale value harmless).</p>
     */
    static final long CONSUME_WINDOW_MS = 120_000L;

    /** Strips RuneScape chat markup ({@code <col=...>}, {@code <br>}, ...). */
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]*>");

    /**
     * Ordered patterns; the first match wins. Group 1 = boss name,
     * group 2 = count (may contain thousands separators).
     */
    private static final Pattern[] KC_PATTERNS = {
            // "Your completed Chambers of Xeric count is: 57"
            Pattern.compile("your completed (.+?) count is:?\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE),
            // "Your subdued Wintertodt count is: 245"
            Pattern.compile("your subdued (.+?) count is:?\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE),
            // "Your Vorkath kill count is: 100" / "Your Barrows chest count is: 279"
            // / "Your Gauntlet completion count is: 12" / harvest & success variants
            Pattern.compile(
                    "your (.+?) (?:kill count|chest count|completion count|success count|harvest count) is:?\\s*([\\d,]+)",
                    Pattern.CASE_INSENSITIVE),
            // Fallback for counts with no recognised suffix, e.g.
            // "Your Lunar Chest count is: 11"
            Pattern.compile("your (.+?) count is:?\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE),
    };

    /** One authoritative-KC sighting parsed from chat. */
    public static final class KcObservation
    {
        private final String bossName;
        private final int    killCount;
        private final long   timestampMs;

        KcObservation(String bossName, int killCount, long timestampMs)
        {
            this.bossName    = bossName;
            this.killCount   = killCount;
            this.timestampMs = timestampMs;
        }

        /** Normalised boss / source name (already passed through the name normaliser). */
        public String getBossName()    { return bossName; }
        /** The authoritative in-game kill count from the message. */
        public int    getKillCount()   { return killCount; }
        /** Wall-clock time the message was observed. */
        public long   getTimestampMs() { return timestampMs; }
    }

    /** Canonicalises raw chat boss names to the plugin's storage keys. */
    private final UnaryOperator<String> nameNormalizer;

    /** Pending, not-yet-consumed observations keyed by normalised boss name. */
    private final Map<String, KcObservation> pending = new HashMap<>();

    /**
     * Highest KC seen per boss this session — used to reject stale or
     * out-of-order KC messages. Cleared with {@link #clear()}.
     */
    private final Map<String, Integer> highestSeen = new HashMap<>();

    /**
     * @param nameNormalizer maps a raw boss name from chat to the canonical
     *                       storage key (production passes
     *                       {@code BossNames::normalize})
     */
    public KillCountResolver(UnaryOperator<String> nameNormalizer)
    {
        this.nameNormalizer = nameNormalizer;
    }

    /**
     * Parses a chat message and, when it carries a KC, records it as the
     * pending observation for that boss.
     *
     * @param rawMessage chat message, may contain colour tags
     * @param nowMs      current wall-clock time
     * @return the recorded observation, or {@code null} when the message
     *         carries no KC or the KC regressed below the highest seen value
     */
    public synchronized KcObservation observe(String rawMessage, long nowMs)
    {
        if (rawMessage == null || rawMessage.isEmpty()) return null;

        String stripped = TAG_PATTERN.matcher(rawMessage).replaceAll("").trim();

        for (Pattern p : KC_PATTERNS)
        {
            Matcher m = p.matcher(stripped);
            if (!m.find()) continue;

            String rawName = m.group(1).trim();
            int kc;
            try
            {
                kc = Integer.parseInt(m.group(2).replace(",", ""));
            }
            catch (NumberFormatException nfe)
            {
                return null; // count too large / malformed — ignore safely
            }
            if (kc <= 0) return null;

            String key = normalize(rawName);
            if (key == null || key.isEmpty()) return null;

            Integer highest = highestSeen.get(key);
            if (highest != null && kc < highest)
            {
                // Regression: stale message replay or cross-variant noise.
                return null;
            }

            KcObservation obs = new KcObservation(key, kc, nowMs);
            pending.put(key, obs);
            highestSeen.put(key, kc);
            return obs;
        }
        return null;
    }

    /**
     * Claims (and removes) the pending KC for {@code bossName} when one was
     * observed within {@link #CONSUME_WINDOW_MS}. Expired observations are
     * dropped. Consuming is exactly-once: a second call returns {@code null},
     * which is what guarantees KC can never be applied to two kills.
     *
     * @return the authoritative game KC, or {@code null} when none is pending
     */
    public synchronized Integer consume(String bossName, long nowMs)
    {
        String key = normalize(bossName);
        if (key == null) return null;

        KcObservation obs = pending.remove(key);
        if (obs == null) return null;

        if (nowMs - obs.getTimestampMs() > CONSUME_WINDOW_MS)
        {
            return null; // too old to be this kill's KC — discarded
        }
        return obs.getKillCount();
    }

    /** @return the highest KC seen for {@code bossName} this session, or {@code null}. */
    public synchronized Integer getHighestSeen(String bossName)
    {
        String key = normalize(bossName);
        return key == null ? null : highestSeen.get(key);
    }

    /**
     * Drops all session state. MUST be called on logout / account switch so
     * observations and monotonic floors never leak across accounts.
     */
    public synchronized void clear()
    {
        pending.clear();
        highestSeen.clear();
    }

    private String normalize(String raw)
    {
        if (raw == null) return null;
        String normalized = nameNormalizer.apply(raw);
        return normalized == null ? null : normalized;
    }
}
