package co.bskim.confluence.codequality.git;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

/**
 * Bare mirrors of the registered remotes, kept under the Confluence home directory.
 *
 * <p>Bare and never checked out: the analysis reads blobs straight from the object database, so
 * a working tree would only cost disk. Clones are reused across runs, so a second analysis of
 * the same repository is a fetch.</p>
 */
@Named
public class GitClient
{
    private static final Logger log = LoggerFactory.getLogger(GitClient.class);

    private static final int TIMEOUT_SECONDS = 180;
    private static final int LS_REMOTE_TIMEOUT_SECONDS = 30;

    private final ApplicationProperties applicationProperties;

    @Inject
    public GitClient(@ComponentImport ApplicationProperties applicationProperties)
    {
        this.applicationProperties = applicationProperties;
    }

    public File storageRoot()
    {
        File home = applicationProperties.getHomeDirectory();
        File root = new File(new File(home, "plugin-data"), "code-quality");
        File repos = new File(root, "repos");
        if (!repos.isDirectory() && !repos.mkdirs())
        {
            throw new IllegalStateException("Cannot create clone directory: " + repos);
        }
        return repos;
    }

    public File repoDir(int repoId)
    {
        return new File(storageRoot(), repoId + ".git");
    }

    /** Clones on first use, fetches afterwards, and hands back an open bare repository. */
    public Repository sync(int repoId, String url, RepoAuth auth)
            throws IOException, GitAPIException
    {
        File dir = repoDir(repoId);
        if (!new File(dir, "config").isFile())
        {
            deleteRecursively(dir);
            Git cloned = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(dir)
                    .setBare(true)
                    .setCloneAllBranches(true)
                    .setTimeout(TIMEOUT_SECONDS)
                    .setCredentialsProvider(credentials(auth))
                    .call();
            return cloned.getRepository();
        }

        Repository repository = new FileRepositoryBuilder().setGitDir(dir).build();
        Git git = Git.wrap(repository);
        try
        {
            git.fetch()
                    .setRemote("origin")
                    .setRefSpecs(Collections.singletonList(
                            new RefSpec("+refs/heads/*:refs/heads/*")))
                    .setRemoveDeletedRefs(true)
                    .setTimeout(TIMEOUT_SECONDS)
                    .setCredentialsProvider(credentials(auth))
                    .call();
        }
        catch (GitAPIException e)
        {
            repository.close();
            throw e;
        }
        return repository;
    }

    /** What a probe actually established. */
    public static final class ProbeResult
    {
        public boolean reachable;
        /** The remote served refs without credentials, so it is publicly readable. */
        public boolean anonymous;
        /** Credentials were required and they worked. */
        public boolean tokenVerified;
        public int branches;
        /**
         * A category, not a message. Returning JGit's own text told a caller whether a port was
         * open, which turned this endpoint into a port scanner; the detail goes to the log
         * instead.
         */
        public String error = "";
    }

    /**
     * Probes anonymously first, then with credentials.
     *
     * <p>One credentialed probe is not enough to say anything about the token: for a publicly
     * readable repository GitHub serves the refs and ignores the Basic auth header entirely, so
     * a token of {@code aaaa} comes back "reachable". Trying anonymously first separates the
     * three cases that matter - the repository is public and the token was never checked, the
     * token was required and worked, or nothing worked - and the form can then say which.</p>
     */
    public ProbeResult probe(String url, RepoAuth auth)
    {
        ProbeResult result = new ProbeResult();

        String anonymousError;
        try
        {
            result.branches = lsRemote(url, null);
            result.reachable = true;
            result.anonymous = true;
            return result;
        }
        catch (Exception e)
        {
            anonymousError = describe(url, e);
        }

        if (auth == null || auth.isEmpty())
        {
            result.error = anonymousError;
            return result;
        }

        try
        {
            result.branches = lsRemote(url, credentials(auth));
            result.reachable = true;
            result.tokenVerified = true;
        }
        catch (Exception e)
        {
            result.error = describe(url, e);
        }
        return result;
    }

    private int lsRemote(String url, CredentialsProvider credentials) throws GitAPIException
    {
        return Git.lsRemoteRepository()
                .setRemote(url)
                .setHeads(true)
                .setTags(false)
                .setTimeout(LS_REMOTE_TIMEOUT_SECONDS)
                .setCredentialsProvider(credentials)
                .call()
                .size();
    }

    /**
     * Reduces a failure to one of a handful of categories.
     *
     * <p>The full text is logged, never returned. A remote's exact error distinguishes an open
     * port from a closed one, a present repository from an absent one, and that difference is
     * exactly what makes an unauthenticated reachability probe useful to somebody mapping an
     * internal network.</p>
     */
    private static String describe(String url, Exception e)
    {
        log.debug("Repository probe failed for {}", url, e);
        String text = (e.getMessage() == null ? "" : e.getMessage()).toLowerCase(Locale.ROOT);
        String type = e.getClass().getName().toLowerCase(Locale.ROOT);

        if (text.contains("not authorized") || text.contains("authentication is required")
                || text.contains("authentication failed") || text.contains("401")
                || text.contains("403"))
        {
            return "notAuthorized";
        }
        if (text.contains("not a git repository")
                || text.contains("does not appear to be a git repository"))
        {
            return "notGitRepository";
        }
        if (text.contains("not found") || text.contains("404"))
        {
            return "notFound";
        }
        if (type.contains("sockettimeout") || text.contains("timed out")
                || text.contains("timeout"))
        {
            return "timeout";
        }
        return "unreachable";
    }

    public void discard(int repoId)
    {
        deleteRecursively(repoDir(repoId));
    }

    private CredentialsProvider credentials(RepoAuth auth)
    {
        if (auth == null || auth.isEmpty())
        {
            return null;
        }
        // GitHub accepts the token as the password with any user name; x-access-token is what
        // its own docs use, so it is the default when no user name was configured.
        String user = auth.username.isEmpty() ? "x-access-token" : auth.username;
        return new UsernamePasswordCredentialsProvider(user, auth.token);
    }

    private static void deleteRecursively(File file)
    {
        if (!file.exists())
        {
            return;
        }
        File[] children = file.listFiles();
        if (children != null)
        {
            for (File child : children)
            {
                deleteRecursively(child);
            }
        }
        if (!file.delete())
        {
            file.deleteOnExit();
        }
    }
}
