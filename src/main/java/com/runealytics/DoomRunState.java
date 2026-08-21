package com.runealytics;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Tracks the state of a Doom of Mokhaiotl delve run.
 *
 * <p>Doom is a progressive encounter where the player can choose to continue
 * deeper into the delve for better rewards or claim their current reward. This
 * state machine ensures:</p>
 * <ul>
 *   <li>Each floor progression is tracked (floor 1, 2, 3, etc.)</li>
 *   <li>Final claimed reward is recorded only once</li>
 *   <li>Floor progression is not counted as independent boss kills</li>
 *   <li>Highest floor completed is tracked separately from loot</li>
 *   <li>Failures/deaths invalidate the run</li>
 *   <li>Logout invalidates pending progress</li>
 * </ul>
 *
 * <h2>State Flow</h2>
 * <pre>
 *   IDLE
 *     ↓ (NPC killed in Doom region)
 *   PROGRESSION_ACTIVE
 *     ↓ (floor N completed)
 *   FLOOR_N_COMPLETED
 *     ↓ (player claims reward)
 *   REWARD_CLAIMED
 *     ↓ (loot recorded)
 *   COMPLETED
 *
 *   OR:
 *     ↓ (player dies or logs out before claiming)
 *   ABANDONED
 * </pre>
 */
@Slf4j
@Getter
public class DoomRunState
{
    public enum State
    {
        IDLE,
        PROGRESSION_ACTIVE,
        REWARD_CLAIMED,
        COMPLETED,
        ABANDONED
    }

    /** Unique identifier for this run (UUID). */
    private final String runId;

    /** RuneScape account this run belongs to (for isolation). */
    private final String account;

    /** State of this run. */
    private State state = State.IDLE;

    /** Highest floor completed in this run (0 = not started, 1-5+ = floor number). */
    private int highestFloorCompleted = 0;

    /** Current in-progress floor (during PROGRESSION_ACTIVE). */
    private int currentFloor = 0;

    /** World the run started on. */
    private int startWorld = 0;

    /** Time run started (epoch ms). */
    private long startedAt = 0;

    /** Time the run was last updated (epoch ms). */
    private long lastActivityAt = 0;

    /** Time reward was claimed (epoch ms). */
    private long claimedAt = 0;

    /** Items obtained from the final reward. */
    private List<ItemStack> claimedItems = Collections.emptyList();

    /** Floor progression history (useful for analytics). */
    private final List<FloorProgression> progressionHistory = new ArrayList<>();

    /** Reason run was abandoned (if applicable). */
    private String abandonReason = null;

    /**
     * Timeout after which an active run is automatically considered abandoned
     * if no activity occurs (2 minutes, matching typical Doom completion time).
     */
    private static final long INACTIVITY_TIMEOUT_MS = 2 * 60 * 1_000L;

    // ─────────────────────────────────────────────────────────────────────────

    public DoomRunState(String account, int world)
    {
        this.runId = UUID.randomUUID().toString();
        this.account = account;
        this.startWorld = world;
        this.startedAt = System.currentTimeMillis();
        this.lastActivityAt = startedAt;
    }

    /**
     * Mark the start of active progression (first NPC killed in Doom).
     */
    public void startProgression()
    {
        if (state != State.IDLE)
        {
            log.debug("DoomRun {}: Cannot start progression from state {}", runId, state);
            return;
        }
        state = State.PROGRESSION_ACTIVE;
        currentFloor = 1;
        lastActivityAt = System.currentTimeMillis();
        log.debug("DoomRun {}: Progression started, account={}", runId, account);
    }

    /**
     * Record completion of a floor with potential reward widget visibility.
     */
    public void completeFloor(int floorNumber)
    {
        if (state != State.PROGRESSION_ACTIVE)
        {
            log.debug("DoomRun {}: Cannot complete floor {} from state {}", runId, floorNumber, state);
            return;
        }

        this.currentFloor = floorNumber;
        this.highestFloorCompleted = Math.max(highestFloorCompleted, floorNumber);
        this.lastActivityAt = System.currentTimeMillis();

        progressionHistory.add(new FloorProgression(floorNumber, lastActivityAt));
        log.debug("DoomRun {}: Floor {} completed (highest={})", runId, floorNumber, highestFloorCompleted);
    }

    /**
     * Mark that the player has claimed their reward.
     */
    public void claimReward(List<ItemStack> items)
    {
        if (state != State.PROGRESSION_ACTIVE)
        {
            log.debug("DoomRun {}: Cannot claim reward from state {}", runId, state);
            return;
        }

        state = State.REWARD_CLAIMED;
        claimedAt = System.currentTimeMillis();
        claimedItems = new ArrayList<>(items);
        lastActivityAt = claimedAt;

        log.debug("DoomRun {}: Reward claimed with {} items, highest floor={}", runId, items.size(), highestFloorCompleted);
    }

    /**
     * Mark the run as successfully completed after loot has been recorded.
     */
    public void complete()
    {
        if (state != State.REWARD_CLAIMED)
        {
            log.debug("DoomRun {}: Cannot complete from state {}", runId, state);
            return;
        }

        state = State.COMPLETED;
        log.debug("DoomRun {}: Run completed, final floor={}, items={}", runId, highestFloorCompleted, claimedItems.size());
    }

    /**
     * Abandon the run due to death, logout, world hop, etc.
     */
    public void abandon(String reason)
    {
        if (state == State.COMPLETED || state == State.ABANDONED)
        {
            return;
        }

        state = State.ABANDONED;
        abandonReason = reason;
        lastActivityAt = System.currentTimeMillis();
        log.debug("DoomRun {}: Abandoned - {}", runId, reason);
    }

    /**
     * Returns true if this run has been inactive for too long.
     * Inactive runs are automatically abandoned when checked.
     */
    public boolean isStale()
    {
        if (state == State.COMPLETED || state == State.ABANDONED)
            return true;

        return System.currentTimeMillis() - lastActivityAt > INACTIVITY_TIMEOUT_MS;
    }

    /**
     * Returns true if this run is ready to record as a kill.
     * (Reward claimed and items populated)
     */
    public boolean isReadyToRecord()
    {
        return state == State.REWARD_CLAIMED && !claimedItems.isEmpty();
    }

    /**
     * Record of a single floor progression.
     */
    @Getter
    public static class FloorProgression
    {
        private final int floor;
        private final long completedAt;

        public FloorProgression(int floor, long completedAt)
        {
            this.floor = floor;
            this.completedAt = completedAt;
        }
    }

    @Override
    public String toString()
    {
        return String.format("DoomRun{id=%s, account=%s, state=%s, floor=%d, highestFloor=%d}",
                runId, account, state, currentFloor, highestFloorCompleted);
    }
}
