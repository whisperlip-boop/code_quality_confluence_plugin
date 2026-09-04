package co.bskim.confluence.codequality.rest;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * What a registration is called when the form leaves the name empty.
 *
 * <p>Load-bearing twice over: it is what every screen displays - the table, the macro's picker
 * and the report's title - and it is what the macro's {@code repository} parameter stores and
 * matches on. It has been wrong in both directions. It was {@code owner/repo}, which is
 * unambiguous but is not what the repository is called anywhere else: its own settings page,
 * its clone directory and the person talking about it all say {@code dept_calendar}. And the
 * display derived it even when an administrator had typed a name into the field, so the table
 * and the report showed different names for one repository.</p>
 */
public class RepoNameTest
{
    /** The last path segment, which is the name the host itself shows. */
    @Test
    public void theNameIsWhatTheRepositoryIsCalled()
    {
        assertEquals("dept_calendar",
                RepoResource.deriveName("https://github.com/whisperlip-boop/dept_calendar.git"));
        assertEquals("no .git to strip", "dept_calendar",
                RepoResource.deriveName("https://github.com/whisperlip-boop/dept_calendar"));
        assertEquals("a trailing slash is not part of it", "dept_calendar",
                RepoResource.deriveName("https://github.com/whisperlip-boop/dept_calendar/"));
        assertEquals("the scp-like form names the same repository", "dept_calendar",
                RepoResource.deriveName("git@github.com:whisperlip-boop/dept_calendar.git"));
        assertEquals("nested groups, as GitLab has them", "billing",
                RepoResource.deriveName("https://gitlab.example.com/platform/team/billing.git"));
    }

    /**
     * Credentials in the URL are host, not name.
     *
     * <p>A name is displayed on a page and stored in macro parameters, so a token that reached
     * it would be published by the very screens this plugin exists to show.</p>
     */
    @Test
    public void aCredentialInTheUrlNeverReachesTheName()
    {
        assertEquals("api", RepoResource.deriveName(
                "https://x-access-token:ghp_secret@github.com/acme/api.git"));
        assertEquals("acme/api", RepoResource.deriveOwnerAndName(
                "https://x-access-token:ghp_secret@github.com/acme/api.git"));
    }

    /** The owner goes back on only when the short name would not tell two rows apart. */
    @Test
    public void theOwnerIsAddedWhenTheShortNameWouldCollide()
    {
        assertEquals("acme/api",
                RepoResource.deriveOwnerAndName("https://github.com/acme/api.git"));
        assertEquals("acme-fork/api",
                RepoResource.deriveOwnerAndName("https://github.com/acme-fork/api.git"));
        assertEquals("nested groups keep only the last two", "team/billing",
                RepoResource.deriveOwnerAndName(
                        "https://gitlab.example.com/platform/team/billing.git"));
    }

    /**
     * A URL with no path falls back to the host rather than to nothing.
     *
     * <p>Not a shape that gets this far - {@code RemoteUrl} refuses a remote with no repository
     * on it - but a name is what a row is identified by on every screen, so an empty one would
     * be worse than an odd one.</p>
     */
    @Test
    public void aUrlWithNoPathFallsBackToTheHost()
    {
        assertEquals("github.com", RepoResource.deriveName("https://github.com"));
        assertEquals("github.com", RepoResource.deriveName("https://github.com/"));
        assertEquals("github.com", RepoResource.deriveOwnerAndName("https://github.com"));
    }
}
