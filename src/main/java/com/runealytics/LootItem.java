package com.runealytics;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class LootItem
{
    @SerializedName("item_id")
    private final int itemId;

    @SerializedName("item_name")
    private final String itemName;

    @SerializedName("quantity")
    private final int quantity;

    @SerializedName("ge_price")
    private final int gePrice;

    @SerializedName("high_alch")
    private final int highAlch;

    @SerializedName("total_value")
    private final int totalValue;

    @SerializedName("hidden")
    private final boolean hidden;
}