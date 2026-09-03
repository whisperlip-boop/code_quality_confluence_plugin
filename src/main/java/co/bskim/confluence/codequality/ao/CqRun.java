package co.bskim.confluence.codequality.ao;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

/** One completed (or failed) analysis pass over a repository. */
@Table("RUN")
public interface CqRun extends Entity
{
    @Indexed
    int getRepoId();
    void setRepoId(int repoId);

    long getStartedAt();
    void setStartedAt(long startedAt);

    Long getFinishedAt();
    void setFinishedAt(Long finishedAt);

    /** OK or FAILED. */
    @StringLength(16)
    String getStatus();
    void setStatus(String status);

    @StringLength(64)
    String getHeadSha();
    void setHeadSha(String headSha);

    /**
     * Algorithm version the numbers were produced with. Bumping
     * {@code AnalysisConfig.ALGO_VERSION} invalidates every cached row, because a trend line
     * mixing two algorithm versions is meaningless.
     */
    int getAlgoVersion();
    void setAlgoVersion(int algoVersion);

    /** Locale-independent report payload; the servlet localises it at render time. */
    @StringLength(StringLength.UNLIMITED)
    String getReportJson();
    void setReportJson(String reportJson);

    @StringLength(StringLength.UNLIMITED)
    String getError();
    void setError(String error);
}
