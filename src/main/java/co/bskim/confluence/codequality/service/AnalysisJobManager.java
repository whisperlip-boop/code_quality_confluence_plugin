package co.bskim.confluence.codequality.service;

import co.bskim.confluence.codequality.analysis.AnalysisConfig;
import co.bskim.confluence.codequality.ao.CqRepo;
import co.bskim.confluence.codequality.analysis.AnalysisEngine;
import co.bskim.confluence.codequality.analysis.PathMatcher;
import co.bskim.confluence.codequality.analysis.Thresholds;
import co.bskim.confluence.codequality.git.GitClient;
import co.bskim.confluence.codequality.git.RemoteUrl;
import co.bskim.confluence.codequality.model.RepoSnapshot;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs analyses off the request thread, one at a time.
 *
 * <p>Single-threaded on purpose: a run is a clone plus a full history walk, and two of those at
 * once on a Confluence node is how a reporting feature turns into an outage. Queued repositories
 * wait their turn, which is fine because nobody is watching a progress bar for four minutes.</p>
 */
@Named
public class AnalysisJobManager implements InitializingBean, DisposableBean
{
    private static final Logger log = LoggerFactory.getLogger(AnalysisJobManager.class);

    public static final class JobState
    {
        public volatile String status = RepositoryService.STATUS_QUEUED;
        public volatile String phase = "queued";
        public volatile int current;
        public volatile int total;
        public volatile String message = "";
        public volatile long startedAt = System.currentTimeMillis();
        volatile boolean cancelled;
    }

    /** How often a live job says it is still working - see {@link CqRepo#getStatusAt}. */
    private static final long HEARTBEAT_SECONDS = 60;
    /**
     * How long a RUNNING row may go unrenewed before startup stops believing it.
     *
     * <p>Generous against the heartbeat, because the cost of the two mistakes is not the same:
     * releasing a row a live job still owns would let a second run start against the same
     * clone, while leaving one held a few minutes longer costs a wait.</p>
     */
    private static final long STALE_AFTER_MILLIS = 10 * 60 * 1000L;

    private final Map<Integer, JobState> jobs = new ConcurrentHashMap<Integer, JobState>();
    private final RepositoryService repositories;
    private final GitClient gitClient;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeat;

