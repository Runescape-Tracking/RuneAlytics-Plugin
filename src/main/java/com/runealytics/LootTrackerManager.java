package com.runealytics;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import okhttp3.OkHttpClient;

import java.util.*;

@Slf4j
public class LootTrackerManager
{
    private final Client client;
    private final ItemManager itemManager;
    private final LootTrackerConfig config;
    private final Gson gson;
    private final OkHttpClient okHttpClient;
    private final Map<String, BossKillStats> bossStats = new HashMap<>();
    private final List<LootTrackerUpdateListener> listeners = new ArrayList<>();
    private final Map<String, Set<Integer>> hiddenDrops = new HashMap<>();


    // Comprehensive list of boss NPC IDs
    private static final Set<Integer> TRACKED_BOSS_IDS = ImmutableSet.of(
            // GWD
            2215, // Commander Zilyana
            2216, // Starlight
            2217, // Bree
            2218, // Growler
            2205, // Spiritual ranger (Zilyana's bodyguard)
            2206, // Spiritual mage (Zilyana's bodyguard)
            2207, // Spiritual warrior (Zilyana's bodyguard)
            2260, // General Graardor
            2261, // Sergeant Strongstack
            2262, // Sergeant Steelwill
            2263, // Sergeant Grimspike
            6260, // Kree'arra
            6261, // Wingman Skree
            6262, // Flockleader Geerin
            6263, // Flight Kilisa
            6203, // K'ril Tsutsaroth
            6204, // Tstanon Karlak
            6205, // Zakl'n Gritch
            6206, // Balfrug Kreeyath

            // Corporeal Beast
            319,  // Corporeal Beast

            // Kalphite Queen
            963,  // Kalphite Queen (form 1)
            965,  // Kalphite Queen (form 2)

            // DKS
            2265, // Dagannoth Prime
            2266, // Dagannoth Rex
            2267, // Dagannoth Supreme

            // Wilderness Bosses
            6766, // Callisto
            6609, // Venenatis
            6611, // Vet'ion
            6612, // Vet'ion reborn
            2054, // Chaos Elemental
            6618, // Crazy Archaeologist
            6619, // Chaos Fanatic
            6615, // Scorpia

            // Other Bosses
            50,   // King Black Dragon
            8059, // Vorkath
            8060, // Vorkath (awakened)
            2042, // Zulrah (green)
            2043, // Zulrah (red)
            2044, // Zulrah (blue)
            5862, // Abyssal Sire
            5886, // Abyssal Sire (sleeping)
            1999, // Cerberus
            7855, // Cerberus (summoned ghosts)
            7544, // Grotesque Guardians - Dusk
            7796, // Grotesque Guardians - Dawn
            494,  // Kraken
            496,  // Kraken tentacle
            7605, // Thermonuclear Smoke Devil
            8609, // Alchemical Hydra

            // Colosseum Bosses
            12796, // Yama (Wave 12)
            12821, // Doom (Final boss)
            12797, // Sol Heredit (Wave 1)
            12798, // Shockwave (Wave 2)
            12799, // Serpent Shaman (Wave 3)
            12800, // Javelin Colossus (Wave 4)
            12801, // Manticore (Wave 5)
            12802, // Shockwave Colossus (Wave 6)
            12803, // Minotaur (Wave 7)
            12804, // Serpent Shaman (Wave 8)
            12805, // Javelin Colossus (Wave 9)
            12806, // Manticore (Wave 10)
            12807, // Shockwave Colossus (Wave 11)

            // Nightmare
            9415, // The Nightmare
            9416, // The Nightmare (transition)
            9425, // Phosani's Nightmare
            9426, // Phosani's Nightmare (transition)

            // Nex
            11278, // Nex
            11279, // Nex (uncharged)

            // Theatre of Blood
            10674, // Maiden of Sugadinti
            10698, // Pestilent Bloat
            10702, // Nylocas Vasilias
            10704, // Sotetseg
            10707, // Xarpus
            10847, // Verzik Vitur (P1)
            10848, // Verzik Vitur (P2)
            10849, // Verzik Vitur (P3)

            // Chambers of Xeric
            7554, // Great Olm (left claw)
            7555, // Great Olm (right claw)
            7556, // Great Olm (head)

            // Tombs of Amascut
            11750, // Wardens P2
            11751, // Wardens P3
            11752, // Wardens (final)
            11753, // Tumeken's Warden
            11754, // Elidinis' Warden
            11770, // Zebak
            11771, // Kephri
            11772, // Ba-Ba
            11773, // Akkha

            // DT2 Bosses
            12166, // Duke Sucellus
            12167, // Duke Sucellus (Awakened)
            12193, // The Leviathan
            12214, // The Leviathan (Awakened)
            12205, // Vardorvis
            12223, // Vardorvis (Awakened)
            12225, // The Whisperer
            12227, // The Whisperer (Awakened)

            // Other New Bosses
            9027,  // Phantom Muspah
            10814, // Scurrius
            7858,  // Giant Mole
            8350,  // Sarachnis
            8338,  // Skotizo

            // Tempoross
            10565, // Tempoross

            // Wintertodt
            7559,  // Wintertodt

            // Zalcano
            9050,  // Zalcano

            // Barrows
            2025, // Ahrim the Blighted
            2026, // Dharok the Wretched
            2027, // Guthan the Infested
            2028, // Karil the Tainted
            2029, // Torag the Corrupted
            2030, // Verac the Defiled

            // Wilderness bosses (revamped)
            11872, // Calvar'ion
            11867, // Artio
            11868 // Spindel

    );

