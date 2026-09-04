package co.bskim.confluence.codequality.service;

import co.bskim.confluence.codequality.analysis.AnalysisConfig;
import co.bskim.confluence.codequality.analysis.AnalysisEngine;
import co.bskim.confluence.codequality.analysis.CommitStats;
import co.bskim.confluence.codequality.analysis.DuplicateDetector;
import co.bskim.confluence.codequality.ao.CqClone;
import co.bskim.confluence.codequality.ao.CqCommit;
import co.bskim.confluence.codequality.ao.CqRepo;
import co.bskim.confluence.codequality.ao.CqRun;
import co.bskim.confluence.codequality.git.RemoteUrl;
import co.bskim.confluence.codequality.git.RepoAuth;
import co.bskim.confluence.codequality.model.RepoSnapshot;
import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistence for registered repositories, their cached commit metrics and their reports.
 *
 * <p><b>Every</b> database access here goes through SAL's {@link TransactionTemplate}, reads
 * included. Analyses run on a background thread, and on Confluence that thread has no Hibernate
 * session bound to it - {@code ActiveObjects.executeInTransaction} alone fails there with
 * "No Hibernate Session bound to thread", which is exactly how the first install failed. The
 * template establishes the session; on a request thread it simply joins the one already open.
 * Any new method that touches Active Objects has to be wrapped the same way.</p>
 */
@Named
public class RepositoryService
{
    private static final Logger log = LoggerFactory.getLogger(RepositoryService.class);

    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";

    private final ActiveObjects ao;
    private final TokenCipher cipher;
    private final TransactionTemplate transactions;

    @Inject
    public RepositoryService(@ComponentImport ActiveObjects ao, TokenCipher cipher,
                             @ComponentImport TransactionTemplate transactions)
    {
        this.ao = ao;
        this.cipher = cipher;
        this.transactions = transactions;
    }

    // ------------------------------------------------------------------ CRUD

