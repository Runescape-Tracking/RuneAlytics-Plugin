package com.runealytics;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Resolves and guards the current RuneScape account identity for all loot
 * sync operations.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>Determine the current RuneScape display name from the RuneLite client.</li>
 *   <li>Normalize the username consistently (trim, remove non-breaking spaces,
 *       lowercase, collapse internal whitespace).</li>
 *   <li>Compare the in-client name against the RuneAlytics verified/linked
 *       account so that a mismatch blocks sync before any data is written.</li>
 *   <li>Provide a stable {@link #getAccountKey()} used as the storage key for
 *       all local loot caches, API snapshot requests, and upload payloads.</li>
 *   <li>Reset when the client logs out or switches accounts.</li>
 * </ol>
 *
 * <h2>Account-mismatch guard</h2>
 * <p>If RuneAlytics is verified/linked to account "Chris123" but the RuneLite
 * client is currently logged into "IronMain", {@link #canSync()} returns
 * {@code false} and {@link #getMismatchMessage()} describes exactly which
 * accounts collide.  No loot is read, merged, or uploaded until the mismatch
 * is resolved.</p>
 *
 * <h2>Normalization rules (must match PHP side)</h2>
 * <ul>
 *   <li>Replace non-breaking spaces (U+00A0) with regular spaces.</li>
 *   <li>{@code trim()} leading/trailing whitespace.</li>
 *   <li>Lowercase the entire string.</li>
 *   <li>Collapse multiple consecutive spaces to a single space.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class CurrentPlayerIdentityService
{
    private final Client           client;
    private final RuneAlyticsState state;

    @Inject
    public CurrentPlayerIdentityService(Client client, RuneAlyticsState state)
    {
        this.client = client;
        this.state  = state;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the normalized display name of the currently logged-in
     * RuneScape account, or {@code null} if no player is available.
     */
    public String getCurrentNormalizedUsername()
    {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) return null;

        String name = localPlayer.getName();
        if (name == null || name.isEmpty()) return null;

        return normalizeUsername(name);
    }

    /**
     * Returns the account key to be used for all per-account loot storage,
     * snapshot requests, and sync payloads.
     *
     * <p>This is the normalized username of the currently logged-in player.
     * Returns {@code null} if the player is not available (not logged in).</p>
     */
    public String getAccountKey()
    {
        return getCurrentNormalizedUsername();
    }

    /**
     * Returns {@code true} when loot sync is safe to proceed.
     *
     * <p>Sync is blocked when:</p>
     * <ul>
     *   <li>No player is logged in ({@code localPlayer == null}).</li>
     *   <li>The in-client player name is unavailable or empty.</li>
     *   <li>RuneAlytics is linked to a different account than the one
     *       currently logged in.</li>
     * </ul>
     */
    public boolean canSync()
    {
        String currentName = getCurrentNormalizedUsername();
        if (currentName == null)
        {
            log.debug("[identity] canSync=false: no local player / not logged in");
            return false;
        }

        String linkedName = getLinkedNormalizedUsername();
        if (linkedName != null && !linkedName.isEmpty() && !linkedName.equals(currentName))
        {
            log.debug("[identity] canSync=false: account mismatch — linked='{}' current='{}'",
                    linkedName, currentName);
            return false;
        }

        return true;
    }

    /**
     * Returns a human-readable explanation of why sync is blocked, or
     * {@code null} if sync is allowed.
     */
    public String getMismatchMessage()
    {
        String currentName = getCurrentNormalizedUsername();
        if (currentName == null)
        {
            return "Log into a RuneScape account before syncing loot.";
        }

        String linkedName = getLinkedNormalizedUsername();
        if (linkedName != null && !linkedName.isEmpty() && !linkedName.equals(currentName))
        {
            return "RuneAlytics is linked to \u2018" + linkedName
                    + "\u2019, but RuneLite is logged in as \u2018" + currentName
                    + "\u2019. Switch accounts or re-verify the plugin to sync loot.";
        }

        return null;
    }

    // ── Static helper (also used by LootSyncMergeService) ────────────────────

    /**
     * Normalizes a RuneScape display name to a stable, comparable key.
     *
     * <p>Rules (must match the PHP server-side normalization):</p>
     * <ol>
     *   <li>Replace non-breaking spaces (U+00A0) with regular spaces.</li>
     *   <li>Trim leading/trailing whitespace.</li>
     *   <li>Lowercase the entire string.</li>
     *   <li>Collapse multiple consecutive spaces to a single space.</li>
     * </ol>
     *
     * @param  name  raw display name, may be {@code null}
     * @return normalized name, or {@code null} if the input is {@code null}/empty
     */
    public static String normalizeUsername(String name)
    {
        if (name == null) return null;

        // Replace non-breaking space (U+00A0) with regular space.
        name = name.replace('\u00A0', ' ');

        name = name.trim();

        if (name.isEmpty()) return null;

        name = name.toLowerCase();

        // Collapse multiple spaces to one (e.g. "Iron  Man" → "iron man").
        name = name.replaceAll("\\s+", " ");

        return name;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns the normalized username of the RuneAlytics verified/linked
     * account, sourced from {@link RuneAlyticsState#getVerifiedUsername()}.
     * Returns {@code null} when no account is linked.
     */
    private String getLinkedNormalizedUsername()
    {
        return normalizeUsername(state.getVerifiedUsername());
    }
}
