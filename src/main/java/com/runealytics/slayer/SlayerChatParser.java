package com.runealytics.slayer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Slayer game messages and Slayer-master dialogue. The varps are the
 * source of truth for the task; these lines tell the event types apart
 * (completed vs cancelled) and supply what the varps can't (the master's words,
 * Konar's location as written, the assigned size).
 *
 * <p>Message formats follow RuneLite's Slayer plugin (which parsed them before
 * it moved to varps) and the game's current wording.</p>
 */
public final class SlayerChatParser
{
    /** What a line means for the task. */
    public enum Kind
    {
        /** A master gave a new task (dialogue, or the "new Slayer assignment" game message). */
        ASSIGNED,
        /** The current task and how many are left (gem / helm / master "still assigned"). */
        CURRENT,
        /** The task was finished. */
        COMPLETED,
        /** The task was skipped, blocked, reset or lost (Jad / Zuk). */
        CANCELLED,
        /** A kill did not count down (bracelet of slaughter): not progress. */
        BRACELET_SLAUGHTER,
        /** A kill counted twice (expeditious bracelet): still ordinary progress. */
        BRACELET_EXPEDITIOUS,
        /** Remaining count only (combat bracelet check). */
        REMAINING
    }

    /** One parsed line. {@code amount} is -1 when the line has no count. */
    public static final class Signal
    {
        public final Kind kind;
        public final String name;
        public final String location;
        public final int amount;

        Signal(Kind kind, String name, String location, int amount)
        {
            this.kind = kind;
            this.name = name;
            this.location = location;
            this.amount = amount;
        }
    }

    // Game messages
    private static final Pattern GEM_PROGRESS = Pattern.compile("^You're assigned to kill (?:[Tt]he )?(?<name>.+?)"
            + "(?: (?:in|on|south of) (?:the )?(?<location>[^;]+))?; only (?<amount>[\\d,]+) more to go\\.$");
    private static final Pattern NEW_ASSIGNMENT = Pattern.compile("^You have received a new Slayer assignment from .*?: "
            + "(?:[Tt]he )?(?<name>.+?)(?: (?:in|on|south of) (?:the )?(?<location>[^(]+))? \\((?<amount>[\\d,]+)\\)$");
    private static final Pattern TASKS_COMPLETED = Pattern.compile("^You've completed (?:at least )?[\\d,]+ (?:Wilderness )?tasks?");
    private static final Pattern CANCEL_JAD_ZUK = Pattern.compile("^You no longer have a slayer task(?: as you left the (?:fight cave|Inferno))?\\.$");
    private static final Pattern COMBAT_BRACELET = Pattern.compile("^You still need to kill ([\\d,]+) monsters to complete your current Slayer assignment");

    // Slayer-master dialogue
    private static final Pattern NPC_ASSIGN = Pattern.compile("(?:Your new task is to kill|You are to bring balance to)\\s*(?<amount>[\\d,]+) "
            + "(?<name>.+?)(?: (?:in|on|south of) (?:the )?(?<location>.+?))?\\.");
    private static final Pattern NPC_ASSIGN_BOSS = Pattern.compile("You're now assigned to (?:kill|bring balance to) (?:the )?(?<name>.*?) (?<amount>[\\d,]+) times");
    private static final Pattern NPC_ASSIGN_FIRST = Pattern.compile("We'll start you off (?:hunting|bringing balance to) (?<name>.*), "
            + "you'll need to kill (?<amount>[\\d,]+) of them\\.");
    private static final Pattern NPC_CURRENT = Pattern.compile("You're (?:still(?: meant to be)?|currently assigned to) "
            + "(?:hunting|bringing balance to|kill|bring balance to|slaying) (?<name>.+?)(?: (?:in|on|south of) (?:the )?(?<location>.+?))?"
            + "(?:, with|; (?:you have|only)) (?<amount>[\\d,]+)(?: more)? to go\\.");

