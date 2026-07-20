package com.runealytics;

import java.util.regex.Pattern;

/**
 * Pure, dependency-free boss / loot-source name canonicalisation.
 *
 * <p>Extracted from {@code LootTrackerManager} so the mapping can be used by
 * components that must stay free of client dependencies (e.g.
 * {@link KillCountResolver}) and unit-tested in isolation. This is the seed of
 * the future data-driven {@code EncounterRegistry}: every variant NPC, chat,
 * or widget name funnels through here to one canonical storage key.</p>
 *
 * <p>{@code LootTrackerManager.normalizeBossName} delegates to
 * {@link #normalize(String)} — behaviour is identical for all existing
 * callers.</p>
 */
public final class BossNames
{
    private BossNames() {}

    /** Prefix marking thieving/pickpocket sources; must pass through unchanged. */
    static final String PICKPOCKET_PREFIX = "Pickpocket: ";

    private static final Pattern COLOR_TAG_PATTERN =
            Pattern.compile("</?col[^>]*>", Pattern.CASE_INSENSITIVE);

    /**
     * Strips RuneScape chat-message colour markup (e.g. {@code <col=00ffff>...</col>})
     * that occasionally leaks into an NPC's display name for certain reflected /
     * phase-variant monsters.
     */
    public static String stripColorTags(String raw)
    {
        return raw == null ? null : COLOR_TAG_PATTERN.matcher(raw).replaceAll("");
    }

    /**
     * Maps variant NPC / source names to a single canonical display name.
     *
     * <p>Pickpocket-prefixed names pass through unchanged so that stored
     * thieving entries survive across normalisation calls.</p>
     *
     * @param raw raw name from NPC, widget, or chat
     * @return canonical name, never null
     */
    public static String normalize(String raw)
    {
        if (raw == null || raw.isEmpty()) return "Unknown";

        raw = stripColorTags(raw).trim();
        if (raw.isEmpty()) return "Unknown";

        // Pass through already-prefixed pickpocket entries unchanged
        if (raw.startsWith(PICKPOCKET_PREFIX)) return raw;

        String l = raw.toLowerCase();

        if (l.contains("doom of mokhaiotl"))              return "Doom of Mokhaiotl";

        if (l.contains("corrupted gauntlet"))             return "Corrupted Gauntlet";
        if (l.contains("gauntlet"))                       return "The Gauntlet";
        if (l.contains("chambers") || l.contains("cox"))  return "Chambers of Xeric";
        if (l.contains("theatre") || l.contains("tob"))   return "Theatre of Blood";
        if (l.contains("tombs") || l.contains("toa"))     return "Tombs of Amascut";

        if (l.contains("zilyana"))                        return "Commander Zilyana";
        if (l.contains("graardor"))                       return "General Graardor";
        if (l.contains("kree"))                           return "Kree'arra";
        if (l.contains("kril"))                           return "K'ril Tsutsaroth";
        if (l.equals("nex"))                              return "Nex";

        if (l.contains("artio"))                          return "Artio";
        if (l.contains("callisto"))                       return "Callisto";
        if (l.contains("calvar"))                         return "Calvar'ion";
        if (l.contains("vet'ion") || l.contains("vetion")) return "Vet'ion";
        if (l.contains("spindel"))                        return "Spindel";
        if (l.contains("venenatis"))                      return "Venenatis";
        if (l.contains("corporeal"))                      return "Corporeal Beast";
        if (l.contains("chaos fanatic"))                  return "Chaos Fanatic";
        if (l.contains("scorpia"))                        return "Scorpia";
        if (l.contains("crazy archaeologist"))            return "Crazy Archaeologist";

        if (l.contains("duke") || l.contains("sucellus")) return "Duke Sucellus";
        if (l.contains("leviathan"))                      return "The Leviathan";
        if (l.contains("vardorvis"))                      return "Vardorvis";
        if (l.contains("whisperer"))                      return "The Whisperer";

        if (l.contains("royal titans") || l.contains("eldric") || l.contains("branda"))
            return "Royal Titans";
        if (l.contains("hueycoatl"))                      return "The Hueycoatl";
        if (l.contains("moons of peril") || l.contains("blue moon")
                || l.contains("blood moon") || l.contains("eclipse moon")
                || l.contains("lunar chest") || l.equals("lunar"))
            return "Moons of Peril";
        if (l.contains("muspah"))                         return "Phantom Muspah";
        if (l.contains("yama"))                           return "Yama";
        if (l.contains("araxxor"))                        return "Araxxor";
        if (l.contains("scurrius"))                       return "Scurrius";
        if (l.contains("amoxliatl"))                      return "Amoxliatl";
        if (l.contains("tormented demon"))                return "Tormented Demon";
        if (l.contains("colosseum") || l.contains("fortis"))
            return "Fortis Colosseum";

        if (l.contains("zulrah"))                         return "Zulrah";
        if (l.contains("vorkath"))                        return "Vorkath";
        if (l.contains("hydra"))                          return "Alchemical Hydra";
        if (l.contains("cerberus"))                       return "Cerberus";
        if (l.contains("abyssal sire"))                   return "Abyssal Sire";
        if (l.contains("kraken"))                         return "Kraken";
        if (l.contains("smoke devil") || l.contains("thermonuclear")) return "Smoke Devil";
        if (l.contains("phosani"))                        return "Phosani's Nightmare";
        if (l.contains("nightmare"))                      return "The Nightmare";
        if (l.contains("grotesque"))                      return "Grotesque Guardians";
        if (l.contains("kalphite queen"))                 return "Kalphite Queen";
        if (l.contains("kbd") || l.contains("king black dragon")) return "King Black Dragon";
        if (l.contains("dagannoth prime"))                return "Dagannoth Prime";
        if (l.contains("dagannoth rex"))                  return "Dagannoth Rex";
        if (l.contains("dagannoth supreme"))              return "Dagannoth Supreme";
        if (l.contains("skotizo"))                        return "Skotizo";
        if (l.contains("hespori"))                        return "Hespori";

        if (l.contains("barrows"))                        return "Barrows";
        if (l.contains("tempoross"))                      return "Tempoross";
        if (l.contains("wintertodt"))                     return "Wintertodt";
        if (l.contains("zalcano"))                        return "Zalcano";

        if (l.contains("beginner clue") || (l.contains("beginner") && l.contains("clue")))
            return "Beginner Clue";
        if (l.contains("easy clue") || (l.contains("easy") && l.contains("clue")))
            return "Easy Clue";
        if (l.contains("medium clue") || (l.contains("medium") && l.contains("clue")))
            return "Medium Clue";
        if (l.contains("hard clue") || (l.contains("hard") && l.contains("clue")))
            return "Hard Clue";
        if (l.contains("elite clue") || (l.contains("elite") && l.contains("clue")))
            return "Elite Clue";
        if (l.contains("master clue") || (l.contains("master") && l.contains("clue")))
            return "Master Clue";

        return raw.trim();
    }
}
