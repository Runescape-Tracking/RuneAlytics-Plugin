package com.runealytics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Value-object contract for the loot-pipeline item pair, including the
 * Lombok-generated equality/hashCode used when stacks are deduplicated.
 */
public class ItemStackTest
{
    @Test
    public void gettersReturnConstructorValues()
    {
        ItemStack stack = new ItemStack(4151, 3);
        assertEquals(4151, stack.getId());
        assertEquals(3, stack.getQuantity());
    }

    @Test
    public void equality_matchesOnIdAndQuantity()
    {
        assertEquals(new ItemStack(995, 100), new ItemStack(995, 100));
        assertEquals(new ItemStack(995, 100).hashCode(), new ItemStack(995, 100).hashCode());
    }

    @Test
    public void equality_differsOnIdOrQuantity()
    {
        assertNotEquals(new ItemStack(995, 100), new ItemStack(996, 100));
        assertNotEquals(new ItemStack(995, 100), new ItemStack(995, 101));
    }

    @Test
    public void allowsBoundaryAndNonPositiveValues()
    {
        ItemStack zero = new ItemStack(0, 0);
        assertEquals(0, zero.getId());
        assertEquals(0, zero.getQuantity());

        ItemStack negative = new ItemStack(-1, -5);
        assertEquals(-1, negative.getId());
        assertEquals(-5, negative.getQuantity());

        ItemStack max = new ItemStack(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, max.getId());
        assertEquals(Integer.MAX_VALUE, max.getQuantity());
    }
}