    // Boss name to ID mapping for reverse lookup
    private static final Map<String, Integer> BOSS_NAME_TO_ID = ImmutableMap.<String, Integer>builder()
            .put("Commander Zilyana", 2215)
            .put("General Graardor", 2260)
            .put("Kree'arra", 6260)
            .put("K'ril Tsutsaroth", 6203)
            .put("Corporeal Beast", 319)
            .put("Kalphite Queen", 963)
            .put("Dagannoth Prime", 2265)
            .put("Dagannoth Rex", 2266)
            .put("Dagannoth Supreme", 2267)
            .put("Callisto", 6766)
            .put("Venenatis", 6609)
            .put("Vet'ion", 6611)
            .put("Chaos Elemental", 2054)
            .put("Crazy Archaeologist", 6618)
            .put("Chaos Fanatic", 6619)
            .put("Scorpia", 6615)
            .put("King Black Dragon", 50)
            .put("Vorkath", 8059)
            .put("Zulrah", 2042)
            .put("Abyssal Sire", 5862)
            .put("Cerberus", 1999)
            .put("Dusk", 7544)
            .put("Dawn", 7796)
            .put("Kraken", 494)
            .put("Thermonuclear Smoke Devil", 7605)
            .put("Alchemical Hydra", 8609)
            .put("The Nightmare", 9415)
            .put("Phosani's Nightmare", 9425)
            .put("Nex", 11278)
            .put("Verzik Vitur", 10847)
            .put("Great Olm", 7554)
            .put("Duke Sucellus", 12166)
            .put("The Leviathan", 12193)
            .put("Vardorvis", 12205)
            .put("The Whisperer", 12225)
            .put("Phantom Muspah", 9027)
            .put("Scurrius", 10814)
            .put("Giant Mole", 7858)
            .put("Sarachnis", 8350)
            .put("Skotizo", 8338)
            .put("Tempoross", 10565)
            .put("Wintertodt", 7559)
            .put("Zalcano", 9050)
            .put("Calvar'ion", 11872)
            .put("Artio", 11867)
            .put("Spindel", 11868)
            .build();

    public LootTrackerManager(Client client, ItemManager itemManager, LootTrackerConfig config,
                              Gson gson, OkHttpClient okHttpClient)
    {
        this.client = client;
        this.itemManager = itemManager;
        this.config = config;
        this.gson = gson;
        this.okHttpClient = okHttpClient;
    }

    public List<BossKillStats> getAllBossStats()
    {
        return new ArrayList<>(bossStats.values());
    }

    /** Check if a drop is hidden for a specific NPC */
    public boolean isDropHidden(String npcName, int itemId)
    {
        Set<Integer> hidden = hiddenDrops.get(npcName);
        return hidden != null && hidden.contains(itemId);
    }

    /** Hide a drop for a specific NPC */
    public void hideDropForNpc(String npcName, int itemId)
    {
        hiddenDrops.computeIfAbsent(npcName, k -> new HashSet<>()).add(itemId);
    }

