package com.runealytics;

/**
 * Represents a synchronization request with priority and merge semantics.
 * Used by SyncCoordinator to coalesce multiple requests into a single operation.
 */
public final class SyncRequest
{
	public enum Priority
	{
		/** User explicitly clicked Sync button - highest priority */
		MANUAL(3),
		/** Logout - special case, usually lightweight upload-only */
		LOGOUT(2),
		/** Automatic post-login reconciliation */
		LOGIN(1),
		/** Automatic periodic/heartbeat-related sync */
		AUTO(0);

		public final int level;
		Priority(int level) { this.level = level; }
	}

	private final Priority priority;
	private final boolean fullReconcile;
	private final String reason;
	private final long createdAtMs;

	public SyncRequest(Priority priority, boolean fullReconcile, String reason)
	{
		this.priority = priority;
		this.fullReconcile = fullReconcile;
		this.reason = reason;
		this.createdAtMs = System.currentTimeMillis();
	}

	public Priority getPriority() { return priority; }
	public boolean isFullReconcile() { return fullReconcile; }
	public String getReason() { return reason; }
	public long getCreatedAtMs() { return createdAtMs; }
	public long getAgeMs() { return System.currentTimeMillis() - createdAtMs; }

	/**
	 * Merge this request with another pending request.
	 * The result satisfies the requirements of both.
	 */
	public SyncRequest mergeWith(SyncRequest other)
	{
		if (other == null) return this;

		Priority resultPriority = this.priority.level > other.priority.level
			? this.priority
			: other.priority;

		boolean resultFull = this.fullReconcile || other.fullReconcile;

		String resultReason = this.reason + " + " + other.reason;

		return new SyncRequest(resultPriority, resultFull, resultReason);
	}

	@Override
	public String toString()
	{
		return String.format("SyncRequest{priority=%s full=%s reason='%s' age=%dms}",
			priority, fullReconcile, reason, getAgeMs());
	}
}
