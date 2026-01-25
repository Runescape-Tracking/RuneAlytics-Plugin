package com.runealytics;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.PluginDescriptor;


@PluginDescriptor(
        name = "RuneAlytics"
)
public class RuneAlyticsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RuneAlyticsPlugin.class);
		RuneLite.main(args);
	}
}