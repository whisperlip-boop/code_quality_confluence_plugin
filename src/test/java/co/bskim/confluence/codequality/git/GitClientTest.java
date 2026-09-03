package co.bskim.confluence.codequality.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
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
