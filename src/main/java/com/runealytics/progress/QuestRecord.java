package com.runealytics.progress;

import java.util.Objects;

/** One observed quest state. Immutable. */
public final class QuestRecord
{
    /** RuneLite {@code Quest#getId()} — the stable identifier (the enum constant name may change). */
    private final int id;
    /** RuneLite enum constant name, e.g. {@code BARBARIAN_TRAINING}. Informational only. */
    private final String key;
    /** Display name from RuneLite, e.g. {@code Barbarian Training}. */
    private final String name;
    private final QuestStatus status;

    public QuestRecord(int id, String key, String name, QuestStatus status)
    {
        this.id = id;
        this.key = key;
        this.name = name;
        this.status = status;
    }

    public int getId()
    {
        return id;
    }

    public String getKey()
    {
        return key;
    }

    public String getName()
    {
        return name;
    }

    public QuestStatus getStatus()
    {
        return status;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof QuestRecord)) return false;
        QuestRecord that = (QuestRecord) o;
        return id == that.id && status == that.status
                && Objects.equals(key, that.key) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(id, key, name, status);
    }
}
