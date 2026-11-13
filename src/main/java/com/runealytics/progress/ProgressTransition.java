package com.runealytics.progress;

import java.util.Objects;

/**
 * A change RuneAlytics actually saw happen: the value was {@code from} in an earlier
 * observation of the same account and is now {@code to}. Immutable.
 *
 * <p>Transitions are never created for a first-ever observation, so historical
 * progress does not appear as "just completed".</p>
 */
public final class ProgressTransition
{
    /** Seen while the plugin was watching (within ~one scan of the change). */
    public static final String DETECTION_LIVE = "live";
    /**
     * Found on the first scan after login / plugin start: it happened while the plugin
     * was not watching, sometime between {@code previousObservedAtMs} and {@code observedAtMs}.
     */
    public static final String DETECTION_CATCH_UP = "catch_up";

    public static final String CATEGORY_QUEST = "quest";
    public static final String CATEGORY_DIARY = "diary";
    public static final String CATEGORY_CA_TASK = "combat_achievement_task";
    public static final String CATEGORY_CA_TIER = "combat_achievement_tier";

    /** Client-generated UUID so the website can create each activity event exactly once. */
    private final String id;
    private final String category;
    private final String key;
    private final String name;
    private final String field;
    private final String from;
    private final String to;
    private final long observedAtMs;
    private final String detection;
    private final Long previousObservedAtMs;

    public ProgressTransition(String id, String category, String key, String name, String field,
                              String from, String to, long observedAtMs, String detection,
                              Long previousObservedAtMs)
    {
        this.id = id;
        this.category = category;
        this.key = key;
        this.name = name;
        this.field = field;
        this.from = from;
        this.to = to;
        this.observedAtMs = observedAtMs;
        this.detection = detection;
        this.previousObservedAtMs = previousObservedAtMs;
    }

    public String getId()
    {
        return id;
    }

    public String getCategory()
    {
        return category;
    }

    public String getKey()
    {
        return key;
    }

    public String getName()
    {
        return name;
    }

    public String getField()
    {
        return field;
    }

    public String getFrom()
    {
        return from;
    }

    public String getTo()
    {
        return to;
    }

    public long getObservedAtMs()
    {
        return observedAtMs;
    }

    public String getDetection()
    {
        return detection;
    }

    public Long getPreviousObservedAtMs()
    {
        return previousObservedAtMs;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof ProgressTransition)) return false;
        return Objects.equals(id, ((ProgressTransition) o).id);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(id);
    }
}
