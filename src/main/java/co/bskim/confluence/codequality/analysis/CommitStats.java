package co.bskim.confluence.codequality.analysis;

/** Everything the report needs about one commit. */
public final class CommitStats
{
    public String sha;
    public String subject;
    public String authorKey;
    public String authorName;
    public String authorEmail;
    public long committedAt;

    /** True for the root commit and for any commit that dumps in a whole codebase at once. */
    public boolean importCommit;

    public int added;
    public int novel;
    public int copied;
    public int moved;
    public int deleted;

    public int churn;
    /** The 14-day window has not closed: churn here means "not known yet", not "zero". */
    public boolean churnCensored;

    /** Static metrics; -1 when this commit was not one of the sampled points. */
    public int loc = -1;
    public int files = -1;
    public int dupLines = -1;
    public int dupClones = -1;
    /** Lines the duplication ratio was measured over: this tree less any mirror subtree. */
    public int dupMeasuredLines = -1;
    /**
     * Normalised lines the parent tree held, which is the denominator of the bulk-import test.
     *
     * <p>Run-local and deliberately not cached: the import verdict is decided during replay
     * and stored as {@link #importCommit}, so nothing later needs this. It is here so the
     * decision can be audited - "added 4,124 lines to a tree of 0" is checkable, "this was an
     * import" is not.</p>
     */
    public int parentLines = -1;
    public int errSwallow = -1;
    public int calls = -1;
    /** Legacy-metric inputs, sampled alongside the rest; -1 when not sampled. */
    public int commentLines = -1;
    public int totalLines = -1;
    public int functions = -1;
    public int decisions = -1;
    public int functionLines = -1;

    public boolean sampled()
    {
        return loc >= 0;
    }

    public double copyRatio()
    {
        return added == 0 ? 0 : (double) copied / added;
    }

    public double churnRatio()
    {
        return added == 0 ? 0 : (double) churn / added;
    }
}
