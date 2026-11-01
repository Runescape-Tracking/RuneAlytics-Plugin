package com.runealytics.progress;

/**
 * Per-category coverage labels sent to the website.
 *
 * <p>Coverage tells the server how to treat records that are <em>missing</em> from
 * a batch. Only {@link #COMPLETE} means "this is every record in the category";
 * every other value means missing records must be left untouched.</p>
 */
public final class ProgressCoverage
{
    /** Every record in the category was read and passed the sanity checks. */
    public static final String COMPLETE = "complete";

    /** The category could not be read reliably; nothing in it may be changed. */
    public static final String UNAVAILABLE = "unavailable";

    /** Raw values were read, but the cross-check against game totals did not match. */
    public static final String RAW_UNVERIFIED = "raw_unverified";

    /** Delta batch: only the records that changed are included. */
    public static final String PARTIAL_CHANGED_ONLY = "partial_changed_only";

    /** RuneAlytics does not capture this category yet. */
    public static final String NOT_SUPPORTED = "not_supported";

    private ProgressCoverage()
    {
    }

    /** True when values of this coverage may update the local baseline. */
    static boolean isUsable(String coverage)
    {
        return COMPLETE.equals(coverage) || RAW_UNVERIFIED.equals(coverage);
    }
}
