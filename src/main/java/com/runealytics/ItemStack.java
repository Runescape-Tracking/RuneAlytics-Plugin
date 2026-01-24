package com.runealytics;

import lombok.Data;

/**
 * Simple item stack representation for loot tracking
 */
@Data
public class ItemStack
{
    private final int id;
    private final int quantity;

    public ItemStack(int id, int quantity)
    {
        this.id = id;
        this.quantity = quantity;
    }
}