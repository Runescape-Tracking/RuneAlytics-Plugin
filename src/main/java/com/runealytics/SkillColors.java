package com.runealytics;

import net.runelite.api.Skill;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-skill accent colours for the XP Tracker, matching the RuneAlytics palette.
 *
 * <p>Keyed by lowercase skill name so it tolerates RuneLite {@link Skill} enum
 * differences across client versions (e.g. skills that may not exist yet simply
 * fall through to the neutral default).</p>
 */
final class SkillColors
{
    private static final Color DEFAULT = new Color(148, 163, 184); // slate

    private static final Map<String, Color> BY_NAME = new HashMap<>();

    static
    {
        put("attack",       0xef4444);
        put("strength",     0x22c55e);
        put("defence",      0x3b82f6);
        put("hitpoints",    0xf87171);
        put("ranged",       0x84cc16);
        put("prayer",       0xf59e0b);
        put("magic",        0xa855f7);
        put("cooking",      0xfb923c);
        put("woodcutting",  0x65a30d);
        put("fletching",    0x10b981);
        put("fishing",      0x0ea5e9);
        put("firemaking",   0xf97316);
        put("crafting",     0x8b5cf6);
        put("smithing",     0x6b7280);
        put("mining",       0x94a3b8);
        put("herblore",     0x16a34a);
        put("agility",      0x06b6d4);
        put("thieving",     0x7c3aed);
        put("slayer",       0xdc2626);
        put("farming",      0x4ade80);
        put("runecraft",    0x60a5fa);
        put("hunter",       0xd97706);
        put("construction", 0x92400e);
        put("sailing",      0x0891b2);
    }

    private SkillColors() {}

    private static void put(String name, int rgb)
    {
        BY_NAME.put(name, new Color(rgb));
    }

    /** Accent colour for a skill; a neutral slate for anything unmapped. */
    static Color of(Skill skill)
    {
        if (skill == null) return DEFAULT;
        Color c = BY_NAME.get(skill.getName().toLowerCase());
        return c != null ? c : DEFAULT;
    }
}
