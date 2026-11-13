package com.runealytics;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Gives instanced areas (Cerberus, Vorkath, raids, God Wars instances, …) a real
 * map location and a name.
 *
 * <p>Inside an instance the player's world coordinates are throw-away instance
 * coordinates that mean nothing on the map. So, every tick outside an instance,
 * this remembers where the player stands; on entering an instance that last spot
 * (the Cerberus winch, the Vorkath boat, the raid lobby, …) is reported instead,
 * with {@code instance_name} saying where they are ("Cerberus", "Theatre of
 * Blood: Verzik Vitur", …). If the entrance isn't known (e.g. logged in inside a
 * raid), the instance's template position — its real place on the world map —
 * is used.</p>
 *
 * <p>The name comes from the instance's template region ({@link #AREAS}); for
 * instances not in the table, from the NPC the player last fought there.</p>
 *
 * <p>Client thread only. Per tick: one instanced-region check and, outside an
 * instance, one world-location read.</p>
 */
@Slf4j
@Singleton
public class InstanceLocationTracker
{
    /** Template region id → display name. */
    static final Map<Integer, String> AREAS;

    static
    {
        Map<Integer, String> m = new HashMap<>();
        put(m, "Cerberus", 4883, 5140, 5395);
        put(m, "Vorkath", 9023);
        // 9008 is the north edge of Zulrah's shrine; the boss tile itself is in 9007.
        put(m, "Zulrah", 9007, 9008);
        put(m, "Kraken", 9116);
        put(m, "Thermonuclear smoke devil", 9363, 9619);
        put(m, "Grotesque Guardians", 6727);
        put(m, "Alchemical Hydra", 5536);
        // 6810 is the older template; current clients copy the lair from 9048.
        put(m, "Skotizo", 6810, 9048);
        put(m, "Hespori", 5021);
        put(m, "Corporeal Beast", 11842, 11844);
        put(m, "The Nightmare", 15515);
        put(m, "Phantom Muspah", 11330);
        put(m, "General Graardor", 11347);
        put(m, "Kree'arra", 11346);
        put(m, "Commander Zilyana", 11602);
        put(m, "K'ril Tsutsaroth", 11603);
        put(m, "Nex", 11601);
        put(m, "Duke Sucellus", 12132);
        put(m, "The Leviathan", 8291);
        put(m, "The Whisperer", 10595);
        put(m, "Vardorvis", 4405);
        put(m, "TzTok-Jad (Fight Caves)", 9551);
        put(m, "TzKal-Zuk (Inferno)", 9043);
        put(m, "Fortis Colosseum", 7216);
        put(m, "The Gauntlet", 7512);
        put(m, "The Corrupted Gauntlet", 7768);
        put(m, "Theatre of Blood: The Maiden of Sugadinti", 12613);
        put(m, "Theatre of Blood: Pestilent Bloat", 13125);
        put(m, "Theatre of Blood: Nylocas", 13122);
        put(m, "Theatre of Blood: Sotetseg", 13123, 13379);
        put(m, "Theatre of Blood: Xarpus", 12612);
        put(m, "Theatre of Blood: Verzik Vitur", 12611, 12867); // 12867 is her treasure vault
        put(m, "Tombs of Amascut", 14160);
        put(m, "Tombs of Amascut: Akkha", 14676);
        put(m, "Tombs of Amascut: Ba-Ba", 15188);
        put(m, "Tombs of Amascut: Kephri", 14164);
        put(m, "Tombs of Amascut: Zebak", 15700);
        put(m, "Tombs of Amascut: Wardens", 15184, 15696);
        put(m, "Tombs of Amascut: Path of Het", 14674);
        put(m, "Tombs of Amascut: Path of Scabaras", 14162);
        put(m, "Tombs of Amascut: Path of Apmeken", 15186);
        put(m, "Tombs of Amascut: Path of Crondis", 15698);
        AREAS = Collections.unmodifiableMap(m);
    }

    static final String CHAMBERS_OF_XERIC = "Chambers of Xeric";

    private static void put(Map<Integer, String> m, String name, int... regions)
    {
        for (int r : regions) m.put(r, name);
    }

    private final Client client;

    /** Where the player last stood outside an instance (this login). */
    private WorldPoint lastOutside;
    private boolean inInstance;
    /** {@link #lastOutside} as it was when the current instance was entered. */
    private WorldPoint entrance;
    /** Last NPC fought in the current instance (name fallback). */
    private String lastFought;

    @Inject
    public InstanceLocationTracker(Client client)
    {
        this.client = client;
    }

    /** Game tick (client thread). */
    public void onGameTick()
    {
        Player me = client.getLocalPlayer();
        if (me == null) return;
        WorldPoint here = me.getWorldLocation();
        if (here == null) return;
        if (!client.isInInstancedRegion())
        {
            if (inInstance) log.debug("[Location] left instance");
            inInstance = false;
            entrance = null;
            lastFought = null;
            lastOutside = here;
            return;
        }
        if (!inInstance)
        {
            inInstance = true;
            entrance = lastOutside;
            lastFought = null;
            log.debug("[Location] entered instance; reporting entrance {}", entrance);
        }
        Actor target = me.getInteracting();
        if (target instanceof NPC)
        {
            NPC npc = (NPC) target;
            String name = BossNames.stripColorTags(npc.getName());
            if (name != null && !name.trim().isEmpty() && npc.getCombatLevel() > 0)
            {
                lastFought = name.trim();
            }
        }
    }

    /** Logout / account change: forget the remembered spot. */
    public void reset()
    {
        lastOutside = null;
        inInstance = false;
        entrance = null;
        lastFought = null;
    }

    /**
     * Swaps an instanced {@code raw} capture for the entrance (or template)
     * position plus the instance name. Anything else, including the privacy
     * decoy and {@code null}, is returned unchanged. Client thread.
     */
    public PlayerLocationSnapshot resolve(PlayerLocationSnapshot raw)
    {
        if (raw == null || !raw.isInstanced()) return raw;
        Player me = client.getLocalPlayer();
        WorldPoint template = templatePoint(me);
        // An empty instance chunk resolves to region 0 (tiles near the origin). That is
        // not a place on the map; ignore it and keep the entrance, or omit the location.
        if (template != null && template.getRegionID() == 0)
        {
            template = null;
        }
        String name = instanceName(template);
        // Before this tick's onGameTick ran, the spot from the last tick outside is the entrance.
        WorldPoint door = inInstance ? entrance : lastOutside;
        if (door != null)
        {
            return PlayerLocationSnapshot.forInstance(door, name, true, raw.getWorld(), raw.getTimestamp());
        }
        if (template != null)
        {
            return PlayerLocationSnapshot.forInstance(template, name, false, raw.getWorld(), raw.getTimestamp());
        }
        return null;   // nowhere sensible to show: omit the location
    }

    /** The instance name for the player's current spot, or {@code null}. Client thread. */
    String instanceName(WorldPoint template)
    {
        if (template != null)
        {
            String area = AREAS.get(template.getRegionID());
            if (area != null) return area;
        }
        if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1) return CHAMBERS_OF_XERIC;
        return lastFought;
    }

    /** Where the instance is copied from on the real map (package-private for tests). */
    WorldPoint templatePoint(Player me)
    {
        if (me == null) return null;
        LocalPoint lp = me.getLocalLocation();
        return lp != null ? WorldPoint.fromLocalInstance(client, lp) : null;
    }
}
