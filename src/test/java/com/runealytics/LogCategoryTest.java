package com.runealytics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins {@link LogCategory#format} after the varargs change — a leftover
 * single-string signature would throw {@link java.util.MissingFormatArgumentException}
 * on any {@code %s}/{@code %d} message.
 */
public class LogCategoryTest
{
    @Test
    public void format_prefixesCategoryLabel()
    {
        assertEquals("[sync] hello", LogCategory.SYNC.format("hello"));
    }

    @Test
    public void format_substitutesVarargs()
    {
        assertEquals("[perf] Memory: 85% (HIGH)",
                LogCategory.PERF.format("Memory: %d%% (%s)", 85, "HIGH"));
    }

    @Test
    public void format_emptyArgs_leavesPlainMessage()
    {
        assertEquals("[cache] miss", LogCategory.CACHE.format("miss"));
    }
}
