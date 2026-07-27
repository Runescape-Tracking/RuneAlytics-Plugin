package com.runealytics;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages clan membership and auto-detection.
 *
 * Tracks:
 * - Current clan membership
 * - Member list from clan chat
 * - Automatic detection when player joins/leaves clan
 *
 * Thread-safe: accepts updates from game thread, readable from any thread.
 */
@Slf4j
@Singleton
@Getter
public class ClanManager
{
	/** Current clan info (null when not in a clan) */
	private volatile ClanInfo currentClan;

	/** List of observers notified when clan state changes */
	private final List<ClanUpdateListener> listeners = new CopyOnWriteArrayList<>();

	public interface ClanUpdateListener
	{
		void onClanJoined(ClanInfo clan);
		void onClanLeft();
		void onMembersUpdated(ClanInfo clan);
	}

	/**
	 * Register a listener for clan state changes.
	 */
	public void addListener(ClanUpdateListener listener)
	{
		listeners.add(listener);
	}

	/**
	 * Called when player joins a clan.
	 * Initializes or updates the clan state.
	 */
	public void onClanJoined(String clanName, String clanTag)
	{
		if (currentClan != null && currentClan.getClanName().equalsIgnoreCase(clanName))
		{
			log.debug("[Clan] Already in clan: {}", clanName);
			return;
		}

		ClanInfo newClan = new ClanInfo(clanName);
		if (clanTag != null && !clanTag.isEmpty())
		{
			newClan.setClanTag(clanTag);
		}
		currentClan = newClan;

		log.info("[Clan] Joined clan: {} tag={}", clanName, clanTag != null ? clanTag : "none");
		listeners.forEach(l -> l.onClanJoined(newClan));
	}

	/**
	 * Called when player leaves the clan (logout or manual leave).
	 */
	public void onClanLeft()
	{
		if (currentClan == null)
		{
			return;
		}

		log.info("[Clan] Left clan: {}", currentClan.getClanName());
		currentClan = null;
		listeners.forEach(ClanUpdateListener::onClanLeft);
	}

	/**
	 * Adds or updates a member in the current clan.
	 * Called when member appears in clan chat or member list.
	 *
	 * @param username player's RSN
	 * @param isTracked whether this player uses RuneAlytics (plugin)
	 */
	public void recordMember(String username, boolean isTracked)
	{
		if (currentClan == null)
		{
			return;
		}

		currentClan.upsertMember(username, isTracked);
		log.debug("[Clan] Member recorded: {} tracked={}", username, isTracked);
	}

	/**
	 * Parses clan member list from clan chat widget.
	 * This is called when the player opens the member list UI.
	 *
	 * @param members list of usernames in the clan
	 */
	public void updateMemberList(List<String> members)
	{
		if (currentClan == null || members == null || members.isEmpty())
		{
			return;
		}

		// Add any new members
		for (String username : members)
		{
			currentClan.upsertMember(username, false); // can't determine if tracked from UI alone
		}

		log.debug("[Clan] Member list updated: {} members", members.size());
		listeners.forEach(l -> l.onMembersUpdated(currentClan));
	}

	/**
	 * Marks the current clan as having been synced to the server.
	 * Prevents unnecessary re-sends of unchanged data.
	 */
	public void markSynced()
	{
		if (currentClan != null)
		{
			currentClan.setLastSyncAt(System.currentTimeMillis() / 1000);
			log.debug("[Clan] Marked synced: {}", currentClan.getClanName());
		}
	}

	/**
	 * Checks if current clan data is dirty (needs sync to server).
	 */
	public boolean hasDirtyData()
	{
		return currentClan != null && currentClan.isDirty();
	}

	/**
	 * Reset clan state (called on logout).
	 */
	public void reset()
	{
		currentClan = null;
	}

	/**
	 * Gets a safe snapshot of clan members for serialization.
	 */
	public List<ClanMember> getMemberSnapshot()
	{
		if (currentClan == null)
		{
			return Collections.emptyList();
		}
		return currentClan.getMemberSnapshot();
	}

	/**
	 * Gets member count (or 0 if not in clan).
	 */
	public int getMemberCount()
	{
		return currentClan != null ? currentClan.getMemberCount() : 0;
	}
}
