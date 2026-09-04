package co.bskim.confluence.codequality.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The clone on disk has to belong to the registration it is filed under.
 *
 * <p>This is the failure mode the tests exist for: the URL was used for the first clone only,
 * so re-pointing a repository left every later fetch going to the old remote. Nothing surfaced
 * it - the run succeeded, the report rendered, the numbers were simply another codebase's, and
 * on a server where a personal repository and a company one sit side by side that is the worst
 * shape a bug can take.</p>
 */
public class GitClientTest
{
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** The regression: the content must follow the URL. */
    @Test
    public void repointingTheUrlMovesTheClone() throws Exception
    {
        File alpha = origin("alpha", "alpha_service.py");
        File beta = origin("beta", "beta_service.py");
        GitClient client = clientRootedAt(folder.newFolder("clones"));

        Repository first = client.sync(7, url(alpha), RepoAuth.NONE);
        try
        {
            assertTrue("setup: the first clone must hold alpha",
                    headHolds(first, "alpha_service.py"));
        }
        finally
        {
            first.close();
        }

        Repository second = client.sync(7, url(beta), RepoAuth.NONE);
        try
        {
            assertTrue("the clone must follow the new URL", headHolds(second, "beta_service.py"));
            assertFalse("and must not still serve the old remote's history",
                    headHolds(second, "alpha_service.py"));
        }
        finally
        {
            second.close();
        }
    }

    /**
     * The other half of the same decision: an unchanged URL must not re-clone.
     *
     * <p>Without this, the fix above would be free to discard on every run - correct reports,
     * and a full clone of every registered repository every time anyone pressed Analyze.</p>
     */
    @Test
    public void theSameUrlFetchesIntoTheExistingClone() throws Exception
    {
        File alpha = origin("alpha", "alpha_service.py");
        GitClient client = clientRootedAt(folder.newFolder("clones"));

        client.sync(7, url(alpha), RepoAuth.NONE).close();
        File marker = new File(client.repoDir(7), "kept-by-the-test");
        assertTrue("setup: the clone directory must exist to be marked", marker.createNewFile());

        // A new commit upstream, to show the fetch path is what ran.
        commit(alpha, "alpha_extra.py", "def extra():\n    return 1\n");

        Repository again = client.sync(7, url(alpha), RepoAuth.NONE);
        try
        {
            assertTrue("the clone was discarded and rebuilt", marker.isFile());
            assertTrue("the new upstream commit was fetched",
                    headHolds(again, "alpha_extra.py"));
        }
        finally
        {
            again.close();
        }
    }

    /**
     * A trailing {@code .git} is the same repository, and re-cloning over it would cost an hour
     * of history replay for a cosmetic edit.
     */
    @Test
    public void anEquivalentUrlKeepsTheClone() throws Exception
    {
        File alpha = origin("alpha", "alpha_service.py");
        GitClient client = clientRootedAt(folder.newFolder("clones"));

        client.sync(7, url(alpha), RepoAuth.NONE).close();
        File marker = new File(client.repoDir(7), "kept-by-the-test");
        assertTrue(marker.createNewFile());

        client.sync(7, url(alpha) + "/", RepoAuth.NONE).close();
        assertTrue("a trailing slash is not a different remote", marker.isFile());
    }

    /** A clone whose config still holds a token is thrown away, which is what removes it. */
    @Test
    public void aCloneWhoseStoredRemoteCarriesATokenIsDiscarded() throws Exception
    {
        File alpha = origin("alpha", "alpha_service.py");
        GitClient client = clientRootedAt(folder.newFolder("clones"));

        client.sync(7, url(alpha), RepoAuth.NONE).close();
        File marker = new File(client.repoDir(7), "kept-by-the-test");
        assertTrue(marker.createNewFile());

        // What a pre-validation clone looks like on disk: the credential in remote.origin.url.
        Repository clone = Git.open(client.repoDir(7)).getRepository();
        try
        {
            clone.getConfig().setString("remote", "origin", "url",
                    "https://x-access-token:ghp_leftover@example.invalid/acme/billing.git");
            clone.getConfig().save();
        }
        finally
        {
            clone.close();
        }
        assertTrue("setup: the token must actually be on disk",
                readText(new File(client.repoDir(7), "config")).contains("ghp_leftover"));

        client.sync(7, url(alpha), RepoAuth.NONE).close();

        assertFalse("the clone carrying a credential must be discarded", marker.isFile());
        assertFalse("and the token must be gone from disk",
                readText(new File(client.repoDir(7), "config")).contains("ghp_leftover"));
    }

