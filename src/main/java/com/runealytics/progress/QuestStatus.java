package com.runealytics.progress;

/**
 * Website-facing quest state.
 *
 * <p>{@link #UNKNOWN} is never produced by RuneLite itself: {@code Quest.getState}
 * maps every script result other than 1/2 to {@code IN_PROGRESS}. It is used only
 * when RuneAlytics has no reliable observation (for example, the quest category
 * failed the post-login readiness checks).</p>
 */
public enum QuestStatus
{
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    UNKNOWN
}
