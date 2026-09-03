package co.bskim.confluence.codequality.git;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Fixtures for URL validation.
 *
 * <p>Three of the security findings this closes were one missing check each, so they are the
 * kind of thing a later tidy-up removes without noticing: a scheme test that looks redundant
 * next to the browser's own, a userinfo split that looks like a display concern. Each test below
 * names the failure it prevents.</p>
 */
public class RemoteUrlTest
{
    /** B-1: the documented GitHub form put a token in the database in clear text. */
    @Test
    public void credentialsInTheUrlAreSplitOut() throws Exception
    {
        RemoteUrl parsed =
                RemoteUrl.parse("https://x-access-token:ghp_secret@github.com/acme/billing.git");

        assertEquals("https://github.com/acme/billing.git", parsed.url);
        assertEquals("x-access-token", parsed.embeddedUser);
        assertEquals("ghp_secret", parsed.embeddedSecret);
        assertTrue(parsed.carriedCredentials());
    }

    /** B-2: file: cloned the server's own disk. B-5: javascript: reached the browser as href. */
    @Test
    public void onlyHttpsAndSshAreAccepted()
    {
        for (String url : new String[] {
                "file:///var/atlassian/application-data/confluence",
                "javascript:alert(document.domain)",
                "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
                "git://github.com/acme/billing.git",
                "http://github.com/acme/billing.git",
                "/var/lib/git/billing.git" })
        {
            try
            {
                RemoteUrl.parse(url);
                fail("must be refused: " + url);
            }
            catch (RemoteUrl.InvalidRemoteException e)
            {
                assertEquals(url, "scheme", e.reason());
            }
        }
    }

    /** B-2: an unauthenticated fetch of the instance's own loopback services. */
    @Test
    public void localAndLinkLocalHostsAreRefused()
    {
        for (String host : new String[] { "localhost", "127.0.0.1", "0.0.0.0",
                                          "169.254.169.254" })
        {
            try
            {
                RemoteUrl.parse("https://" + host + "/acme/billing.git");
                fail("must be refused: " + host);
            }
            catch (RemoteUrl.InvalidRemoteException e)
            {
                assertEquals(host, "localHost", e.reason());
            }
        }
    }

    /**
     * Private ranges stay allowed. This is a decision, not an oversight - an internal GitLab is
     * the main thing this plugin gets pointed at - so it is pinned here to stop a later
     * "hardening" pass from quietly closing the product.
     */
    @Test
    public void privateRangesAreAllowedOnPurpose() throws Exception
    {
        assertEquals("https://10.0.4.7/acme/billing.git",
                RemoteUrl.parse("https://10.0.4.7/acme/billing.git").url);
        assertEquals("ssh://git@192.168.1.20:2222/acme/billing.git",
                RemoteUrl.parse("ssh://git@192.168.1.20:2222/acme/billing.git").url);
    }

    /**
     * Under ssh a user name with no password stays in the URL.
     *
     * <p>Not cosmetic: JGit takes the ssh login from the URI, so an {@code ssh://git@github.com}
     * stripped down to {@code ssh://github.com} connects as whatever account Confluence runs
     * under and is refused. The credentials provider's user name does not stand in for it -
     * that is an https concept.</p>
     */
    @Test
    public void anSshUserNameIsAddressingAndSurvives() throws Exception
    {
        RemoteUrl ssh = RemoteUrl.parse("ssh://git@github.com/acme/billing.git");
        assertEquals("ssh://git@github.com/acme/billing.git", ssh.url);
        assertEquals("git", ssh.embeddedUser);
        assertFalse(ssh.carriedCredentials());
        assertFalse(RemoteUrl.carriesSecret("ssh://git@github.com/acme/billing.git"));

        assertEquals("ssh://git@github.com/acme/billing.git",
                RemoteUrl.sanitiseForDisplay("ssh://git@github.com/acme/billing.git"));
        assertEquals("an ssh password, on the other hand, never survives",
                "ssh://github.com/acme/billing.git",
                RemoteUrl.sanitiseForDisplay("ssh://git:hunter2@github.com/acme/billing.git"));
    }

