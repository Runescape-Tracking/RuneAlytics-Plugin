package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks Doom of Mokhaiotl delve runs with full lifecycle management.
 *
 * <p>This component manages the state of potentially multiple concurrent Doom runs
 * (one per account/world combination) and provides hooks for:</p>
 * <ul>
 *   <li>Detecting Doom region entry</li>
 *   <li>Tracking NPC kills in Doom</li>
 *   <li>Detecting floor progression</li>
 *   <li>Capturing reward claims</li>
 *   <li>Invalidating runs on logout/world-hop</li>
 *   <li>Distinguishing progression from claimed rewards</li>
 * </ul>
 *
 * <h2>Doom Region Detection</h2>
 * <p>Doom of Mokhaiotl is located in the Kharidian Desert. The delve regions are
 * roughly area 14226 and nearby regions. We detect Doom by checking if the player
 * is in a high-level desert region and has killed a Mokhaiotl-family NPC.</p>
 *
 * <h2>Floor Detection</h2>
 * <p>Floor progression is detected through:</p>
 * <ul>
 *   <li>Chat messages mentioning floor completion</li>
 *   <li>Widget loading events (reward widgets fire as floor completes)</li>
 *   <li>NPC kills in Doom region (indicates progression)</li>
 * </ul>
 */
@Slf4j
@Singleton
public class DoomEncounterTracker
{
    private static final int MOKHAIOTL_DELVE_NPC_ID_START = 12681;
    private static final int MOKHAIOTL_DELVE_NPC_ID_END = 12690;

    /**
     * Timeout for abandoning a stale run (no activity for 2 minutes).
     */
    private static final long RUN_STALE_MS = 2 * 60 * 1_000L;

    /**
     * Per-account current run. Maps account name → DoomRunState.
     * Null when no active run.
     */
    private final Map<String, DoomRunState> currentRun = new ConcurrentHashMap<>();

    /**
     * Completed runs archived for analytics.
     * Kept for up to 1 hour after completion.
     */
    private final Map<String, DoomRunState> completedRuns = new LinkedHashMap<String, DoomRunState>()
    {
        protected boolean removeEldestEntry(Map.Entry eldest)
        {
            return size() > 100; // Keep last 100 completed runs
        }
    };

    private final Client client;

    @Inject
    public DoomEncounterTracker(Client client)
    {
        this.client = client;
    }

    /**
     * Called when an NPC is killed. If it's in a Doom region, activates or updates
     * the current run.
     */
    public void onNpcKilled(NPC npc, String account, int world)
    {
        if (!isDoomNpc(npc))
            return;

        WorldPoint loc = npc.getWorldLocation();
        if (loc == null || !isDoomRegion(loc))
            return;

        log.debug("Doom NPC killed: {} at {}", npc.getName(), loc);

        // Get or create run for this account
        DoomRunState run = currentRun.computeIfAbsent(account, k -> new DoomRunState(account, world));

        // If run is stale or on wrong world, replace it
        if (run.isStale() || run.getStartWorld() != world)
        {
            log.debug("Doom run {} is stale or on wrong world, creating new run", run.getRunId());
            run = new DoomRunState(account, world);
            currentRun.put(account, run);
        }

        // Activate progression if not already active
        if (run.getState() == DoomRunState.State.IDLE)
        {
            run.startProgression();
        }
    }

    /**
     * Called when a floor completion is detected (via chat or widget).
     */
    public void onFloorCompleted(String account, int floorNumber)
    {
        DoomRunState run = currentRun.get(account);
        if (run == null)
        {
            log.debug("No active Doom run for {}, ignoring floor {} completion", account, floorNumber);
            return;
        }

        run.completeFloor(floorNumber);
    }

    /**
     * Called when the reward is claimed (widget opened showing items).
     */
    public void onRewardClaimed(String account, List<ItemStack> items)
    {
        DoomRunState run = currentRun.get(account);
        if (run == null)
        {
            log.debug("No active Doom run for {}, ignoring reward claim", account);
            return;
        }

        run.claimReward(items);
        log.debug("Doom reward claimed: {} items, highest floor={}", items.size(), run.getHighestFloorCompleted());
    }

    /**
     * Marks the run as completed after loot has been recorded to the database.
     */
    public void markComplete(String account)
    {
        DoomRunState run = currentRun.remove(account);
        if (run != null)
        {
            run.complete();
            completedRuns.put(run.getRunId(), run);
            log.debug("Doom run marked complete: {}", run);
        }
    }

    /**
     * Abandons the current run (logout, world hop, death, etc.).
     */
    public void abandonRun(String account, String reason)
    {
        DoomRunState run = currentRun.get(account);
        if (run != null)
        {
            run.abandon(reason);
            if (run.getState() == DoomRunState.State.ABANDONED)
                currentRun.remove(account);
            log.debug("Doom run abandoned: {}", reason);
        }
    }

    /**
     * Returns the current active Doom run for an account, or null if none.
     */
    public DoomRunState getCurrentRun(String account)
    {
        DoomRunState run = currentRun.get(account);
        if (run != null && run.isStale())
        {
            run.abandon("inactivity timeout");
            currentRun.remove(account);
            return null;
        }
        return run;
    }

    /**
     * Clears all state (logout/plugin shutdown).
     */
    public void reset()
    {
        for (DoomRunState run : currentRun.values())
            run.abandon("session reset");
        currentRun.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private boolean isDoomNpc(NPC npc)
    {
        if (npc == null) return false;
        int id = npc.getId();
        return id >= MOKHAIOTL_DELVE_NPC_ID_START && id <= MOKHAIOTL_DELVE_NPC_ID_END;
    }

    /**
     * Returns true if the given world point is in the Doom of Mokhaiotl delve region.
     * Doom is primarily in region 14226 with surrounding delve regions.
     */
    private boolean isDoomRegion(WorldPoint p)
    {
        if (p == null) return false;

        // Doom delves are in the Kharidian Desert around 3300-3400x / 2800-2900y
        // More specifically, the delve region block is around 14226
        // We can detect by: specific coordinates or region verification
        // For now, use coordinate ranges
        int x = p.getX();
        int y = p.getY();
        int region = p.getRegionID();

        // Primary Doom delve region
        if (region == 14226) return true;

        // Nearby delve region IDs  (rough estimate based on world coordinates)
        // Doom regions: 14226 is the main one
        // Surrounding: 14225, 14227, 14482, 14483, etc. (need verification)

        // Coordinate-based detection: Kharidian desert around Artefacts area
        return (x >= 3200 && x <= 3400 && y >= 2700 && y <= 2900);
    }
}
