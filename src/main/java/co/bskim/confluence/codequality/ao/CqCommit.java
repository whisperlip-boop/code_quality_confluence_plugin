package co.bskim.confluence.codequality.ao;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

/**
 * Per-commit metrics cache. Rows survive between runs so that only new commits (plus the
 * churn window that new commits can still change) are recomputed.
 */
@Table("CMETRIC")
public interface CqCommit extends Entity
{
    @Indexed
    int getRepoId();
    void setRepoId(int repoId);

    @StringLength(64)
    String getSha();
    void setSha(String sha);

    long getCommittedAt();
    void setCommittedAt(long committedAt);

    /** Canonical identity after .mailmap and heuristic merging. */
    @StringLength(255)
    String getAuthorKey();
    void setAuthorKey(String authorKey);

    @StringLength(255)
    String getAuthorName();
    void setAuthorName(String authorName);

    @StringLength(450)
    String getSubject();
    void setSubject(String subject);

    int getAlgoVersion();
    void setAlgoVersion(int algoVersion);

    /** Bulk import commits are kept for the timeline but excluded from every ratio. */
    boolean isImportCommit();
    void setImportCommit(boolean importCommit);

    /** Countable added lines: comment-stripped, non-blank and long enough to be meaningful. */
    int getAddedLines();
    void setAddedLines(int addedLines);

    int getCopyLines();
    void setCopyLines(int copyLines);

    int getMovedLines();
    void setMovedLines(int movedLines);

    int getNewLines();
    void setNewLines(int newLines);

    int getDeletedLines();
    void setDeletedLines(int deletedLines);

    int getChurnLines();
    void setChurnLines(int churnLines);

    /**
     * True when the 14-day observation window has not closed yet. Such a commit's churn is
     * "not known", not "zero" - the report must grey it out rather than reward it.
     */
    boolean isChurnCensored();
    void setChurnCensored(boolean churnCensored);

    /** Static metrics are sampled; -1 marks a commit that was not sampled. */
    int getLoc();
    void setLoc(int loc);

    int getFileCount();
    void setFileCount(int fileCount);

    int getDupLines();
    void setDupLines(int dupLines);

    int getDupClones();
    void setDupClones(int dupClones);

    int getErrSwallow();
    void setErrSwallow(int errSwallow);

    int getCallCount();
    void setCallCount(int callCount);

    /**
     * Inputs for the legacy metrics shown alongside the new ones. Added in algorithm
     * version 2; Active Objects creates the columns on upgrade and the version bump forces a
     * recompute, so old rows are never mixed in.
     */
    int getCommentLines();
    void setCommentLines(int commentLines);

    int getTotalLines();
    void setTotalLines(int totalLines);

    int getFunctions();
    void setFunctions(int functions);

    int getDecisions();
    void setDecisions(int decisions);

    int getFunctionLines();
    void setFunctionLines(int functionLines);
}
