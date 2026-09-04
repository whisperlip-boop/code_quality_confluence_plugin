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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

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
    /**
     * Probes run on a Confluence request thread, so this is a user-interface budget rather than
     * a network one. It used to be 30 seconds and the probe made two attempts, so a host that
     * accepted the connection and never answered held a request thread for a minute - on an
     * instance with a small HTTP pool, a handful of those is an outage. Fifteen seconds is long
     * enough for an internal GitLab that is merely slow.
     */
    private static final int LS_REMOTE_TIMEOUT_SECONDS = 15;

    /**
     * Free space a clone will not start without.
     *
     * <p>The clones live under the Confluence home, so a repository big enough to fill the
     * filesystem does not spoil a report - it stops the instance from writing attachments,
     * indexes and logs. Refusing to start is the cheap half of the guard, and the only half
     * that runs before the disk is consumed.</p>
     */
    private static final long MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024;

    /**
     * Size a single clone may not exceed.
     *
     * <p>Checked after the fetch, because git offers no way to ask a remote how big it is.
     * That makes this the second line rather than the first: it stops the <em>next</em> run
     * from paying for a repository that should never have been registered, and it says so
     * instead of quietly working for four minutes and reporting numbers nobody can read. For
     * scale, the largest repository in the reference cohorts is around 200MB of clone; 4GB is
     * a monorepo, and a monorepo on a Confluence node is a disk incident.</p>
     */
    private static final long MAX_CLONE_BYTES = 4L * 1024 * 1024 * 1024;

    /** Raised instead of filling the Confluence home or working on something unbounded. */
    public static final class StorageLimitException extends IOException
    {
        private static final long serialVersionUID = 1L;

        StorageLimitException(String message)
        {
            super(message);
        }
    }

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

    /*
     * The ceilings and the disk figures behind small overridable methods, the same seam
     * storageRoot() already provides. A test cannot fill a volume or clone four gigabytes, and
     * both of the faults these guards had were in the arithmetic rather than in the plumbing.
     */
    long minFreeBytes()
    {
        return MIN_FREE_BYTES;
    }

    long maxCloneBytes()
    {
        return MAX_CLONE_BYTES;
    }

    long usableSpace(File dir)
    {
        return dir.getUsableSpace();
    }

    long totalSpace(File dir)
    {
        return dir.getTotalSpace();
    }

    /**
     * Clones on first use, fetches afterwards, and hands back an open bare repository.
     *
     * <p>The URL is checked against the clone's own {@code remote.origin.url} every time, not
     * only on the first call. It used to be used for the first clone alone: re-point a
     * registration at another repository and the fetch went to the <em>old</em> remote for ever,
     * so the screen said one thing and every number on the report came from another codebase,
     * with nothing to hint at it. Checking here rather than at the point of edit also repairs
     * the rows of anyone who changed a URL before this existed.</p>
     *
     * <p>This takes the URL as given and does not validate it - {@link RemoteUrl#revalidate}
     * is the caller's job, and {@code AnalysisJobManager} does it before every run. Any new
     * caller that fetches from a stored URL has to do the same.</p>
     */
    public Repository sync(int repoId, String url, RepoAuth auth)
            throws IOException, GitAPIException
    {
        File dir = repoDir(repoId);
        File root = storageRoot();
        long usable = usableSpace(root);
        // getUsableSpace() answers 0 both when the volume is full and when it cannot be read,
        // and this used to skip the check for either - so the guard stood down in exactly the
        // case it exists for. getTotalSpace() is 0 only in the second case, which is the one
        // there is nothing to be done about.
        if (totalSpace(root) > 0 && usable < minFreeBytes())
        {
            throw new StorageLimitException("Only " + megabytes(usable)
                    + "MB free where clones are kept; " + megabytes(minFreeBytes())
                    + "MB is required before starting. Free space or move the Confluence home.");
        }
        if (new File(dir, "config").isFile() && !clonedFrom(dir, url))
        {
            log.info("Repository {} points at a different remote than its clone; re-cloning",
                    repoId);
            deleteRecursively(dir);
        }
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
            // The clone path is the one that consumes the disk in the first place, so it is
            // checked too - not only the fetch that comes after it.
            checkSize(cloned.getRepository(), dir);
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
        checkSize(repository, dir);
        return repository;
    }

    /** Fails the run rather than letting one repository own the node's disk. */
    private void checkSize(Repository repository, File dir) throws IOException
    {
        long size = sizeOf(dir);
        if (size <= maxCloneBytes())
        {
            return;
        }
        repository.close();
        // Reclaimed, not left behind. This clone will never be analysed, and nothing else
        // removes it: the orphan sweep only takes directories no registration owns, and this
        // one is owned. Registering a repository over the ceiling used to cost its whole size
        // in disk, for good, recoverable only by finding it on the server. The next attempt
        // pays for another clone, which is the right way round - a refused run should not be
        // holding gigabytes while it is refused.
        if (!deleteRecursively(dir))
        {
            log.warn("Could not remove the oversize clone at {}; it will occupy disk until "
                    + "removed by hand", dir);
        }
        throw new StorageLimitException("The clone is " + megabytes(size) + "MB, over the "
                + megabytes(maxCloneBytes()) + "MB limit for one repository. This repository is "
                + "too large to analyse on this node.");
    }

    private static long sizeOf(File file)
    {
        Path path = file.toPath();
        // Not through links either: one pointing at something large would make the ceiling
        // refuse a clone over bytes the clone does not hold.
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        {
            return file.isFile() ? file.length() : 0;
        }
        File[] children = file.listFiles();
        if (children == null)
        {
            return 0;
        }
        long total = 0;
        for (File child : children)
        {
            total += sizeOf(child);
        }
        return total;
    }

    private static long megabytes(long bytes)
    {
        return bytes / (1024 * 1024);
    }

    /**
     * Whether the clone in {@code dir} came from {@code url}.
     *
     * <p>False for an unreadable clone as well as a mismatched one: a directory this cannot
     * read the remote of is a directory this should not fetch into. Re-cloning costs bandwidth;
     * the alternative costs a wrong report that looks right.</p>
     */
    private static boolean clonedFrom(File dir, String url)
    {
        String stored;
        try
        {
            Repository repository = new FileRepositoryBuilder().setGitDir(dir).build();
            try
            {
                stored = repository.getConfig().getString("remote", "origin", "url");
            }
            finally
            {
                repository.close();
            }
        }
        catch (IOException e)
        {
            log.debug("Cannot read the remote of {}; treating the clone as stale", dir, e);
            return false;
        }
        catch (RuntimeException e)
        {
            // A corrupt config throws rather than returning null.
            log.debug("Cannot read the remote of {}; treating the clone as stale", dir, e);
            return false;
        }
        if (stored == null || stored.isEmpty())
        {
            return false;
        }
        if (RemoteUrl.carriesSecret(stored))
        {
            // Written before URLs were validated, so a token is sitting in this file in clear
            // text. Re-cloning from the sanitised URL is what removes it from disk.
            log.info("Discarding a clone whose stored remote still carries a credential");
            return false;
        }
        return RemoteUrl.canonical(stored).equals(RemoteUrl.canonical(url));
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
        if ("timeout".equals(anonymousError) || "unreachable".equals(anonymousError))
        {
            // Nothing answered, so there is nothing for a credential to change. Trying again
            // only doubles what the request thread is holding, which is the whole cost here.
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

    /**
     * Deletes clone directories that no registered repository owns.
     *
     * <p>Deleting a repository mid-analysis used to leave its clone behind for good: the
     * request removes the row and calls {@link #discard}, then the analysis thread - which is
     * inside {@code sync} and does not check for cancellation there - finishes cloning into the
     * directory that was just removed. Several gigabytes, owned by nobody, and nothing ever
     * looked at it again.</p>
     *
     * <p>A sweep rather than tighter coordination on the delete path, because the same
     * directories are left behind by a node that is killed during a clone, and no amount of
     * care in {@code delete} covers that.</p>
     *
     * @return how many directories were removed
     */
    public int discardOrphans(Set<Integer> liveRepoIds)
    {
        File[] entries = storageRoot().listFiles();
        if (entries == null)
        {
            return 0;
        }
        int removed = 0;
        for (File entry : entries)
        {
            String name = entry.getName();
            if (!name.endsWith(".git"))
            {
                continue;
            }
            Integer id = parseId(name.substring(0, name.length() - 4));
            if (id == null || liveRepoIds.contains(id))
            {
                continue;
            }
            log.info("Removing the clone of repository {}, which no longer exists: {}",
                    id, entry);
            if (deleteRecursively(entry))
            {
                removed++;
            }
            else
            {
                log.warn("Could not remove the orphaned clone at {} - check its ownership and "
                        + "remove it by hand", entry);
            }
        }
        return removed;
    }

    private static Integer parseId(String text)
    {
        try
        {
            return Integer.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            // Not one of ours; leaving it alone is the safe reading of an unexpected name.
            return null;
        }
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

    /**
     * @return true when nothing is left behind
     *
     * <p>The return value exists because the orphan sweep counted directories it had failed to
     * remove. A clone owned by another user - which is what a hand-made directory or a
     * container running as a different uid looks like - cannot be deleted, and reporting it as
     * cleaned up means nobody ever finds out it is still there.</p>
     */
    /**
     * Deletes a tree without following anything out of it.
     *
     * <p>{@code listFiles()} walks straight through a directory symlink, so a link under the
     * clone root would have had this delete the contents of whatever it pointed at - and the
     * clone root sits inside the Confluence home. A link is removed as a link.</p>
     */
    private static boolean deleteRecursively(File file)
    {
        Path path = file.toPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        {
            return true;
        }
        boolean clean = true;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        {
            File[] children = file.listFiles();
            if (children != null)
            {
                for (File child : children)
                {
                    clean &= deleteRecursively(child);
                }
            }
        }
        if (file.delete())
        {
            return clean;
        }
        file.deleteOnExit();
        return false;
    }
}
