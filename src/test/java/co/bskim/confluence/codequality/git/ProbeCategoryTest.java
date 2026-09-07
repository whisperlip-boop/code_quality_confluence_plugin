package co.bskim.confluence.codequality.git;

import org.eclipse.jgit.api.errors.TransportException;
import org.junit.Test;

import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;

/**
 * What a failed probe is reported as.
 *
 * <p>The category is all the caller gets - the remote's own sentence is logged and never
 * returned, because it distinguishes an open port from a closed one and a present repository
 * from an absent one, which is what makes an unauthenticated probe useful to somebody mapping
 * an internal network. That makes the mapping the whole of the answer, and it has been wrong in
 * a way that cost real time: GitHub refuses a fine-grained token that has not been granted the
 * repository with "Write access to repository not granted", JGit passes that through without
 * the 403, and it came out as "unreachable" - which sends an administrator to look at the
 * network while the problem is a checkbox on the token.</p>
 */
public class ProbeCategoryTest
{
    /** Every one of these three is a real message, taken from JGit against a real remote. */
    @Test
    public void aTokenWithoutAccessToTheRepositoryIsNotAuthorised()
    {
        // A valid fine-grained token whose repository list does not include this repository.
        assertEquals("notAuthorized", categoryOf("https://github.com/acme/private.git:"
                + " git-upload-pack not permitted on 'https://github.com/acme/private.git/'"));
        // The same string with its github_pat_ prefix missing, which is not a token at all.
        assertEquals("notAuthorized",
                categoryOf("https://github.com/acme/private.git: not authorized"));
        // No token offered for a private repository.
        assertEquals("notAuthorized", categoryOf("https://github.com/acme/private.git:"
                + " Authentication is required but no CredentialsProvider has been registered"));
    }

    @Test
    public void theOtherWaysAnAuthorisationFailureArrives()
    {
        assertEquals("notAuthorized", categoryOf("git@github.com:acme/x.git: not authorized"));
        assertEquals("notAuthorized", categoryOf(
                "https://github.com/acme/x.git: Invalid username or token."
                        + " Authentication failed"));
        assertEquals("notAuthorized",
                categoryOf("https://host/x.git: 401 Unauthorized"));
        assertEquals("notAuthorized",
                categoryOf("https://host/x.git: The requested URL returned error: 403"));
    }

    /** The categories that must not be swallowed by the authorisation checks. */
    @Test
    public void theOtherCategoriesStillAnswerForThemselves()
    {
        assertEquals("notFound", categoryOf("https://github.com/acme/nope.git: not found"));
        assertEquals("notGitRepository", categoryOf(
                "https://example.com/x: does not appear to be a git repository"));
        assertEquals("timeout", categoryOf("https://10.0.0.1/x.git: connection timed out"));
        assertEquals("unreachable", categoryOf("https://10.0.0.1/x.git: connection refused"));
        assertEquals("timeout", GitClient.describe("https://10.0.0.1/x.git",
                new SocketTimeoutException("read timed out")));
    }

    /** A failure with nothing to go on is unreachable, not something more confident. */
    @Test
    public void anEmptyMessageIsNotGuessedAt()
    {
        assertEquals("unreachable", GitClient.describe("https://host/x.git",
                new TransportException("")));
    }

    private static String categoryOf(String remoteMessage)
    {
        return GitClient.describe("https://host/x.git", new TransportException(remoteMessage));
    }
}
