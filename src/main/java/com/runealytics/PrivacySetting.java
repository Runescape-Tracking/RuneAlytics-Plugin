package com.runealytics;

public enum PrivacySetting
{
    PUBLIC("Public"),
    FRIENDS("Friends"),
    PRIVATE("Private");

    private final String label;

    PrivacySetting(String label) { this.label = label; }

    public String getLabel() { return label; }

    @Override
    public String toString() { return label; }
}