    /**
     * A clone over the ceiling is refused <em>and</em> reclaimed.
     *
     * <p>It used to be refused only. Nothing else removes it - the orphan sweep takes
     * directories no registration owns and this one is owned - so registering a repository
     * over the limit cost its whole size in disk permanently, recoverable only by finding it
     * on the server. Every later attempt refused the same clone and left it there.</p>
     */
    @Test
    public void anOversizeCloneIsReclaimedRatherThanLeftOnDisk() throws Exception
    {
        File origin = origin("bulky", "bulky_service.py");
        GitClient client = clientRootedAt(folder.newFolder("store"), 1L, 0L);

        try
        {
            client.sync(4, url(origin), RepoAuth.NONE).close();
            fail("a clone over the ceiling has to be refused");
        }
        catch (GitClient.StorageLimitException expected)
        {
            assertTrue("the message has to name the size: " + expected.getMessage(),
                    expected.getMessage().contains("MB"));
        }

        assertFalse("and the refused clone must not be left occupying disk",
                client.repoDir(4).exists());
    }

    /**
     * A full volume must stop a clone, not wave it through.
     *
     * <p>The guard read {@code usable > 0 && usable < MIN_FREE_BYTES}, and
     * {@code getUsableSpace()} answers 0 when the volume is full - so it stood down in exactly
     * the case it exists for. Four large repositories registered in a row is all it takes: the
     * first three fill the disk and the fourth is told to go ahead.</p>
     */
    @Test
    public void aFullVolumeRefusesTheClone() throws Exception
    {
        File origin = origin("gamma", "gamma_service.py");
        GitClient client = clientWithSpace(folder.newFolder("full"), 0, 500L * 1024 * 1024 * 1024);

        try
        {
            client.sync(5, url(origin), RepoAuth.NONE).close();
            fail("no free space has to stop the clone");
        }
        catch (GitClient.StorageLimitException expected)
        {
            assertTrue("the message has to say how little is free: " + expected.getMessage(),
                    expected.getMessage().contains("free"));
        }
    }

    /**
     * A figure that cannot be read is not a reason to refuse.
     *
     * <p>{@code getUsableSpace()} also answers 0 when it cannot see the partition at all, and
     * refusing every clone on a filesystem that will not report its size would be worse than
     * the guard is worth. {@code getTotalSpace()} is 0 only in that case, which is how the two
     * are told apart.</p>
     */
    @Test
    public void anUnreadableVolumeIsNotTreatedAsFull() throws Exception
    {
        File origin = origin("delta", "delta_service.py");
        GitClient client = clientWithSpace(folder.newFolder("unknown"), 0, 0);

        Repository cloned = client.sync(6, url(origin), RepoAuth.NONE);
        try
        {
            assertTrue("the clone must go ahead", client.repoDir(6).isDirectory());
        }
        finally
        {
            cloned.close();
        }
    }

    /**
     * The sweep removes what no registration owns, and nothing else.
     *
     * <p>It is handed the set of live ids and deletes every clone directory outside it, which
     * makes the set the only thing standing between housekeeping and every clone on the node.
     * A version of this that ignored the set, or was handed an empty one by mistake, would
     * delete all of them and the next run of each would re-clone from scratch - slow, and
     * silent, because re-cloning is what a first run looks like.</p>
     */
    @Test
    public void theSweepKeepsTheClonesThatStillHaveRegistrations() throws Exception
    {
        File root = folder.newFolder("store");
        for (int id : new int[] { 1, 2, 7 })
        {
            assertTrue(new File(root, id + ".git").mkdirs());
        }
        // Not a clone directory at all, and not this code's business.
        assertTrue(new File(root, "notes").mkdirs());

        Set<Integer> live = new HashSet<Integer>(Arrays.asList(1, 7));
        assertEquals("only the one without a registration", 1,
                clientRootedAt(root).discardOrphans(live));

        assertTrue("a registered clone must survive", new File(root, "1.git").isDirectory());
        assertTrue("all of them", new File(root, "7.git").isDirectory());
        assertFalse("the orphan must be gone", new File(root, "2.git").exists());
        assertTrue("and anything that is not a clone is left alone",
                new File(root, "notes").isDirectory());
    }

