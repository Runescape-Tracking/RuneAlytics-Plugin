package com.runealytics;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Edge-case coverage for {@link LootSyncMergeService}: source-key normalization
 * (must match the PHP {@code normalizeSourceKey}) and the account guard that
 * refuses to sync without a logged-in account.
 */
public class LootSyncMergeServiceTest
{
    private final LootSyncMergeService service = new LootSyncMergeService(
            mock(CurrentPlayerIdentityService.class),
            mock(LootStorageManager.class),
            mock(LootTrackerApiClient.class),
            mock(DefaultRuneLiteLootTrackerReader.class),
            mock(ItemManager.class),
            mock(ClientThread.class));

    // ── normalizeBossKey ───────────────────────────────────────────────────

    @Test
    public void normalizeBossKey_null_returnsEmpty()
    {
        assertEquals("", LootSyncMergeService.normalizeBossKey(null));
    }

    @Test
    public void normalizeBossKey_trimsAndLowercases()
    {
        assertEquals("zulrah", LootSyncMergeService.normalizeBossKey("  Zulrah  "));
    }

    @Test
    public void normalizeBossKey_stripsApostrophes()
    {
        assertEquals("kril_tsutsaroth", LootSyncMergeService.normalizeBossKey("K'ril Tsutsaroth"));
    }

    @Test
    public void normalizeBossKey_stripsUnicodeApostrophes()
    {
        assertEquals("verzik_vitur",
                LootSyncMergeService.normalizeBossKey("Verzik\u2019 Vitur"));
    }

    @Test
    public void normalizeBossKey_replacesNonBreakingSpace()
    {
        assertEquals("dagannoth_kings",
                LootSyncMergeService.normalizeBossKey("Dagannoth\u00A0Kings"));
    }

    @Test
    public void normalizeBossKey_collapsesRepeatedWhitespaceToSingleUnderscore()
    {
        assertEquals("giant_mole", LootSyncMergeService.normalizeBossKey("Giant  Mole"));
    }

    // ── account guard ──────────────────────────────────────────────────────

    @Test
    public void performMergeForAccount_null_isBlocked()
    {
        LootSyncMergeService.MergeResult result = service.performMergeForAccount(null);
        assertFalse(result.isSuccess());
        assertTrue(result.getBlockedReason().contains("No RuneScape account"));
    }

    @Test
    public void performMergeForAccount_empty_isBlocked()
    {
        assertFalse(service.performMergeForAccount("").isSuccess());
    }

    @Test
    public void blockedResult_summaryLine_explainsBlock()
    {
        String summary = service.performMergeForAccount(null).toSummaryLine();
        assertTrue(summary.startsWith("Loot sync blocked:"));
    }
}
