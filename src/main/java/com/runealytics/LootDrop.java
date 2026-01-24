package com.runealytics;

import lombok.Data;
import java.time.Instant;

/**
 * Represents a single item drop from an NPC
 */
@Data
public class LootDrop
{
    private final int itemId;
    private final String itemName;
    private final int quantity;
    private final long gePrice;      // Grand Exchange price
    private final int highAlchValue; // High alch value
    private final boolean hidden;    // User chose to hide this drop
    private final Instant timestamp;

    public LootDrop(int itemId, String itemName, int quantity, long gePrice, int highAlchValue)
    {
        this.itemId = itemId;
        this.itemName = itemName;
        this.quantity = quantity;
        this.gePrice = gePrice;
        this.highAlchValue = highAlchValue;
        this.hidden = false;
        this.timestamp = Instant.now();
    }

    /**
     * Get total value of this drop (GE price * quantity)
     */
    public long getTotalValue()
    {
        return gePrice * quantity;
    }

    /**
     * Get total high alch value
     */
    public long getTotalAlchValue()
    {
        return (long) highAlchValue * quantity;
    }
}