    public List<RepoSnapshot> all()
    {
        return transactions.execute(() ->
        {
            List<RepoSnapshot> repos = new ArrayList<RepoSnapshot>();
            for (CqRepo row : ao.find(CqRepo.class))
            {
                repos.add(snapshot(row));
            }
            Collections.sort(repos, new Comparator<RepoSnapshot>()
            {
                @Override
                public int compare(RepoSnapshot a, RepoSnapshot b)
                {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
            return repos;
        });
    }

    /** Every registered repository's id, for the clone-directory sweep. */
    public Set<Integer> allIds()
    {
        return transactions.execute(() ->
        {
            Set<Integer> ids = new HashSet<Integer>();
            for (CqRepo row : ao.find(CqRepo.class))
            {
                ids.add(row.getID());
            }
            return ids;
        });
    }

    public RepoSnapshot byId(final int id)
    {
        return transactions.execute(() -> snapshot(ao.get(CqRepo.class, id)));
    }

    /** Copies an entity's values out while the transaction is still open. */
    private static RepoSnapshot snapshot(CqRepo row)
    {
        if (row == null)
        {
            return null;
        }
        // Stripped here as well as on save, so a row written before validation existed cannot
        // hand a token to the REST list or the report page.
        return new RepoSnapshot(row.getID(), row.getName(),
                RemoteUrl.sanitiseForDisplay(row.getUrl()), row.getBranch(),
                row.getAuthType(), row.getAuthUser(), row.getAuthSecret(),
                row.getSpaceKeys(), row.getExcludes(),
                row.getThresholds(), row.getLastSyncedAt(), row.getStatus(),
                row.getStatusMessage());
    }

    public RepoSnapshot create(final String name, final String url, final String branch,
                         final String authUser, final String token, final String spaceKeys,
                         final String excludes, final String thresholds, final String createdBy)
    {
        return transactions.execute(() ->
        {
            CqRepo repo = ao.create(CqRepo.class);
            repo.setName(name);
            repo.setUrl(url);
            repo.setBranch(branch);
            repo.setAuthUser(authUser);
            repo.setAuthType(token == null || token.isEmpty() ? "NONE" : "PAT");
            repo.setAuthSecret(cipher.encrypt(token));
            repo.setSpaceKeys(spaceKeys);
            repo.setExcludes(excludes);
            repo.setThresholds(thresholds);
            repo.setStatus(STATUS_NEW);
            repo.setStatusMessage("");
            repo.setCreatedBy(createdBy);
            repo.setCreatedAt(System.currentTimeMillis());
            repo.save();
            return snapshot(repo);
        });
    }

    /** A null {@code token} leaves the stored one alone; an empty string clears it. */
    public RepoSnapshot update(final int id, final String name, final String url,
                         final String branch, final String authUser, final String token,
                         final String spaceKeys, final String excludes,
                         final String thresholds)
    {
        return transactions.execute(() ->
        {
            CqRepo repo = ao.get(CqRepo.class, id);
            if (repo == null)
            {
                return null;
            }
            // Canonical comparison, the same one GitClient uses to decide whether its clone
            // still belongs to this registration. The two must agree: dropping the cache while
            // keeping the clone (or the reverse) leaves the report half from each remote.
            // Comparing the raw strings also replayed an entire history when somebody added or
            // removed a trailing ".git".
            boolean remoteChanged = !RemoteUrl.canonical(repo.getUrl())
                    .equals(RemoteUrl.canonical(url));
            repo.setName(name);
            repo.setUrl(url);
            repo.setBranch(branch);
            repo.setAuthUser(authUser);
            repo.setSpaceKeys(spaceKeys);
            repo.setExcludes(excludes);
            repo.setThresholds(thresholds);
            if (token != null)
            {
                repo.setAuthType(token.isEmpty() ? "NONE" : "PAT");
                repo.setAuthSecret(cipher.encrypt(token));
            }
            if (remoteChanged)
            {
                // The cache is keyed by sha, and a different remote is a different history.
                dropCache(id);
                repo.setStatus(STATUS_NEW);
                repo.setStatusMessage("");
                repo.setLastSyncedAt(null);
            }
            repo.save();
            return snapshot(repo);
        });
    }

    public void delete(final int id)
    {
        transactions.execute(() ->
        {
            dropCache(id);
            CqRepo repo = ao.get(CqRepo.class, id);
            if (repo != null)
            {
                ao.delete(repo);
            }
            return null;
        });
    }

    private void dropCache(int repoId)
    {
        ao.delete(ao.find(CqCommit.class, Query.select().where("REPO_ID = ?", repoId)));
        ao.delete(ao.find(CqClone.class, Query.select().where("REPO_ID = ?", repoId)));
        ao.delete(ao.find(CqRun.class, Query.select().where("REPO_ID = ?", repoId)));
    }

    public RepoAuth authFor(final RepoSnapshot repo)
    {
        if (repo == null || !repo.hasToken())
        {
            return RepoAuth.NONE;
        }
        return new RepoAuth(repo.authUser, cipher.decrypt(repo.authSecret));
    }

    // ------------------------------------------------------------------ runs

    /** The stored report payload, or null when this repository has never completed a run. */
    public String reportJson(final int repoId)
    {
        return transactions.execute(() ->
        {
            CqRun run = lastOkRun(repoId);
            if (run == null)
            {
                return null;
            }
            String json = run.getReportJson();
            return json == null || json.isEmpty() ? null : json;
        });
    }

    /** When the last successful run finished, which sets the incremental replay floor. */
    public long lastRunFinishedAt(final int repoId)
    {
        return transactions.execute(() ->
        {
            CqRun run = lastOkRun(repoId);
            Long finished = run == null ? null : run.getFinishedAt();
            return finished == null ? 0L : finished;
        });
    }

    private CqRun lastOkRun(int repoId)
    {
        CqRun[] runs = ao.find(CqRun.class, Query.select()
                .where("REPO_ID = ? AND STATUS = ?", repoId, "OK")
                .order("STARTED_AT DESC").limit(1));
        return runs.length == 0 ? null : runs[0];
    }

    public void markStatus(final int repoId, final String status, final String message)
    {
        transactions.execute(() ->
        {
            CqRepo repo = ao.get(CqRepo.class, repoId);
            if (repo != null)
            {
                repo.setStatus(status);
                repo.setStatusMessage(message == null ? "" : message);
                repo.setStatusAt(System.currentTimeMillis());
                repo.save();
            }
            return null;
        });
    }

    /**
     * Says that a job is still working, without changing what it is doing.
     *
     * <p>What makes a RUNNING row checkable - see {@link CqRepo#getStatusAt}. Called on a timer
     * while a job is live, including during the clone, which reports no progress of its own and
     * is the longest a run can go without saying anything.</p>
     */
    public void touchStatus(final Collection<Integer> repoIds)
    {
        if (repoIds.isEmpty())
        {
            return;
        }
        transactions.execute(() ->
        {
            long now = System.currentTimeMillis();
            for (Integer repoId : repoIds)
            {
                CqRepo repo = ao.get(CqRepo.class, repoId);
                if (repo != null)
                {
                    repo.setStatusAt(now);
                    repo.save();
                }
            }
            return null;
        });
    }

    /**
     * Fails rows that claim to be running but that nobody has renewed.
     *
     * <p>Run once at startup. A row left RUNNING by a node that died is otherwise permanent:
     * the client disables Analyze for a running repository, so there is no way back through the
     * interface, and the administrator has to be told to edit the database. Rows written before
     * this column existed have no timestamp and are treated as abandoned, which they are - no
     * process from that era survives.</p>
     *
     * @param staleAfterMillis how long a claim may go unrenewed before it is not believed
     * @return how many rows were released
     */
    public int failStaleRunning(final long staleAfterMillis)
    {
        return transactions.execute(() ->
        {
            long cutoff = System.currentTimeMillis() - staleAfterMillis;
            int released = 0;
            for (CqRepo repo : ao.find(CqRepo.class, Query.select()
                    .where("STATUS = ? OR STATUS = ?", STATUS_RUNNING, STATUS_QUEUED)))
            {
                Long at = repo.getStatusAt();
                if (at != null && at > cutoff)
                {
                    continue;
                }
                repo.setStatus(STATUS_FAILED);
                repo.setStatusMessage("Interrupted");
                repo.setStatusAt(System.currentTimeMillis());
                repo.save();
                released++;
            }
            return released;
        });
    }

    /**
     * Cached rows keyed by sha, but only those produced by the current algorithm version.
     * Mixing versions inside one trend line would make the trend an artefact of the tooling.
     */
    public Map<String, CommitStats> cachedCommits(final int repoId)
    {
        return transactions.execute(() ->
        {
            Map<String, CommitStats> cached = new HashMap<String, CommitStats>();
            CqCommit[] rows = ao.find(CqCommit.class,
                    Query.select().where("REPO_ID = ?", repoId));
            for (CqCommit row : rows)
            {
                if (row.getAlgoVersion() != AnalysisConfig.ALGO_VERSION)
                {
                    continue;
                }
                CommitStats stats = new CommitStats();
                stats.sha = row.getSha();
                stats.subject = row.getSubject();
                stats.authorKey = row.getAuthorKey();
                stats.authorName = row.getAuthorName();
                stats.committedAt = row.getCommittedAt();
                stats.importCommit = row.isImportCommit();
                stats.added = row.getAddedLines();
                stats.copied = row.getCopyLines();
                stats.moved = row.getMovedLines();
                stats.novel = row.getNewLines();
                stats.deleted = row.getDeletedLines();
                stats.churn = row.getChurnLines();
                stats.churnCensored = row.isChurnCensored();
                stats.loc = row.getLoc();
                stats.files = row.getFileCount();
                stats.dupLines = row.getDupLines();
                stats.dupClones = row.getDupClones();
                stats.errSwallow = row.getErrSwallow();
                stats.calls = row.getCallCount();
                stats.commentLines = row.getCommentLines();
                stats.totalLines = row.getTotalLines();
                stats.functions = row.getFunctions();
                stats.decisions = row.getDecisions();
                stats.functionLines = row.getFunctionLines();
                cached.put(stats.sha, stats);
            }
            return cached;
        });
    }

    /** Stores a successful run: replayed commit rows, the HEAD clone list and the report. */
    public void persistSuccess(final int repoId, final AnalysisEngine.Outcome outcome,
                               final String reportJson, final long startedAt)
    {
        transactions.execute(() ->
        {
            if (ao.get(CqRepo.class, repoId) == null)
            {
                // Deleted while the analysis was running. Writing the rows anyway left
                // CqCommit, CqClone and CqRun rows belonging to a repository that is gone -
                // invisible in the UI, and counted by anything that scans those tables.
                log.info("Repository {} was deleted during its analysis; discarding the result",
                        repoId);
                return null;
            }

            Set<String> current = new HashSet<String>();
            for (CommitStats row : outcome.commits)
            {
                if (row != null)
                {
                    current.add(row.sha);
                }
            }
            Set<String> replayed = new HashSet<String>();
            for (int i = outcome.replayedFrom; i < outcome.commits.size(); i++)
            {
                CommitStats row = outcome.commits.get(i);
                if (row != null)
                {
                    replayed.add(row.sha);
                }
            }

            // Rows that were recomputed are replaced; rows for commits that vanished (a force
            // push or a rebase) are dropped rather than left to poison the timeline.
            List<CqCommit> stale = new ArrayList<CqCommit>();
            for (CqCommit row : ao.find(CqCommit.class,
                    Query.select().where("REPO_ID = ?", repoId)))
            {
                if (!current.contains(row.getSha()) || replayed.contains(row.getSha())
                        || row.getAlgoVersion() != AnalysisConfig.ALGO_VERSION)
                {
                    stale.add(row);
                }
            }
            ao.delete(stale.toArray(new CqCommit[0]));

            for (int i = outcome.replayedFrom; i < outcome.commits.size(); i++)
            {
                CommitStats stats = outcome.commits.get(i);
                if (stats == null)
                {
                    continue;
                }
                CqCommit row = ao.create(CqCommit.class);
                row.setRepoId(repoId);
                row.setSha(stats.sha);
                row.setSubject(stats.subject);
                row.setAuthorKey(stats.authorKey);
                row.setAuthorName(stats.authorName);
                row.setCommittedAt(stats.committedAt);
                row.setAlgoVersion(AnalysisConfig.ALGO_VERSION);
                row.setImportCommit(stats.importCommit);
                row.setAddedLines(stats.added);
                row.setCopyLines(stats.copied);
                row.setMovedLines(stats.moved);
                row.setNewLines(stats.novel);
                row.setDeletedLines(stats.deleted);
                row.setChurnLines(stats.churn);
                row.setChurnCensored(stats.churnCensored);
                row.setLoc(stats.loc);
                row.setFileCount(stats.files);
                row.setDupLines(stats.dupLines);
                row.setDupClones(stats.dupClones);
                row.setErrSwallow(stats.errSwallow);
                row.setCallCount(stats.calls);
                row.setCommentLines(stats.commentLines);
                row.setTotalLines(stats.totalLines);
                row.setFunctions(stats.functions);
                row.setDecisions(stats.decisions);
                row.setFunctionLines(stats.functionLines);
                row.save();
            }

            ao.delete(ao.find(CqClone.class, Query.select().where("REPO_ID = ?", repoId)));
            int stored = 0;
            for (DuplicateDetector.CloneHit hit : outcome.headClones)
            {
                if (stored++ >= 60)
                {
                    break;
                }
                CqClone clone = ao.create(CqClone.class);
                clone.setRepoId(repoId);
                clone.setFileA(hit.fileA);
                clone.setLineA(hit.lineA);
                clone.setFileB(hit.fileB);
                clone.setLineB(hit.lineB);
                clone.setLines(hit.lines);
                clone.save();
            }

            ao.delete(ao.find(CqRun.class, Query.select().where("REPO_ID = ?", repoId)));
            CqRun run = ao.create(CqRun.class);
            run.setRepoId(repoId);
            run.setStartedAt(startedAt);
            run.setFinishedAt(System.currentTimeMillis());
            run.setStatus("OK");
            run.setHeadSha(outcome.headSha);
            run.setAlgoVersion(AnalysisConfig.ALGO_VERSION);
            run.setReportJson(reportJson);
            run.setError("");
            run.save();

            CqRepo repo = ao.get(CqRepo.class, repoId);
            if (repo != null)
            {
                repo.setStatus(STATUS_OK);
                repo.setStatusMessage("");
                repo.setLastSyncedAt(System.currentTimeMillis());
                repo.save();
            }
            return null;
        });
    }

    /**
     * Which repositories have a stored report, in one transaction.
     *
     * <p>The list endpoint used to ask {@link #hasReport} per row, and the macro polls every
     * two seconds - forty repositories on one dashboard is twenty transactions a second doing
     * nothing but repeating the same table scan.</p>
     */
    public Set<Integer> repoIdsWithReports()
    {
        return transactions.execute(() ->
        {
            Set<Integer> withReports = new HashSet<Integer>();
            // Two columns, and neither of them the report. It used to select whole rows and
            // test getReportJson() for emptiness, which reads an unlimited CLOB per OK run to
            // answer a question about its existence: a 20,000-commit repository's report is
            // megabytes, thirty registrations is tens of them, and this runs on every page
            // that hosts the macro. Existence is what the row already knows - persistSuccess
            // writes the status and the report in one transaction, so an OK run with no report
            // is not a state this code produces.
            for (CqRun run : ao.find(CqRun.class, Query.select("REPO_ID")
                    .where("STATUS = ? AND REPORT_JSON IS NOT NULL", "OK")))
            {
                withReports.add(run.getRepoId());
            }
            return withReports;
        });
    }

    public boolean hasReport(final int repoId)
    {
        return transactions.execute(() ->
        {
            CqRun run = lastOkRun(repoId);
            String json = run == null ? null : run.getReportJson();
            return json != null && !json.isEmpty();
        });
    }
}
