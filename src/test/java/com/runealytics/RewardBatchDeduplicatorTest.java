package com.runealytics;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers the canonical fingerprinting and windowed batch dedupe used to stop
 * reopened chest/reward interfaces from re-recording the same reward.
 */
public class RewardBatchDeduplicatorTest
{
    private static final long T0 = 1_000_000L;

    // ── Fingerprint canonicalisation ─────────────────────────────────────────

    @Test
    public void fingerprint_isOrderIndependent()
    {
        List<ItemStack> a = Arrays.asList(new ItemStack(995, 5000), new ItemStack(560, 120));
        List<ItemStack> b = Arrays.asList(new ItemStack(560, 120), new ItemStack(995, 5000));
        assertEquals(RewardBatchDeduplicator.fingerprint("Barrows", a),
                RewardBatchDeduplicator.fingerprint("Barrows", b));
    }

    @Test
    public void fingerprint_mergesSplitStacks()
    {
        List<ItemStack> split  = Arrays.asList(new ItemStack(995, 2000), new ItemStack(995, 3000));
        List<ItemStack> merged = Collections.singletonList(new ItemStack(995, 5000));
        assertEquals(RewardBatchDeduplicator.fingerprint("Barrows", split),
                RewardBatchDeduplicator.fingerprint("Barrows", merged));
    }

    @Test
    public void fingerprint_differsByQuantitySourceAndItem()
    {
        List<ItemStack> base = Collections.singletonList(new ItemStack(995, 5000));

        assertNotEquals(RewardBatchDeduplicator.fingerprint("Barrows", base),
                RewardBatchDeduplicator.fingerprint("Barrows",
                        Collections.singletonList(new ItemStack(995, 5001))));
        assertNotEquals(RewardBatchDeduplicator.fingerprint("Barrows", base),
                RewardBatchDeduplicator.fingerprint("Chambers of Xeric", base));
        assertNotEquals(RewardBatchDeduplicator.fingerprint("Barrows", base),
                RewardBatchDeduplicator.fingerprint("Barrows",
                        Collections.singletonList(new ItemStack(560, 5000))));
    }

    @Test
    public void fingerprint_sourceCaseInsensitive()
    {
        List<ItemStack> items = Collections.singletonList(new ItemStack(995, 100));
        assertEquals(RewardBatchDeduplicator.fingerprint("Barrows", items),
                RewardBatchDeduplicator.fingerprint("BARROWS", items));
    }

    // ── Windowed dedupe ───────────────────────────────────────────────────────

    @Test
    public void isDuplicate_sameBatchInsideWindowIsSuppressed()
    {
        RewardBatchDeduplicator dedupe = new RewardBatchDeduplicator();
        List<ItemStack> chest = Arrays.asList(new ItemStack(4740, 150), new ItemStack(995, 25_000));

        assertFalse("first read must be accepted", dedupe.isDuplicate("Barrows", chest, T0));
        // Interface reopened 10 seconds later — well past the 2s name lock.
        assertTrue("re-read must be suppressed", dedupe.isDuplicate("Barrows", chest, T0 + 10_000));
    }

    @Test
    public void isDuplicate_differentBatchIsAccepted()
    {
        RewardBatchDeduplicator dedupe = new RewardBatchDeduplicator();
        assertFalse(dedupe.isDuplicate("Barrows",
                Collections.singletonList(new ItemStack(4740, 150)), T0));
        assertFalse(dedupe.isDuplicate("Barrows",
                Collections.singletonList(new ItemStack(4740, 151)), T0 + 1_000));
    }

    @Test
    public void isDuplicate_sameBatchAfterWindowIsAccepted()
    {
        RewardBatchDeduplicator dedupe = new RewardBatchDeduplicator(5_000L);
        List<ItemStack> batch = Collections.singletonList(new ItemStack(995, 100));

        assertFalse(dedupe.isDuplicate("Tempoross", batch, T0));
        assertFalse("a genuinely new identical reward outside the window must count",
                dedupe.isDuplicate("Tempoross", batch, T0 + 5_001));
    }

    @Test
    public void isDuplicate_sourcesAreIndependent()
    {
        RewardBatchDeduplicator dedupe = new RewardBatchDeduplicator();
        List<ItemStack> batch = Collections.singletonList(new ItemStack(995, 100));

        assertFalse(dedupe.isDuplicate("Barrows", batch, T0));
        assertFalse("same items from another source are a different reward",
                dedupe.isDuplicate("The Gauntlet", batch, T0 + 100));
    }

    @Test
    public void isDuplicate_emptyOrNullNeverDedupes()
    {
        RewardBatchDeduplicator dedupe = new RewardBatchDeduplicator();
        assertFalse(dedupe.isDuplicate("Barrows", Collections.emptyList(), T0));
        assertFalse(dedupe.isDuplicate("Barrows", null, T0));
    }

    @Test
    public void clear_forgetsAcceptedBatches()
    {
        RewardBatchDeduplicator dedupe = new RewardBatchDeduplicator();
        List<ItemStack> batch = Collections.singletonList(new ItemStack(995, 100));

        assertFalse(dedupe.isDuplicate("Barrows", batch, T0));
        dedupe.clear();
        assertFalse("post-clear the batch is new again (account switch)",
                dedupe.isDuplicate("Barrows", batch, T0 + 100));
    }
}
