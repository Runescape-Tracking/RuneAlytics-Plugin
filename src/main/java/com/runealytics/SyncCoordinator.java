package com.runealytics;

import lombok.extern.slf4j.Slf4j;

/**
 * Coordinates synchronization operations to ensure:
 * 1. Only one full loot sync runs at a time
 * 2. Additional requests during a sync are coalesced (not silently lost)
 * 3. The strongest priority level is honored
 * 4. Exception-safe slot release
 */
@Slf4j
public class SyncCoordinator
{
	private volatile boolean syncInProgress = false;
	private volatile SyncRequest pendingRequest = null;

	/**
	 * Attempt to start a synchronization.
	 * If one is already running, marks it as pending and returns false.
	 * Returns true if the caller should proceed with the sync.
	 */
	public synchronized boolean tryStartSync(SyncRequest request)
	{
		if (!syncInProgress)
		{
			syncInProgress = true;
			pendingRequest = null;
			log.debug("[Sync] Starting: {}", request);
			return true;
		}
		else
		{
			// Sync already running — coalesce the pending request
			if (pendingRequest == null)
			{
				pendingRequest = request;
				log.debug("[Sync] Queuing pending: {}", request);
			}
			else
			{
				SyncRequest merged = pendingRequest.mergeWith(request);
				pendingRequest = merged;
				log.debug("[Sync] Coalesced request: {} + {} -> {}",
					pendingRequest, request, merged);
			}
			return false;
		}
	}

	/**
	 * Release the sync slot. If a request was pending, return it.
	 * Must be called in a finally block after sync completes.
	 */
	public synchronized SyncRequest endSync()
	{
		syncInProgress = false;
		SyncRequest pending = pendingRequest;
		pendingRequest = null;
		if (pending != null)
		{
			log.debug("[Sync] Releasing slot with pending: {}", pending);
		}
		return pending;
	}

	/**
	 * Get the current state for UI display.
	 */
	public synchronized boolean isSyncRunning()
	{
		return syncInProgress;
	}

	/**
	 * Get pending request without clearing it (for UI updates).
	 */
	public synchronized SyncRequest getPendingRequest()
	{
		return pendingRequest;
	}
}
