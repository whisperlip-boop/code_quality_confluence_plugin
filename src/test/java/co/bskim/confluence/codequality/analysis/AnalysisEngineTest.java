package co.bskim.confluence.codequality.analysis;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Engine tests over a real repository built on the fly.
 *
 * <p>The two properties here are the ones the product's claim rests on: churn is censored
 * rather than reported as zero while its window is still open, and an incremental run produces
 * the same numbers as a full one. The second was previously only checked by a manual probe
 * whose guard skipped the case it existed to catch - it compared static metrics only when both
 * runs had sampled the commit, which is exactly the situation where they disagreed.</p>
 */
public class AnalysisEngineTest
{
    private static final long DAY = 24L * 60 * 60 * 1000;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Git git;
    private long now;

    @Before
    public void setUp() throws Exception
    {
        git = Git.init().setDirectory(folder.getRoot()).call();
        now = System.currentTimeMillis();
    }

    @After
    public void tearDown()
    {
        if (git != null)
        {
            git.getRepository().close();
            git.close();
        }
    }

    /**
     * A commit inside the window has not finished being observed, so its churn is unknown -
     * and a report that called it zero would make the newest work always look healthiest.
     */
    @Test
    public void churnIsCensoredOnlyInsideTheWindow() throws Exception
    {
        commit("a.py", body(1, 40), "seed", now - 30 * DAY);
        commit("a.py", body(1, 44), "before the window", now - 20 * DAY);
        commit("a.py", body(1, 48), "inside the window", now - 10 * DAY);
        commit("a.py", body(1, 52), "yesterday", now - DAY);

        List<CommitStats> commits = analyse(Collections.<String, CommitStats>emptyMap(), 0)
                .commits;
        assertEquals(4, commits.size());

        assertFalse("30 days old: window closed", commits.get(0).churnCensored);
        assertFalse("20 days old: window closed", commits.get(1).churnCensored);
        assertTrue("10 days old: window still open", commits.get(2).churnCensored);
        assertTrue("yesterday: window still open", commits.get(3).churnCensored);
    }

    /** Rewriting a line within two weeks is attributed back to the commit that added it. */
    @Test
    public void churnIsAttributedToTheCommitThatAddedTheLine() throws Exception
    {
        commit("a.py", body(1, 30), "seed", now - 60 * DAY);
        commit("a.py", body(1, 30) + line("the_feature_line_that_will_be_rewritten = 1"),
                "add the feature", now - 50 * DAY);
        commit("a.py", body(1, 30) + line("the_feature_line_rewritten_two_days_later = 2"),
                "rewrite it", now - 48 * DAY);

        List<CommitStats> commits = analyse(Collections.<String, CommitStats>emptyMap(), 0)
                .commits;
        assertEquals(1, commits.get(1).added);
        assertEquals("the added line was rewritten inside the window", 1,
                commits.get(1).churn);
        assertFalse(commits.get(1).churnCensored);
    }

    /**
     * The claim the README leads with. Every per-commit field is compared, the sampled flag
     * included - a run that samples different commits picks a different trend reference and
     * reports a different 90-day delta with no change to the code.
     */
    @Test
    public void incrementalRunMatchesFullRunFieldForField() throws Exception
    {
        for (int i = 0; i < 12; i++)
        {
            commit("mod" + (i % 3) + ".py", body(i, 30 + i * 4), "change " + i,
                    now - (60 - i * 4) * DAY);
        }

        AnalysisEngine.Outcome full =
                analyse(Collections.<String, CommitStats>emptyMap(), 0);
        assertEquals(0, full.replayedFrom);

        Map<String, CommitStats> cache = new HashMap<String, CommitStats>();
        for (CommitStats row : full.commits)
        {
            cache.put(row.sha, row);
        }

        // The replay floor is the previous run less two churn windows. Commits here sit at
        // now-60d through now-16d, so a previous run eight days ago puts the floor at now-36d -
        // squarely in the middle, which is the only interesting case.
        AnalysisEngine.Outcome incremental = analyse(cache, now - 8 * DAY);
        assertTrue("the incremental run must not have replayed everything",
                incremental.replayedFrom > 0);
        assertTrue("nor skipped everything",
                incremental.replayedFrom < full.commits.size());

        assertEquals(full.commits.size(), incremental.commits.size());
        for (int i = 0; i < full.commits.size(); i++)
        {
            CommitStats a = full.commits.get(i);
            CommitStats b = incremental.commits.get(i);
            String at = "commit " + i + " (" + a.sha + ")";
            assertEquals(at + " sha", a.sha, b.sha);
            assertEquals(at + " added", a.added, b.added);
            assertEquals(at + " copied", a.copied, b.copied);
            assertEquals(at + " moved", a.moved, b.moved);
            assertEquals(at + " novel", a.novel, b.novel);
            assertEquals(at + " deleted", a.deleted, b.deleted);
            assertEquals(at + " churn", a.churn, b.churn);
            assertEquals(at + " censored", a.churnCensored, b.churnCensored);
            assertEquals(at + " import", a.importCommit, b.importCommit);
            // No guard on sampled(): a difference here is the failure, not a reason to skip.
            assertEquals(at + " sampled", a.sampled(), b.sampled());
            assertEquals(at + " loc", a.loc, b.loc);
            assertEquals(at + " dupLines", a.dupLines, b.dupLines);
            assertEquals(at + " errSwallow", a.errSwallow, b.errSwallow);
            assertEquals(at + " calls", a.calls, b.calls);
            assertEquals(at + " functions", a.functions, b.functions);
        }

        assertEquals("HEAD LOC", full.headLoc, incremental.headLoc);
        assertEquals("HEAD duplication", full.headDupLines, incremental.headDupLines);
        assertEquals("HEAD clone count", full.headDupClones, incremental.headDupClones);
        assertEquals("HEAD calls", full.headCalls, incremental.headCalls);
    }

    // ------------------------------------------------------------------ helpers

    private AnalysisEngine.Outcome analyse(Map<String, CommitStats> cached, long previousRunAt)
            throws Exception
    {
        Repository repository = git.getRepository();
        AnalysisEngine engine = new AnalysisEngine(repository,
                new PathMatcher(AnalysisConfig.DEFAULT_EXCLUDES, ""), null);
        return engine.analyse("", cached, previousRunAt, now);
    }

    private void commit(String path, String content, String message, long at) throws Exception
    {
        File file = new File(folder.getRoot(), path);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(path).call();
        PersonIdent who = new PersonIdent("Test Author", "test@example.com", new Date(at),
                TimeZone.getTimeZone("UTC"));
        git.commit().setMessage(message).setAuthor(who).setCommitter(who).call();
    }

    /** Distinct, long-enough Python lines so normalisation keeps them. */
    private static String body(int seed, int count)
    {
        StringBuilder out = new StringBuilder();
        out.append(line("def handler_" + seed + "(self, request):"));
        for (int i = 0; i < count; i++)
        {
            out.append(line("value_" + seed + "_" + i + " = compute_step(request, " + i + ")"));
        }
        return out.toString();
    }

    private static String line(String text)
    {
        return text + "\n";
    }
}
