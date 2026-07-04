package com.runealytics;

import java.awt.Color;
import net.runelite.api.Skill;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Coverage for the per-skill accent lookup: mapped skills return their palette
 * colour, and anything unmapped or null falls through to the neutral default
 * (so a new/renamed skill enum value can never NPE the panel).
 */
public class SkillColorsTest
{
    private static final Color DEFAULT = new Color(148, 163, 184);

    @Test
    public void mappedSkill_returnsPaletteColour()
    {
        assertEquals(new Color(0xef4444), SkillColors.of(Skill.ATTACK));
        assertEquals(new Color(0x3b82f6), SkillColors.of(Skill.DEFENCE));
        assertEquals(new Color(0xa855f7), SkillColors.of(Skill.MAGIC));
    }

    @Test
    public void nullSkill_returnsDefault()
    {
        assertEquals(DEFAULT, SkillColors.of(null));
    }

    @Test
    public void unmappedSkill_returnsDefault()
    {
        // OVERALL is not a trainable accent in the palette map.
        assertEquals(DEFAULT, SkillColors.of(Skill.OVERALL));
    }
}