    /**
     * B-1, the half that stayed open: a token alone in the user position.
     *
     * <p>{@code https://ghp_xxx@github.com/acme/billing.git} is a documented way to
     * authenticate to both GitHub and GitLab, and it has no colon in it. Treating a
     * password-less user name as addressing - which is right for ssh - left the token in the
     * URL column in clear text, from where the REST list and the report page hand it to every
     * logged-in user. Under https nothing in the user position is addressing.</p>
     */
    @Test
    public void anHttpsTokenInTheUserPositionIsACredential() throws Exception
    {
        String url = "https://ghp_1234567890abcdef@github.com/acme/billing.git";

        RemoteUrl parsed = RemoteUrl.parse(url);
        assertEquals("https://github.com/acme/billing.git", parsed.url);
        assertEquals("ghp_1234567890abcdef", parsed.embeddedSecret);
        assertEquals("", parsed.embeddedUser);
        assertTrue(parsed.carriedCredentials());

        assertTrue("a clone whose stored remote looks like this must be discarded",
                RemoteUrl.carriesSecret(url));
        assertEquals("https://github.com/acme/billing.git",
                RemoteUrl.sanitiseForDisplay(url));

        // Same treatment for a name that only looks like a login: guessing wrong the other way
        // stores a secret in the clear, and guessing wrong this way costs a failed fetch.
        assertEquals("bskim", RemoteUrl.parse("https://bskim@github.com/acme/billing.git")
                .embeddedSecret);
    }

    /** The scp-like form is ssh and carries no password. */
    @Test
    public void scpLikeFormIsAcceptedWithItsUserName() throws Exception
    {
        RemoteUrl parsed = RemoteUrl.parse("git@github.com:acme/billing.git");

        assertEquals("git@github.com:acme/billing.git", parsed.url);
        assertEquals("git", parsed.embeddedUser);
        assertFalse(parsed.carriedCredentials());
    }

    /**
     * What must count as the same remote.
     *
     * <p>Getting this wrong is expensive in both directions: too strict and a trailing
     * {@code .git} throws away every cached commit and re-clones, too loose and a repository
     * re-pointed at a different fork keeps serving the old history.</p>
     */
    @Test
    public void canonicalIgnoresOnlyWhatDoesNotChangeTheRepository()
    {
        String expected = "https://github.com/acme/billing";
        assertEquals(expected, RemoteUrl.canonical("https://github.com/acme/billing"));
        assertEquals(expected, RemoteUrl.canonical("https://github.com/acme/billing.git"));
        assertEquals(expected, RemoteUrl.canonical("https://github.com/acme/billing.git/"));
        assertEquals(expected, RemoteUrl.canonical("https://GitHub.com/acme/billing"));
        assertEquals(expected, RemoteUrl.canonical("  https://github.com/acme/billing  "));
        assertEquals("a rotated token does not move the remote", expected,
                RemoteUrl.canonical("https://x-access-token:ghp_new@github.com/acme/billing"));
        assertEquals("nor does a login name", expected,
                RemoteUrl.canonical("https://someone@github.com/acme/billing"));

        // Path case, host, port, scheme and owner all identify the repository.
        assertFalse(expected.equals(RemoteUrl.canonical("https://github.com/acme/Billing")));
        assertFalse(expected.equals(RemoteUrl.canonical("https://github.com/other/billing")));
        assertFalse(expected.equals(RemoteUrl.canonical("https://gitlab.com/acme/billing")));
        assertFalse(expected.equals(RemoteUrl.canonical("ssh://github.com/acme/billing")));
        assertFalse(expected.equals(RemoteUrl.canonical("https://github.com:8443/acme/billing")));
    }

    /** Recognising a clone written before validation existed, whose config still holds a token. */
    @Test
    public void carriesSecretSpotsAnythingInTheUserPosition()
    {
        assertTrue(RemoteUrl.carriesSecret(
                "https://x-access-token:ghp_secret@github.com/acme/billing.git"));
        assertTrue("under https a bare user name is a token as often as not",
                RemoteUrl.carriesSecret("https://someone@github.com/acme/billing.git"));
        assertFalse(RemoteUrl.carriesSecret("https://github.com/acme/billing.git"));
        assertFalse("under ssh the login name is addressing",
                RemoteUrl.carriesSecret("ssh://git@github.com/acme/billing.git"));
        assertTrue("but an ssh password is still a password",
                RemoteUrl.carriesSecret("ssh://git:hunter2@github.com/acme/billing.git"));
        assertFalse("the scp-like form has no password field",
                RemoteUrl.carriesSecret("git@github.com:acme/billing.git"));
        assertFalse("a colon in the path is not userinfo",
                RemoteUrl.carriesSecret("https://github.com/acme/bil:ling.git"));
        assertFalse(RemoteUrl.carriesSecret(null));
    }
}