    /**
     * The sweep must not delete through a link.
     *
     * <p>{@code listFiles()} walks straight into a directory symlink, so a link left under the
     * clone root - which lives inside the Confluence home - would have had the sweep delete
     * whatever it pointed at rather than the link.</p>
     */
    @Test
    public void theSweepDoesNotFollowASymbolicLinkOutOfTheCloneRoot() throws Exception
    {
        File root = folder.newFolder("store");
        File outside = folder.newFolder("not-ours");
        File treasure = new File(outside, "keep-me.txt");
        Files.write(treasure.toPath(), "important".getBytes(StandardCharsets.UTF_8));

        File orphan = new File(root, "99.git");
        assertTrue(orphan.mkdirs());
        try
        {
            Files.createSymbolicLink(new File(orphan, "escape").toPath(), outside.toPath());
        }
        catch (UnsupportedOperationException | IOException e)
        {
            Assume.assumeNoException("this filesystem does not do symbolic links", e);
        }

        assertEquals(1, clientRootedAt(root).discardOrphans(Collections.<Integer>emptySet()));

        assertFalse("the orphaned clone must be gone", orphan.exists());
        assertTrue("but not what the link pointed at", treasure.isFile());
        assertTrue("nor the directory holding it", outside.isDirectory());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The clone root is the only thing {@code ApplicationProperties} is used for, and standing
     * up a SAL implementation to say "this temp directory" would be all stub and no test.
     */
    private static GitClient clientRootedAt(final File root)
    {
        return new GitClient(null)
        {
            @Override
            public File storageRoot()
            {
                return root;
            }
        };
    }

    /** Rooted, with the two ceilings set - a test can neither clone 4GB nor fill a volume. */
    private static GitClient clientRootedAt(final File root, final long maxClone,
                                            final long minFree)
    {
        return new GitClient(null)
        {
            @Override
            public File storageRoot()
            {
                return root;
            }

            @Override
            long maxCloneBytes()
            {
                return maxClone;
            }

            @Override
            long minFreeBytes()
            {
                return minFree;
            }
        };
    }

    /** Rooted, with the disk figures dictated, so "full" and "unreadable" can be told apart. */
    private static GitClient clientWithSpace(final File root, final long usable,
                                             final long total)
    {
        return new GitClient(null)
        {
            @Override
            public File storageRoot()
            {
                return root;
            }

            @Override
            long usableSpace(File dir)
            {
                return usable;
            }

            @Override
            long totalSpace(File dir)
            {
                return total;
            }
        };
    }

    /** A non-bare repository with one commit, to clone from over the local transport. */
    private File origin(String name, String path) throws Exception
    {
        File dir = folder.newFolder(name);
        Git git = Git.init().setDirectory(dir).call();
        git.getRepository().close();
        git.close();
        commit(dir, path, "def " + name + "_entry_point(request):\n    return handle(request)\n");
        return dir;
    }

    private static void commit(File repoDir, String path, String content) throws Exception
    {
        Files.write(new File(repoDir, path).toPath(), content.getBytes(StandardCharsets.UTF_8));
        Git git = Git.open(repoDir);
        try
        {
            git.add().addFilepattern(path).call();
            git.commit().setMessage("add " + path).setAuthor("Test", "test@example.com").call();
        }
        finally
        {
            git.getRepository().close();
            git.close();
        }
    }

    private static String url(File repoDir)
    {
        return repoDir.getAbsolutePath();
    }

    private static boolean headHolds(Repository repository, String path) throws IOException
    {
        ObjectId head = repository.resolve("HEAD");
        assertEquals("HEAD must resolve for the question to mean anything", true, head != null);
        RevWalk walk = new RevWalk(repository);
        try
        {
            TreeWalk tree = TreeWalk.forPath(repository,
                    path, walk.parseCommit(head).getTree());
            if (tree == null)
            {
                return false;
            }
            tree.close();
            return true;
        }
        finally
        {
            walk.close();
        }
    }

    private static String readText(File file) throws IOException
    {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
