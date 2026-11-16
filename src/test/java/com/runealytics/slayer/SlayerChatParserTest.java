package com.runealytics.slayer;

import org.junit.Test;

import static com.runealytics.slayer.SlayerChatParser.Kind;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SlayerChatParserTest
{
    private static SlayerChatParser.Signal chat(String s)
    {
        return SlayerChatParser.parseChat(s);
    }

    private static SlayerChatParser.Signal dialog(String s)
    {
        return SlayerChatParser.parseDialog(s);
    }

    @Test
    public void assignmentDialogue()
    {
        SlayerChatParser.Signal s = dialog("Excellent, you're doing great. Your new task is to kill 125 Hellhounds.");
        assertEquals(Kind.ASSIGNED, s.kind);
        assertEquals("Hellhounds", s.name);
        assertEquals(125, s.amount);
        assertNull(s.location);
    }

    @Test
    public void konarAssignmentWithLocationAcrossLines()
    {
        SlayerChatParser.Signal s = dialog("You are to bring balance to 45 Hydras in the<br>Karuulm Slayer Dungeon.");
        assertEquals(Kind.ASSIGNED, s.kind);
        assertEquals("Hydras", s.name);
        assertEquals("Karuulm Slayer Dungeon", s.location);
        assertEquals(45, s.amount);
    }

    @Test
    public void bossAndFirstAssignments()
    {
        SlayerChatParser.Signal boss = dialog("Excellent. You're now assigned to kill the Kraken 35 times. Your reward point tally is 1,234.");
        assertEquals(Kind.ASSIGNED, boss.kind);
        assertEquals("Kraken", boss.name);
        assertEquals(35, boss.amount);

        SlayerChatParser.Signal first = dialog("We'll start you off hunting goblins, you'll need to kill 15 of them.");
        assertEquals("goblins", first.name);
        assertEquals(15, first.amount);
    }

    @Test
    public void newAssignmentGameMessage()
    {
        SlayerChatParser.Signal s = chat("You have received a new Slayer assignment from Duradel: Dust Devils (189)");
        assertEquals(Kind.ASSIGNED, s.kind);
        assertEquals("Dust Devils", s.name);
        assertEquals(189, s.amount);
    }

    @Test
    public void checkingTheTask()
    {
        SlayerChatParser.Signal gem = chat("You're assigned to kill hellhounds; only 1,042 more to go.");
        assertEquals(Kind.CURRENT, gem.kind);
        assertEquals("hellhounds", gem.name);
        assertEquals(1042, gem.amount);

        SlayerChatParser.Signal konar = chat("You're assigned to kill Hydras in the Karuulm Slayer Dungeon; only 12 more to go.");
        assertEquals("Hydras", konar.name);
        assertEquals("Karuulm Slayer Dungeon", konar.location);
        assertEquals(12, konar.amount);

        SlayerChatParser.Signal master = dialog("You're still hunting abyssal demons; you have 23 to go. Come back when you've finished.");
        assertEquals(Kind.CURRENT, master.kind);
        assertEquals(23, master.amount);
    }

    @Test
    public void completion()
    {
        assertEquals(Kind.COMPLETED, chat("You have completed your task! You killed 125 Hellhounds. You gained 32,000 xp.").kind);
        assertEquals(Kind.COMPLETED, chat("You've completed 52 tasks and received 15 points, giving you a total of 1,230; return to a Slayer master.").kind);
        assertEquals(Kind.COMPLETED, chat("<col=ef1020>You've completed 1,000 Wilderness tasks</col>").kind);
    }

    @Test
    public void cancellation()
    {
        assertEquals(Kind.CANCELLED, chat("Your task has been cancelled.").kind);
        assertEquals(Kind.CANCELLED, chat("You no longer have a slayer task as you left the fight cave.").kind);
        assertEquals(Kind.CANCELLED, chat("You no longer have a slayer task as you left the Inferno.").kind);
    }

    @Test
    public void bracelets()
    {
        assertEquals(Kind.BRACELET_SLAUGHTER,
                chat("Your bracelet of slaughter prevents your slayer count from decreasing. It has 29 charges left.").kind);
        assertEquals(Kind.BRACELET_EXPEDITIOUS,
                chat("Your expeditious bracelet helps you progress your slayer task faster. It has 14 charges left.").kind);
        SlayerChatParser.Signal combat = chat("You still need to kill 38 monsters to complete your current Slayer assignment.");
        assertEquals(Kind.REMAINING, combat.kind);
        assertEquals(38, combat.amount);
    }

    @Test
    public void unrelatedLinesAreIgnored()
    {
        assertNull(chat("A superior foe has appeared..."));
        assertNull(chat("Oh dear, you are dead!"));
        assertNull(dialog("Hello there, adventurer."));
        assertNull(chat(null));
    }
}