    /** Unhide a drop for a specific NPC */
    public void unhideDropForNpc(String npcName, int itemId)
    {
        Set<Integer> hidden = hiddenDrops.get(npcName);
        if (hidden != null)
        {
            hidden.remove(itemId);
            if (hidden.isEmpty()) hiddenDrops.remove(npcName);
        }
    }

    public boolean isBoss(int npcId, String npcName)
    {
        // Check by ID first (most reliable)
        if (TRACKED_BOSS_IDS.contains(npcId))
        {
            return true;
        }

        // Fallback to name-based detection
        if (npcName != null)
        {
            return isBossName(npcName);
        }

        return false;
    }

    public void clearAllData()
    {
        // Clear all boss stats
        bossStats.clear(); // Assuming you have a Map<String, BossKillStats> bossStats

        // Notify all listeners that data changed
        for (LootTrackerUpdateListener listener : listeners)
        {
            listener.onDataRefresh();
        }
    }

    /** Prestige a boss (resets stats but increments prestige) */
    public void prestigeBoss(String npcName)
    {
        BossKillStats stats = bossStats.get(npcName);
        if (stats != null)
        {
            stats.prestige();
        }
    }

    /** Add a kill record */
    public void addKill(NpcKillRecord kill)
    {
        BossKillStats stats = bossStats.computeIfAbsent(
                kill.getNpcName(),
                name -> new BossKillStats(name, kill.getNpcId())
        );
        stats.addKill(kill);
        // Optionally notify listeners
    }


    /** Clear data for a specific boss */
    public void clearBossData(String npcName)
    {
        bossStats.remove(npcName);
        hiddenDrops.remove(npcName);
    }

    public void addListener(LootTrackerUpdateListener listener)
    {
        listeners.add(listener);
    }