    @Inject
    public AnalysisJobManager(RepositoryService repositories, GitClient gitClient)
    {
        this.repositories = repositories;
        this.gitClient = gitClient;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory()
        {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable)
            {
                Thread thread = new Thread(runnable,
                        "code-quality-analysis-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "code-quality-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void afterPropertiesSet()
    {
        // From now, not only at startup. A row left RUNNING by a node that died still has a
        // fresh timestamp for the first few minutes, so a startup-only sweep would believe it
        // and then never look again - the row would be stuck exactly as before. Reconciling on
        // a timer is also what makes the claim mean something on a cluster.
        heartbeat.scheduleWithFixedDelay(this::reconcileStatuses, 0, HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);
    }

    /** Repositories a job here is actually working on - finished entries linger in the map. */
    private List<Integer> liveRepoIds()
    {
        List<Integer> live = new ArrayList<Integer>();
        for (Map.Entry<Integer, JobState> entry : jobs.entrySet())
        {
            String status = entry.getValue().status;
            if (RepositoryService.STATUS_QUEUED.equals(status)
                    || RepositoryService.STATUS_RUNNING.equals(status))
            {
                live.add(entry.getKey());
            }
        }
        return live;
    }

    /**
     * Renews the claim of every live job, then withdraws the ones nobody renewed.
     *
     * <p>A RUNNING row says some node is working on this repository. The first half is how a
     * job here keeps saying so - including during the clone, which reports no progress of its
     * own and is the longest a run can go without a word. The second half is what finally
     * releases a row whose node stopped: an Error, a kill, a restart. Without it the row is
     * permanent, and because the client disables Analyze for a running repository there is no
     * way back through the interface at all.</p>
     *
     * <p>Live first, then stale, so a job on this node can never release its own row. Failure
     * is logged and swallowed: this runs every minute, and the next pass will do.</p>
     */
    private void reconcileStatuses()
    {
        try
        {
            repositories.touchStatus(liveRepoIds());
            int released = repositories.failStaleRunning(STALE_AFTER_MILLIS);
            if (released > 0)
            {
                log.info("Released {} repository row(s) left running by a stopped node",
                        released);
            }
        }
        catch (Exception e)
        {
            log.debug("Could not reconcile repository statuses", e);
        }
    }

    /**
     * @return false when a run for this repository is already queued or in flight
     *
     * <p>The claim is one atomic map operation. It used to be get, decide, put: a double click
     * or a race with the two-second poller queued the same repository twice, and then the
     * second job's fresh {@code JobState} replaced the running one's, so progress reported
     * QUEUED at 0% for the whole of the first run.</p>
     */
    public boolean submit(final int repoId)
    {
        final JobState state = new JobState();
        JobState claimed = jobs.compute(repoId, (key, existing) ->
        {
            if (existing != null && (RepositoryService.STATUS_QUEUED.equals(existing.status)
                    || RepositoryService.STATUS_RUNNING.equals(existing.status)))
            {
                return existing;
            }
            return state;
        });
        if (claimed != state)
        {
            return false;
        }

        repositories.markStatus(repoId, RepositoryService.STATUS_QUEUED, "");

        executor.submit(() ->
        {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            // JGit resolves its transports and config parsers through the context loader, and a
            // pooled thread does not inherit the bundle's.
            Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
            try
            {
                run(repoId, state);
            }
            catch (AnalysisEngine.CancelledException e)
            {
                state.status = RepositoryService.STATUS_FAILED;
                state.message = "Cancelled";
                repositories.markStatus(repoId, RepositoryService.STATUS_FAILED, "Cancelled");
            }
            catch (Throwable e)
            {
                // Throwable, not Exception. An OutOfMemoryError is the failure the clone
                // ceiling exists to prevent, and it used to escape into a Future nobody reads:
                // no log line, and the row stayed RUNNING for good with Analyze disabled
                // because of it. Recording it costs one small write, which is worth attempting
                // even on a node that may now be unwell.
                log.error("Code quality analysis failed for repository {}", repoId, e);
                String message = describe(e);
                state.status = RepositoryService.STATUS_FAILED;
                state.message = message;
                repositories.markStatus(repoId, RepositoryService.STATUS_FAILED, message);
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(original);
                sweepOrphanClones();
            }
        });
        return true;
    }

    private void run(int repoId, JobState state) throws Exception
    {
        RepoSnapshot repo = repositories.byId(repoId);
        if (repo == null)
        {
            throw new IllegalStateException("Repository " + repoId + " no longer exists");
        }

        state.status = RepositoryService.STATUS_RUNNING;
        state.phase = "fetch";
        repositories.markStatus(repoId, RepositoryService.STATUS_RUNNING, "");
        long startedAt = System.currentTimeMillis();

        // Re-checked here, not only when the row was saved. A name that resolved to a real
        // host in March can resolve to this node's own loopback in September, and the stored
        // URL would be fetched regardless. This does not make the check atomic with the
        // connection - RemoteUrl's class comment says what that would take and why it is not
        // done - but the answer has to be acceptable now rather than once. It also catches a
        // row written before scheme validation existed.
        RemoteUrl.revalidate(repo.url);

        Repository git = gitClient.sync(repoId, repo.url, repositories.authFor(repo));
        try
        {
            AnalysisEngine.Progress progress = new AnalysisEngine.Progress()
            {
                @Override
                public void report(String phase, int current, int total)
                {
                    state.phase = phase;
                    state.current = current;
                    state.total = total;
                }

                @Override
                public boolean cancelled()
                {
                    return state.cancelled;
                }
            };

            PathMatcher excludes =
                    new PathMatcher(AnalysisConfig.DEFAULT_EXCLUDES, repo.excludes);
            long previousRunAt = repositories.lastRunFinishedAt(repoId);

            AnalysisEngine engine = new AnalysisEngine(git, excludes, progress);
            AnalysisEngine.Outcome outcome = engine.analyse(
                    repo.branch, repositories.cachedCommits(repoId), previousRunAt, startedAt);

            state.phase = "report";
            Thresholds thresholds = Thresholds.parse(repo.thresholds);
            String reportJson = ReportBuilder.build(repo.name, repo.url, outcome,
                    thresholds, System.currentTimeMillis());

            state.phase = "store";
            repositories.persistSuccess(repoId, outcome, reportJson, startedAt);

            state.status = RepositoryService.STATUS_OK;
            state.phase = "done";
            state.message = "";
        }
        finally
        {
            git.close();
        }
    }

    /**
     * Removes clone directories no repository owns, after every run.
     *
     * <p>Here rather than on the delete path because this is also where a clone interrupted by
     * a node restart gets cleaned up, and because a delete that races an in-flight clone can
     * only be tidied after that clone finishes. Failure is logged and swallowed: a run that
     * succeeded must not be reported as failed because housekeeping did not.</p>
     */
    private void sweepOrphanClones()
    {
        try
        {
            int removed = gitClient.discardOrphans(repositories.allIds());
            if (removed > 0)
            {
                log.info("Removed {} orphaned clone directory/ies", removed);
            }
        }
        catch (RuntimeException e)
        {
            log.warn("Could not sweep orphaned clone directories", e);
        }
    }

    public JobState state(int repoId)
    {
        return jobs.get(repoId);
    }

    public void cancel(int repoId)
    {
        JobState state = jobs.get(repoId);
        if (state != null)
        {
            state.cancelled = true;
        }
    }

    public void forget(int repoId)
    {
        jobs.remove(repoId);
    }

    private static String describe(Throwable e)
    {
        String message = e.getMessage();
        if (message == null || message.isEmpty())
        {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    @Override
    public void destroy()
    {
        for (JobState state : jobs.values())
        {
            state.cancelled = true;
        }
        heartbeat.shutdownNow();
        executor.shutdownNow();
        try
        {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
