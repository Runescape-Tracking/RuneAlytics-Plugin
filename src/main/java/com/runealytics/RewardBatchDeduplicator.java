package com.runealytics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reward-batch fingerprint deduplication for chest / widget / raid loot.
 *
 * <h2>Problem</h2>
 * <p>Chest and reward-interface loot can be observed more than once for a
 * single completion: the widget-read path and {@code PlayerLootReceived} can
 * both fire, a reward interface can be closed and <em>reopened</em> (Barrows
 * chest, raid chest, clue casket screen), and some containers emit repeated
 * {@code ItemContainerChanged} events. The existing 2-second per-source-name
 * window only protects against the first case — a reopen seconds later
 * re-recorded the entire reward batch as a new kill.</p>
 *
 * <h2>Approach</h2>
 * <p>Every accepted chest/widget reward batch is reduced to a canonical
 * fingerprint — SHA-256 over the normalised source name plus the sorted,
 * merged {@code itemId:quantity} multiset — and remembered for
 * {@value #DEFAULT_WINDOW_MS} ms. A batch whose fingerprint was already
 * accepted inside the window is a re-read of the same reward and is dropped.
 * Batch ordering and stack splitting do not affect the fingerprint.</p>
 *
 * <h2>What this deliberately does NOT dedupe</h2>
 * <ul>
 *   <li>NPC ground-drop kills ({@code NpcLootReceived} fires once per kill and
 *       rapid identical kills are legitimate) — this class is only wired into
 *       the player/chest loot path.</li>
 *   <li>Inventory-diff sources (skilling, crate-opening) where genuinely
 *       identical consecutive batches are common — those callers bypass this
 *       check.</li>
 * </ul>
 *
 * <p>Two <em>different</em> completions of the same chest inside the window
 * would need byte-identical multi-item rewards to collide — for every wired
 * source the activity itself takes far longer than the window, so the
 * practical false-positive rate is nil.</p>
 *
 * <p>Pure Java; all methods are synchronized because loot events can arrive
 * from the client thread and scheduled executor tasks.</p>
 */
public class RewardBatchDeduplicator
{
    /** How long an accepted batch fingerprint suppresses identical batches. */
    static final long DEFAULT_WINDOW_MS = 90_000L;

    /** Hard cap on remembered fingerprints, to bound memory. */
    private static final int MAX_ENTRIES = 256;

    private final long windowMs;

    /** fingerprint → acceptance time (insertion-ordered for cheap pruning). */
    private final LinkedHashMap<String, Long> recent = new LinkedHashMap<>();

    public RewardBatchDeduplicator()
    {
        this(DEFAULT_WINDOW_MS);
    }

    /** Test seam: custom window. */
    RewardBatchDeduplicator(long windowMs)
    {
        this.windowMs = windowMs;
    }

    /**
     * Checks whether {@code items} from {@code source} duplicate a batch
     * already accepted within the window; when not, the batch is registered
     * as accepted.
     *
     * @param source normalised source name (e.g. {@code "Barrows"})
     * @param items  the reward batch
     * @param nowMs  current wall-clock time
     * @return {@code true} if this exact batch was already recorded recently
     *         and must be dropped
     */
    public synchronized boolean isDuplicate(String source, List<ItemStack> items, long nowMs)
    {
        if (items == null || items.isEmpty()) return false;

        prune(nowMs);

        String fp = fingerprint(source, items);
        Long acceptedAt = recent.get(fp);
        if (acceptedAt != null && nowMs - acceptedAt < windowMs)
        {
            return true;
        }

        recent.put(fp, nowMs);
        return false;
    }

    /**
     * Canonical fingerprint of a reward batch: SHA-256 hex over the lowercase
     * source name and the merged multiset of {@code itemId:quantity} pairs
     * sorted by item id — invariant under item ordering and stack splitting.
     */
    public static String fingerprint(String source, List<ItemStack> items)
    {
        // Merge split stacks so {a:2} equals {a:1, a:1}.
        Map<Integer, Long> merged = new TreeMap<>();
        if (items != null)
        {
            for (ItemStack item : items)
            {
                if (item == null) continue;
                merged.merge(item.getId(), (long) item.getQuantity(), Long::sum);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(source == null ? "" : source.toLowerCase()).append('|');
        for (Map.Entry<Integer, Long> e : merged.entrySet())
        {
            sb.append(e.getKey()).append(':').append(e.getValue()).append(';');
        }

        return sha256Hex(sb.toString());
    }

    /** Drops all remembered fingerprints (logout / account switch). */
    public synchronized void clear()
    {
        recent.clear();
    }

    private void prune(long nowMs)
    {
        Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, Long> e = it.next();
            if (nowMs - e.getValue() >= windowMs || recent.size() > MAX_ENTRIES)
            {
                it.remove();
            }
            else
            {
                // Insertion-ordered: the first young-enough entry means the
                // rest are younger still (times are monotonic per call site).
                break;
            }
        }
        // Enforce the cap even if the head entries are young.
        while (recent.size() > MAX_ENTRIES)
        {
            Iterator<String> keys = recent.keySet().iterator();
            keys.next();
            keys.remove();
        }
    }

    private static String sha256Hex(String input)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (java.security.NoSuchAlgorithmException e)
        {
            // SHA-256 is mandated by the JVM spec; fall back to the raw key so
            // dedupe still works (just with longer keys) rather than crashing.
            return input;
        }
    }
}
