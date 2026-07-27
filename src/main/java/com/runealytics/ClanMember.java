package com.runealytics;

import com.google.gson.JsonObject;
import lombok.Getter;

/**
 * Represents a single clan member snapshot.
 * Lightweight to avoid overhead during large clan updates.
 */
@Getter
public class ClanMember
{
	/** Player's OSRS username */
	private final String username;

	/** Unix timestamp when this member was first observed in the clan */
	private final long joinedAt;

	/** Unix timestamp of last known activity in the clan */
	private long lastSeenAt;

	/** Whether the player is tracked by RuneAlytics (using the plugin) */
	private final boolean isTracked;

	/** Member's rank in the clan (if available from clan chat) */
	private String rank;

	public ClanMember(String username, long joinedAt, boolean isTracked)
	{
		this.username = username;
		this.joinedAt = joinedAt;
		this.lastSeenAt = System.currentTimeMillis() / 1000;
		this.isTracked = isTracked;
		this.rank = "member"; // default
	}

	public void updateLastSeen()
	{
		this.lastSeenAt = System.currentTimeMillis() / 1000;
	}

	public void setRank(String rank)
	{
		this.rank = rank;
	}

	/**
	 * Converts to JSON for network transmission.
	 * Only sends essential data to minimize payload size.
	 */
	public JsonObject toJson()
	{
		JsonObject obj = new JsonObject();
		obj.addProperty("username",   username);
		obj.addProperty("rank",       rank);
		obj.addProperty("joined_at",  joinedAt);
		obj.addProperty("last_seen",  lastSeenAt);
		obj.addProperty("is_tracked", isTracked);
		return obj;
	}
}
