package co.bskim.confluence.codequality.analysis;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
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
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When a commit counts as wholesale rather than as somebody's work.
 *
 * <p>The verdict removes a commit from every ratio the report shows, so both mistakes are
 * expensive: missing a code dump lets it stand in for the team's habits, and firing wrongly
 * deletes real work from the denominator. Half of these fixtures are commits that must
 * <em>not</em> be excluded and half are commits that must, because the rule was tightened and
 * a tightened rule is one that can now miss things.</p>
 *
 * <p>The numbers come from {@code tools/ImportProbe.java} over 50,429 commits - see
 * {@link AnalysisConfig#IMPORT_RATIO}.</p>
 */
public class ImportDetectionTest
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
     * The case the measurements turned up: ordinary work on a small repository.
     *
     * <p>loguru's "Add rotating file handler" added 205 lines to a 208-line tree and was
     * excluded from everything. Half of a small tree is a normal week, so the ratio test needs
     * a denominator worth dividing by.</p>
     */
    @Test
    public void ordinaryWorkOnASmallRepositoryIsNotWholesale() throws Exception
    {
        commit("a.py", body(1, 300), "the project so far", now - 30 * DAY);
        commit("b.py", body(2, 250), "add a feature", now - 20 * DAY);

        List<CommitStats> commits = analyse();
        assertEquals(2, commits.size());
        assertEquals("setup: the feature commit must be big enough for the old rule to fire",
                251, commits.get(1).added);
        assertEquals("setup: and must clear half the parent", 301, commits.get(1).parentLines);
        assertFalse("250 lines on a 300-line tree is a week's work, not an import",
                commits.get(1).importCommit);
    }

    /**
     * The other case: a file split, which is the refactoring the report exists to reward.
     *
     * <p>"Split up click into a package" moved 1,130 of its 1,165 added lines and was excluded
     * from the refactoring ratio.</p>
     */
    @Test
    public void movingCodeBetweenFilesIsNotWholesale() throws Exception
    {
        String kept = body(1, 60);
        String moving = tail(250);
        commit("a.py", kept + moving, "the project so far", now - 30 * DAY);
        // Same lines, different file: the parent's copy is deleted by this very commit.
        write("a.py", kept);
        commit("b.py", moving, "split a.py into two modules", now - 20 * DAY);

        List<CommitStats> commits = analyse();
        assertEquals(2, commits.size());
        CommitStats split = commits.get(1);
        assertTrue("setup: the split must be large enough for the old rule to fire",
                split.added > AnalysisConfig.IMPORT_MIN_LINES);
        assertTrue("setup: and most of it must be recognised as moved",
                split.moved > split.added / 2);
        assertFalse("a relocation is not an import", split.importCommit);
    }

    /** The root commit is the initial dump by definition, and must stay excluded. */
    @Test
    public void theRootCommitIsAlwaysWholesale() throws Exception
    {
        commit("a.py", body(1, 300), "initial commit", now - 30 * DAY);
        commit("b.py", body(2, 20), "a small change", now - 20 * DAY);

        List<CommitStats> commits = analyse();
        assertTrue("the first commit brings in the whole codebase",
                commits.get(0).importCommit);
        assertFalse(commits.get(1).importCommit);
    }

    /**
     * Code arriving where there is no codebase yet is the initial body, however modest.
     *
     * <p>This keeps "moved cli over from the sandbox" and "Initial code drop", both of which
     * landed on a tree with no analysable code at all.</p>
     */
    @Test
    public void codeArrivingOnAnEmptyTreeIsWholesale() throws Exception
    {
        // A first commit with nothing analysable in it, so the second is where code appears.
        commit("README.md", "# The project\n", "start the repository", now - 40 * DAY);
        commit("a.py", body(1, 600), "import the library from the sandbox", now - 30 * DAY);

        List<CommitStats> commits = analyse();
        assertEquals(2, commits.size());
        assertEquals("setup: the first commit must hold no analysable code", 0,
                commits.get(1).parentLines);
        assertTrue("600 lines arriving where there was none is the initial body",
                commits.get(1).importCommit);
    }

    /** A dump large enough is wholesale whatever it landed on. */
    @Test
    public void aVeryLargeAdditionIsWholesaleEvenOnASmallTree() throws Exception
    {
        commit("a.py", body(1, 300), "the project so far", now - 30 * DAY);
        commit("vendor_lib.py", body(2, 2100), "vendor a dependency", now - 20 * DAY);

        List<CommitStats> commits = analyse();
        CommitStats dump = commits.get(1);
        assertTrue("setup: the tree is below the parent floor", dump.parentLines < 1000);
        assertTrue("setup: and the addition is over the outright limit",
                dump.added >= AnalysisConfig.IMPORT_LARGE_LINES);
        assertTrue("2,100 lines in one commit is wholesale", dump.importCommit);
    }

    /** Doubling a substantial tree is still wholesale. */
    @Test
    public void doublingALargeTreeIsWholesale() throws Exception
    {
        commit("a.py", body(1, 1200), "the project so far", now - 30 * DAY);
        commit("b.py", body(2, 700), "merge a long-running branch", now - 20 * DAY);

        List<CommitStats> commits = analyse();
        CommitStats merge = commits.get(1);
        assertTrue("setup: the tree is above the parent floor", merge.parentLines >= 1000);
        assertTrue("more than half of a 1,200-line tree arriving at once", merge.importCommit);
    }

    // ------------------------------------------------------------------ helpers

    private List<CommitStats> analyse() throws Exception
    {
        AnalysisEngine engine = new AnalysisEngine(git.getRepository(),
                new PathMatcher(AnalysisConfig.DEFAULT_EXCLUDES, ""), null);
        return engine.analyse("", Collections.<String, CommitStats>emptyMap(), 0, now).commits;
    }

    private void write(String path, String content) throws Exception
    {
        Files.write(new File(folder.getRoot(), path).toPath(),
                content.getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(path).call();
    }

    private void commit(String path, String content, String message, long at) throws Exception
    {
        write(path, content);
        PersonIdent who = new PersonIdent("Test Author", "test@example.com", new Date(at),
                TimeZone.getTimeZone("UTC"));
        git.commit().setMessage(message).setAuthor(who).setCommitter(who).call();
    }

    /** Distinct, long-enough Python lines so normalisation keeps every one of them. */
    private static String body(int seed, int count)
    {
        StringBuilder out = new StringBuilder();
        out.append("def handler_").append(seed).append("(self, request):\n");
        for (int i = 0; i < count; i++)
        {
            out.append("value_").append(seed).append('_').append(i)
                    .append(" = compute_step(request, ").append(i).append(")\n");
        }
        return out.toString();
    }

    /** A block distinct from anything {@link #body} produces, so it can be moved about. */
    private static String tail(int count)
    {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++)
        {
            out.append("relocated_helper_").append(i)
                    .append(" = build_relocated_thing(context, ").append(i).append(")\n");
        }
        return out.toString();
    }
}
