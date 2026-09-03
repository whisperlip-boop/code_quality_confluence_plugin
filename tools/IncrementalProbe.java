import co.bskim.confluence.codequality.analysis.AnalysisConfig;
import co.bskim.confluence.codequality.analysis.AnalysisEngine;
import co.bskim.confluence.codequality.analysis.CommitStats;
import co.bskim.confluence.codequality.analysis.PathMatcher;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that an incremental run agrees with a full one, commit for commit.
 *
 * This is the part of the engine most likely to drift: an incremental run rebuilds the tree
 * state and the n-gram index from a materialised tree rather than from the start of history,
 * and if that reconstruction is off by anything the trend line becomes an artefact of when the
 * analysis happened to run. The second pass is forced to replay only part of the history by
 * handing it a previous-run timestamp in the future.
 *
 *   java -cp target/code-quality-1.0.0.jar:<slf4j>:/tmp/cq-probe IncrementalProbe /tmp/captureV.git
 */
public final class IncrementalProbe
{
    private static final AnalysisEngine.Progress QUIET = new AnalysisEngine.Progress()
    {
        @Override
        public void report(String phase, int current, int total)
        {
        }

        @Override
        public boolean cancelled()
        {
            return false;
        }
    };

    public static void main(String[] args) throws Exception
    {
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(new File(args[0])).build();
        long analysisTime = System.currentTimeMillis();

        AnalysisEngine.Outcome full = engine(repository)
                .analyse("", Collections.<String, CommitStats>emptyMap(), 0L, analysisTime);

        Map<String, CommitStats> cache = new HashMap<String, CommitStats>();
        for (CommitStats row : full.commits)
        {
            cache.put(row.sha, row);
        }

        // A previous run dated in the future pushes the replay floor into the middle of the
        // history, which is the only way to exercise the partial path on a young repository.
        long pretendPreviousRun = analysisTime + AnalysisConfig.CHURN_WINDOW_MS;
        AnalysisEngine.Outcome incremental = engine(repository)
                .analyse("", cache, pretendPreviousRun, analysisTime);

        System.out.println("full        : " + full.commits.size() + " commits, replayed from "
                + full.replayedFrom);
        System.out.println("incremental : " + incremental.commits.size()
                + " commits, replayed from " + incremental.replayedFrom);
        if (incremental.replayedFrom == 0)
        {
            System.out.println("WARNING: the incremental run replayed everything, so this "
                    + "comparison proves nothing. Use a repository with a longer history.");
        }

        int mismatches = compare(full.commits, incremental.commits);
        mismatches += head(full, incremental);
        System.out.println(mismatches == 0
                ? "MATCH: incremental output is identical to the full run"
                : "MISMATCH: " + mismatches + " differences");
        repository.close();
        System.exit(mismatches == 0 ? 0 : 1);
    }

    private static AnalysisEngine engine(Repository repository)
    {
        return new AnalysisEngine(repository,
                new PathMatcher(AnalysisConfig.DEFAULT_EXCLUDES, ""), QUIET);
    }

    private static int compare(List<CommitStats> a, List<CommitStats> b)
    {
        int mismatches = 0;
        if (a.size() != b.size())
        {
            System.out.println("  commit count " + a.size() + " vs " + b.size());
            return 1;
        }
        for (int i = 0; i < a.size(); i++)
        {
            CommitStats x = a.get(i);
            CommitStats y = b.get(i);
            mismatches += check(x.sha, "added", x.added, y.added);
            mismatches += check(x.sha, "copied", x.copied, y.copied);
            mismatches += check(x.sha, "moved", x.moved, y.moved);
            mismatches += check(x.sha, "novel", x.novel, y.novel);
            mismatches += check(x.sha, "deleted", x.deleted, y.deleted);
            mismatches += check(x.sha, "churn", x.churn, y.churn);
            mismatches += check(x.sha, "censored", x.churnCensored ? 1 : 0,
                    y.churnCensored ? 1 : 0);
            mismatches += check(x.sha, "import", x.importCommit ? 1 : 0,
                    y.importCommit ? 1 : 0);
            if (x.sampled() && y.sampled())
            {
                mismatches += check(x.sha, "loc", x.loc, y.loc);
                mismatches += check(x.sha, "dupLines", x.dupLines, y.dupLines);
                mismatches += check(x.sha, "errSwallow", x.errSwallow, y.errSwallow);
                mismatches += check(x.sha, "calls", x.calls, y.calls);
            }
        }
        return mismatches;
    }

    private static int head(AnalysisEngine.Outcome a, AnalysisEngine.Outcome b)
    {
        int mismatches = 0;
        mismatches += check("HEAD", "loc", a.headLoc, b.headLoc);
        mismatches += check("HEAD", "files", a.headFiles, b.headFiles);
        mismatches += check("HEAD", "dupLines", a.headDupLines, b.headDupLines);
        mismatches += check("HEAD", "dupClones", a.headDupClones, b.headDupClones);
        mismatches += check("HEAD", "calls", a.headCalls, b.headCalls);
        mismatches += check("HEAD", "swallow", a.headSwallow, b.headSwallow);
        mismatches += check("HEAD", "broad", a.headBroad, b.headBroad);
        mismatches += check("HEAD", "clones", a.headClones.size(), b.headClones.size());
        return mismatches;
    }

    private static int check(String sha, String field, int expected, int actual)
    {
        if (expected == actual)
        {
            return 0;
        }
        System.out.println("  " + sha + " " + field + ": full=" + expected
                + " incremental=" + actual);
        return 1;
    }
}