    private SlayerChatParser()
    {
    }

    /** A game message (GAMEMESSAGE / SPAM); {@code null} when it isn't about the Slayer task. */
    public static Signal parseChat(String raw)
    {
        String msg = clean(raw);
        if (msg.isEmpty()) return null;
        Matcher m = GEM_PROGRESS.matcher(msg);
        if (m.matches()) return task(Kind.CURRENT, m);
        m = NEW_ASSIGNMENT.matcher(msg);
        if (m.matches()) return task(Kind.ASSIGNED, m);
        if (msg.startsWith("You have completed your task!") || TASKS_COMPLETED.matcher(msg).find())
        {
            return new Signal(Kind.COMPLETED, null, null, 0);
        }
        if (msg.equals("Your task has been cancelled.") || CANCEL_JAD_ZUK.matcher(msg).matches())
        {
            return new Signal(Kind.CANCELLED, null, null, -1);
        }
        if (msg.startsWith("Your bracelet of slaughter prevents your slayer")) return new Signal(Kind.BRACELET_SLAUGHTER, null, null, -1);
        if (msg.startsWith("Your expeditious bracelet helps you progress your")) return new Signal(Kind.BRACELET_EXPEDITIOUS, null, null, -1);
        m = COMBAT_BRACELET.matcher(msg);
        if (m.find()) return new Signal(Kind.REMAINING, null, null, number(m.group(1)));
        return null;
    }

    /** The text of an NPC dialogue box; {@code null} when it isn't a Slayer assignment/check. */
    public static Signal parseDialog(String raw)
    {
        String text = clean(raw);
        Matcher m = NPC_ASSIGN.matcher(text);
        if (m.find()) return task(Kind.ASSIGNED, m);
        m = NPC_ASSIGN_BOSS.matcher(text);
        if (m.find()) return new Signal(Kind.ASSIGNED, m.group("name"), null, number(m.group("amount")));
        m = NPC_ASSIGN_FIRST.matcher(text);
        if (m.find()) return new Signal(Kind.ASSIGNED, m.group("name"), null, number(m.group("amount")));
        m = NPC_CURRENT.matcher(text);
        if (m.find()) return task(Kind.CURRENT, m);
        return null;
    }

    private static final Pattern TASK_COMMAND = Pattern.compile("^Slayer Task: (?<name>.+?)(?: \\((?<location>[^)]+)\\))?: "
            + "(?:(?<left>[\\d,]+) left|(?<killed>[\\d,]+)/(?<total>[\\d,]+) killed)$");

    /**
     * RuneLite's reply to the player's own {@code !task}, as written onto that chat
     * line ("Slayer Task: Hydras (Karuulm Slayer Dungeon): 12 left" or "33/45
     * killed"). {@code null} for anything else.
     */
    public static Signal parseTaskCommand(String raw)
    {
        Matcher m = TASK_COMMAND.matcher(clean(raw));
        if (!m.matches()) return null;
        int left = m.group("left") != null ? number(m.group("left"))
                : number(m.group("total")) - number(m.group("killed"));
        if (left < 0) return null;
        String location = m.group("location");
        return new Signal(Kind.CURRENT, m.group("name").trim(), location == null ? null : location.trim(), left);
    }

    /** Strips colour/markup tags and line breaks; collapses spaces. */
    static String clean(String raw)
    {
        if (raw == null) return "";
        return raw.replaceAll("<br>", " ").replaceAll("<[^>]*>", "").replace(' ', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    private static Signal task(Kind kind, Matcher m)
    {
        String location = m.group("location");
        return new Signal(kind, m.group("name").trim(), location == null ? null : location.trim(), number(m.group("amount")));
    }

    private static int number(String s)
    {
        try
        {
            return Integer.parseInt(s.replace(",", ""));
        }
        catch (RuntimeException e)
        {
            return -1;
        }
    }
}
