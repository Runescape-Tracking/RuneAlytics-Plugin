package com.runealytics.slayer;

import com.google.gson.annotations.SerializedName;

import java.util.UUID;

/**
 * One event for {@code POST /api/plugin/slayer-task}. Field names are the wire
 * names; Gson leaves out {@code null} fields. The UUID is minted when the event
 * is created and kept across retries and restarts, so the server can drop repeats.
 */
public final class SlayerTaskEvent
{
    public static final String ASSIGNED = "assigned";
    public static final String PROGRESS = "progress";
    public static final String EXTENDED = "extended";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";

    @SerializedName("event_uuid") final String eventUuid;
    @SerializedName("type") final String type;
    /** Epoch milliseconds. */
    @SerializedName("occurred_at") final long occurredAt;
    @SerializedName("creature_id") final Integer creatureId;
    @SerializedName("creature_name") final String creatureName;
    @SerializedName("master") final String master;
    @SerializedName("location") final String location;
    @SerializedName("amount") final Integer amount;
    @SerializedName("remaining") final Integer remaining;
    @SerializedName("schema_version") final int schemaVersion = 1;

    SlayerTaskEvent(String type, long occurredAt, SlayerTaskTracker.Task task, Integer amount, Integer remaining)
    {
        this.eventUuid = UUID.randomUUID().toString();
        this.type = type;
        this.occurredAt = occurredAt;
        this.creatureId = task.creatureId > 0 ? task.creatureId : null;
        this.creatureName = cap(task.name, 80);
        this.master = cap(task.master, 40);
        this.location = cap(task.location, 80);
        this.amount = clamp(amount);
        this.remaining = clamp(remaining);
    }

    public String getEventUuid()
    {
        return eventUuid;
    }

    public String getType()
    {
        return type;
    }

    private static String cap(String s, int max)
    {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static Integer clamp(Integer v)
    {
        return v == null ? null : Math.max(0, Math.min(10_000, v));
    }
}
