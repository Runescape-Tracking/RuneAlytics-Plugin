package com.runealytics.progress;

import org.junit.Test;

import static com.runealytics.progress.ProgressReadinessGate.Status.ATTEMPT;
import static com.runealytics.progress.ProgressReadinessGate.Status.INACTIVE;
import static com.runealytics.progress.ProgressReadinessGate.Status.READY;
import static com.runealytics.progress.ProgressReadinessGate.Status.TIMED_OUT;
import static com.runealytics.progress.ProgressReadinessGate.Status.WAITING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProgressReadinessGateTest
{
    @Test
    public void inactiveUntilLoggedIn()
    {
        assertEquals(INACTIVE, new ProgressReadinessGate().evaluate(5, true, true));
    }

    @Test
    public void waitsForMinimumTicksAndQuietPeriod()
    {
        ProgressReadinessGate g = new ProgressReadinessGate();
        g.begin(100);
        assertEquals(WAITING, g.evaluate(100, true, true));
        assertEquals(WAITING, g.evaluate(101, true, true));
        g.onVarChanged(102);
        assertEquals("vars still arriving", WAITING, g.evaluate(102, true, true));
        assertEquals(WAITING, g.evaluate(103, true, true));
        assertEquals(ATTEMPT, g.evaluate(104, true, true));
    }

    @Test
    public void neverAttemptsWithoutIdentityOrBaseline()
    {
        ProgressReadinessGate g = new ProgressReadinessGate();
        g.begin(0);
        assertEquals(WAITING, g.evaluate(50, false, true));
        assertEquals(WAITING, g.evaluate(50, true, false));
    }

    @Test
    public void timesOutWhenVariablesNeverSettle()
    {
        ProgressReadinessGate g = new ProgressReadinessGate();
        g.begin(0);
        for (int t = 0; t < ProgressReadinessGate.MAX_TICKS; t++)
        {
            g.onVarChanged(t);
            assertEquals(WAITING, g.evaluate(t, true, true));
        }
        assertEquals(TIMED_OUT, g.evaluate(ProgressReadinessGate.MAX_TICKS, true, true));
    }

    @Test
    public void hopRequiresReadinessAgain()
    {
        ProgressReadinessGate g = new ProgressReadinessGate();
        g.begin(0);
        g.markReady();
        assertTrue(g.isReady());
        assertEquals(READY, g.evaluate(10, true, true));
        g.suspend();
        assertFalse(g.isReady());
        assertEquals(INACTIVE, g.evaluate(11, true, true));
        g.begin(20);
        assertEquals(WAITING, g.evaluate(20, true, true));
    }
}
