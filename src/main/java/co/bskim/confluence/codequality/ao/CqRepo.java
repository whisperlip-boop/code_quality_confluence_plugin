package co.bskim.confluence.codequality.ao;

import net.java.ao.Entity;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

/**
 * A registered remote repository. Global scope: one row is visible from every macro and from
 * the admin screen, so the same repository is never cloned or analysed twice.
 */
@Table("REPO")
public interface CqRepo extends Entity
{
    @StringLength(255)
    String getName();
    void setName(String name);

    @StringLength(450)
    String getUrl();
    void setUrl(String url);

    /** Empty means "whatever the remote HEAD points at". */
    @StringLength(255)
    String getBranch();
    void setBranch(String branch);

    /** NONE or PAT. */
    @StringLength(16)
    String getAuthType();
    void setAuthType(String authType);

    @StringLength(255)
    String getAuthUser();
    void setAuthUser(String authUser);

    /** AES-encrypted personal access token; never leaves the server. */
    @StringLength(StringLength.UNLIMITED)
    String getAuthSecret();
    void setAuthSecret(String authSecret);

    /**
     * Space keys this repository is visible in, comma separated.
     *
     * <p>Empty means administrators only. Fail-closed on purpose: a repository nobody linked
     * yet holds a private codebase's file paths, commit subjects and author addresses, and the
     * safe reading of "not configured" is "not shared".</p>
     */
    @StringLength(StringLength.UNLIMITED)
    String getSpaceKeys();
    void setSpaceKeys(String spaceKeys);

    /** Newline-separated path globs excluded from analysis, on top of the built-in defaults. */
    @StringLength(StringLength.UNLIMITED)
    String getExcludes();
    void setExcludes(String excludes);

    /** JSON threshold overrides; empty means the shipped defaults. */
    @StringLength(StringLength.UNLIMITED)
    String getThresholds();
    void setThresholds(String thresholds);

    Long getLastSyncedAt();
    void setLastSyncedAt(Long lastSyncedAt);

    /** NEW, QUEUED, RUNNING, OK or FAILED. */
    @StringLength(16)
    String getStatus();
    void setStatus(String status);

    @StringLength(StringLength.UNLIMITED)
    String getStatusMessage();
    void setStatusMessage(String statusMessage);

    /**
     * When the status was last written or a live job last said it was still working.
     *
     * <p>A RUNNING row is a claim that some node is working on this repository, and nothing
     * used to withdraw that claim when the node stopped: an Error inside the job, a kill, a
     * restart, and the row stayed RUNNING for good, with the Analyze button disabled because
     * of it. A timestamp is what makes the claim checkable. A live job refreshes it, so the
     * check is safe on a cluster too - a run on another node keeps its own row fresh, and only
     * a claim nobody has renewed is treated as abandoned.</p>
     */
    Long getStatusAt();
    void setStatusAt(Long statusAt);

    @StringLength(255)
    String getCreatedBy();
    void setCreatedBy(String createdBy);

    long getCreatedAt();
    void setCreatedAt(long createdAt);
}
