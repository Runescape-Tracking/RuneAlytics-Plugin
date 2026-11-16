package com.runealytics.slayer;

import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

import java.util.List;

/**
 * Reads the current Slayer task from the game, the same way RuneLite's Slayer
 * plugin does (1.13.x): {@code SLAYER_COUNT} remaining, {@code SLAYER_TARGET}
 * task id (98 = boss task, resolved through {@code SLAYER_TARGET_BOSSID}), the
 * task name from the SlayerTask DB table, and {@code SLAYER_AREA} for Konar /
 * area tasks. Client thread only.
 */
public final class SlayerTaskReader
{
    /** {@code SLAYER_TARGET} value for boss tasks (RuneLite: [proc,helper_slayer_current_assignment]). */
    private static final int BOSS_TASK = 98;

    private SlayerTaskReader()
    {
    }

    /** Varps/varbits whose change means the task may have changed. */
    public static boolean isTaskVar(int varpId, int varbitId)
    {
        return varpId == VarPlayerID.SLAYER_COUNT || varpId == VarPlayerID.SLAYER_TARGET
                || varpId == VarPlayerID.SLAYER_AREA || varbitId == VarbitID.SLAYER_TARGET_BOSSID;
    }

    /**
     * The current task; remaining 0 when there is none. {@code null} when the
     * task tables can't be read yet (try again later).
     */
    public static SlayerTaskTracker.Task read(Client client)
    {
        int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
        if (remaining <= 0) return new SlayerTaskTracker.Task(0, null, null, null, 0);

        int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
        int row;
        if (taskId == BOSS_TASK)
        {
            List<Integer> boss = client.getDBRowsByValue(DBTableID.SlayerTaskSublist.ID,
                    DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID));
            if (boss.isEmpty()) return null;
            row = (Integer) client.getDBTableField(boss.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
        }
        else
        {
            List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
            if (rows.isEmpty()) return null;
            row = rows.get(0);
        }
        String name = (String) client.getDBTableField(row, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];

        String location = null;
        int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
        if (areaId > 0)
        {
            List<Integer> areas = client.getDBRowsByValue(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
            if (areas.isEmpty()) return null;
            location = (String) client.getDBTableField(areas.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0];
        }
        return new SlayerTaskTracker.Task(taskId, name, location, master(client.getVarbitValue(VarbitID.SLAYER_MASTER)), remaining);
    }

    /**
     * The master, where the {@code SLAYER_MASTER} value is known for certain
     * (RuneLite's Slayer plugin: 7 = Krystilia, 10 = Mortimer). Other masters come
     * from the assignment dialogue.
     */
    static String master(int id)
    {
        switch (id)
        {
            case 7:
                return "Krystilia";
            case 10:
                return "Mortimer";
            default:
                return null;
        }
    }
}