    private boolean isBossName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }

        String lowerName = name.toLowerCase();

        // Comprehensive boss name patterns
        return lowerName.contains("duke") ||
                lowerName.contains("sucellus") ||
                lowerName.contains("leviathan") ||
                lowerName.contains("vardorvis") ||
                lowerName.contains("whisperer") ||
                lowerName.contains("zulrah") ||
                lowerName.contains("vorkath") ||
                lowerName.contains("cerberus") ||
                lowerName.contains("nightmare") ||
                lowerName.contains("phosani") ||
                lowerName.contains("nex") ||
                lowerName.contains("graardor") ||
                lowerName.contains("zilyana") ||
                lowerName.contains("kree") ||
                lowerName.contains("kril") ||
                lowerName.contains("corporeal") ||
                lowerName.contains("kalphite queen") ||
                lowerName.contains("dagannoth") ||
                lowerName.contains("chaos elemental") ||
                lowerName.contains("king black dragon") ||
                lowerName.contains("abyssal sire") ||
                lowerName.contains("grotesque") ||
                lowerName.contains("dusk") ||
                lowerName.contains("dawn") ||
                lowerName.contains("kraken") ||
                lowerName.contains("smoke devil") ||
                lowerName.contains("hydra") ||
                lowerName.contains("verzik") ||
                lowerName.contains("olm") ||
                lowerName.contains("muspah") ||
                lowerName.contains("scurrius") ||
                lowerName.contains("giant mole") ||
                lowerName.contains("sarachnis") ||
                lowerName.contains("skotizo") ||
                lowerName.contains("tempoross") ||
                lowerName.contains("wintertodt") ||
                lowerName.contains("zalcano") ||
                lowerName.contains("callisto") ||
                lowerName.contains("venenatis") ||
                lowerName.contains("vetion") ||
                lowerName.contains("calvarion") ||
                lowerName.contains("artio") ||
                lowerName.contains("spindel") ||
                lowerName.contains("warden") ||
                lowerName.contains("zebak") ||
                lowerName.contains("kephri") ||
                lowerName.contains("ba-ba") ||
                lowerName.contains("akkha");
    }

    public String normalizeBossName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return "Unknown";
        }

        String normalized = name.trim();
        String lowerName = normalized.toLowerCase();

        // DT2 Bosses
        if (lowerName.contains("duke") || lowerName.contains("sucellus"))
        {
            return "Duke Sucellus";
        }
        if (lowerName.contains("leviathan"))
        {
            return "The Leviathan";
        }
        if (lowerName.contains("vardorvis"))
        {
            return "Vardorvis";
        }
        if (lowerName.contains("whisperer"))
        {
            return "The Whisperer";
        }

        // Other bosses
        if (lowerName.contains("zulrah"))
        {
            return "Zulrah";
        }
        if (lowerName.contains("vorkath"))
        {
            return "Vorkath";
        }
        if (lowerName.contains("cerberus"))
        {
            return "Cerberus";
        }
        if (lowerName.contains("phosani"))
        {
            return "Phosani's Nightmare";
        }
        if (lowerName.contains("nightmare"))
        {
            return "The Nightmare";
        }
        if (lowerName.contains("nex"))
        {
            return "Nex";
        }
        if (lowerName.contains("graardor"))
        {
            return "General Graardor";
        }
        if (lowerName.contains("zilyana"))
        {
            return "Commander Zilyana";
        }
        if (lowerName.contains("kree"))
        {
            return "Kree'arra";
        }
        if (lowerName.contains("kril") || lowerName.contains("k'ril"))
        {
            return "K'ril Tsutsaroth";
        }
        if (lowerName.contains("corporeal"))
        {
            return "Corporeal Beast";
        }
        if (lowerName.contains("kalphite"))
        {
            return "Kalphite Queen";
        }
        if (lowerName.contains("prime"))
        {
            return "Dagannoth Prime";
        }
        if (lowerName.contains("rex"))
        {
            return "Dagannoth Rex";
        }
        if (lowerName.contains("supreme"))
        {
            return "Dagannoth Supreme";
        }
        if (lowerName.contains("king black"))
        {
            return "King Black Dragon";
        }
        if (lowerName.contains("sire"))
        {
            return "Abyssal Sire";
        }
        if (lowerName.contains("dusk") || lowerName.contains("dawn") || lowerName.contains("grotesque"))
        {
            return "Grotesque Guardians";
        }
        if (lowerName.contains("kraken"))
        {
            return "Kraken";
        }
        if (lowerName.contains("smoke devil"))
        {
            return "Thermonuclear Smoke Devil";
        }
        if (lowerName.contains("hydra"))
        {
            return "Alchemical Hydra";
        }
        if (lowerName.contains("verzik"))
        {
            return "Theatre of Blood";
        }
        if (lowerName.contains("olm"))
        {
            return "Chambers of Xeric";
        }
        if (lowerName.contains("warden"))
        {
            return "Tombs of Amascut";
        }
        if (lowerName.contains("muspah"))
        {
            return "Phantom Muspah";
        }
        if (lowerName.contains("scurrius"))
        {
            return "Scurrius";
        }
        if (lowerName.contains("mole"))
        {
            return "Giant Mole";
        }
        if (lowerName.contains("sarachnis"))
        {
            return "Sarachnis";
        }
        if (lowerName.contains("skotizo"))
        {
            return "Skotizo";
        }
        if (lowerName.contains("tempoross"))
        {
            return "Tempoross";
        }
        if (lowerName.contains("wintertodt"))
        {
            return "Wintertodt";
        }
        if (lowerName.contains("zalcano"))
        {
            return "Zalcano";
        }
        if (lowerName.contains("callisto") || lowerName.contains("calvarion"))
        {
            return "Callisto";
        }
        if (lowerName.contains("venenatis") || lowerName.contains("spindel"))
        {
            return "Venenatis";
        }
        if (lowerName.contains("vetion") || lowerName.contains("artio"))
        {
            return "Vet'ion";
        }
        if (lowerName.contains("chaos elemental"))
        {
            return "Chaos Elemental";
        }
        if (lowerName.contains("archaeologist"))
        {
            return lowerName.contains("crazy") ? "Crazy Archaeologist" : "Deranged Archaeologist";
        }
        if (lowerName.contains("fanatic"))
        {
            return "Chaos Fanatic";
        }
        if (lowerName.contains("scorpia"))
        {
            return "Scorpia";
        }

        // Barrows
        if (lowerName.contains("ahrim") || lowerName.contains("dharok") ||
                lowerName.contains("guthan") || lowerName.contains("karil") ||
                lowerName.contains("torag") || lowerName.contains("verac"))
        {
            return "Barrows";
        }

        return normalized;
    }

    public Integer getBossIdFromName(String bossName)
    {
        String normalized = normalizeBossName(bossName);
        return BOSS_NAME_TO_ID.get(normalized);
    }
}