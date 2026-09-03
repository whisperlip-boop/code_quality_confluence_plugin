package co.bskim.confluence.codequality.git;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates and sanitises a clone URL before anything is stored or fetched.
 *
 * <p>Three things went wrong without this. A URL of the documented GitHub form
 * {@code https://x-access-token:ghp_xxx@github.com/acme/billing.git} put a personal access
 * token into the database in clear text - bypassing {@link co.bskim.confluence.codequality
 * .service.TokenCipher} entirely - and handed it back to every logged-in user through the REST
 * list and the report page. {@code file:///var/atlassian/...} cloned another repository off the
 * server's own disk and published it. And an unchecked scheme reached the browser as an
 * {@code href}, so {@code javascript:} was stored XSS.</p>
 *
 * <p><b>Private address ranges are allowed on purpose.</b> An internal GitLab on 10.x is the
 * main thing this plugin is pointed at; blocking it to prevent port scanning would close the
 * product rather than the hole. The scanning signal came from returning raw connection errors
 * to the caller, and that is closed in {@link GitClient} by reporting a category instead.</p>
 *
 * <p><b>What the host check does and does not buy.</b> It refuses obvious and declared local
 * addresses - loopback, wildcard, link-local, multicast - at the moment a URL is saved and
 * again before each fetch. It does <b>not</b> survive a name whose answer changes between the
 * check and the connection: a host that resolves to a public address for us and to
 * {@code 169.254.169.254} for JGit passes, because the two resolutions are separate. Closing
 * that properly means connecting to the address we validated, which under JGit means a custom
 * connection factory that has to keep TLS verification pinned to the name - getting that wrong
 * is worse than the hole. So the cloud metadata address is not defended here; it belongs to
 * egress policy on the node. An unresolvable name is also allowed through deliberately, so a
 * Confluence node behind split-horizon DNS still works.</p>
 *
 * <p>The earlier version of this comment claimed link-local addresses were refused full stop,
 * which the code cannot promise. A security note that overstates its own guarantee is worse
 * than none - somebody reads it and stops looking.</p>
 */
public final class RemoteUrl
{
    /** Thrown for anything that must not be stored or fetched. */
    public static final class InvalidRemoteException extends Exception
    {
        private static final long serialVersionUID = 1L;
        private final String reason;

        InvalidRemoteException(String reason, String message)
        {
            super(message);
            this.reason = reason;
        }

        /** Stable key for the message shown to the user. */
        public String reason()
        {
            return reason;
        }
    }

    private static final int MAX_LENGTH = 2000;
    /** {@code git@host:owner/repo.git} - the scp-like form, which is not a URI. */
    private static final Pattern SCP_LIKE =
            Pattern.compile("^([A-Za-z0-9._%+-]+)@([A-Za-z0-9.\\-]+):(?!//)(.+)$");

    /** The sanitised URL: identical to the input except that any userinfo is gone. */
    public final String url;
    /** User name taken from the URL's userinfo, or empty. */
    public final String embeddedUser;
    /** Secret taken from the URL's userinfo, or empty. Treated exactly like a typed token. */
    public final String embeddedSecret;

    private RemoteUrl(String url, String embeddedUser, String embeddedSecret)
    {
        this.url = url;
        this.embeddedUser = embeddedUser;
        this.embeddedSecret = embeddedSecret;
    }

    public boolean carriedCredentials()
    {
        return !embeddedSecret.isEmpty();
    }

    public static RemoteUrl parse(String raw) throws InvalidRemoteException
    {
        if (raw == null || raw.trim().isEmpty())
        {
            throw new InvalidRemoteException("empty", "Repository URL is required");
        }
        String cleaned = raw.trim();
        if (cleaned.length() > MAX_LENGTH)
        {
            throw new InvalidRemoteException("tooLong", "Repository URL is too long");
        }
        for (int i = 0; i < cleaned.length(); i++)
        {
            if (cleaned.charAt(i) < 0x20 || cleaned.charAt(i) == 0x7f)
            {
                throw new InvalidRemoteException("control",
                        "Repository URL contains a control character");
            }
        }

        Matcher scp = SCP_LIKE.matcher(cleaned);
        if (scp.matches())
        {
            // git@host:owner/repo - ssh, and it cannot carry a password.
            checkHost(scp.group(2));
            return new RemoteUrl(cleaned, scp.group(1), "");
        }

        URI uri;
        try
        {
            uri = new URI(cleaned);
        }
        catch (URISyntaxException e)
        {
            throw new InvalidRemoteException("malformed", "Repository URL is not a valid URL");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"ssh".equals(scheme))
        {
            // Everything else is refused by name: file: reads the server's disk, git: and ftp:
            // are unauthenticated, javascript: and data: reach the browser as an href, and a
            // bare path is a local clone.
            throw new InvalidRemoteException("scheme",
                    "Only https:// and ssh:// repository URLs are accepted");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty())
        {
            throw new InvalidRemoteException("host", "Repository URL has no host");
        }
        checkHost(uri.getHost());

        String userInfo = uri.getUserInfo();
        String user = "";
        String secret = "";
        if (userInfo != null && !userInfo.isEmpty())
        {
            int colon = userInfo.indexOf(':');
            user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            secret = colon < 0 ? "" : userInfo.substring(colon + 1);
        }

        // The two schemes read userinfo differently, and conflating them leaks a token.
        //
        // ssh: the login name is addressing. JGit takes it from the URI rather than from the
        // credentials provider, so dropping the "git@" out of ssh://git@github.com/... makes
        // the clone connect as whatever account Confluence runs under and be refused.
        //
        // https: userinfo is only ever a credential. Both GitHub and GitLab accept a token on
        // its own in the user position - https://ghp_xxx@github.com/acme/billing.git - so a
        // bare name with no password has to be treated as the secret, not kept in the URL.
        // Keeping it stored a personal access token in clear text in the database and handed
        // it back through the REST list and the report page.
        String sanitised;
        if ("ssh".equals(scheme))
        {
            sanitised = secret.isEmpty() ? cleaned : rebuildWithoutUserInfo(uri, cleaned);
        }
        else
        {
            if (secret.isEmpty())
            {
                secret = user;
                user = "";
            }
            sanitised = rebuildWithoutUserInfo(uri, cleaned);
        }
        return new RemoteUrl(sanitised, user, secret);
    }

    /**
     * Strips userinfo without validating anything.
     *
     * <p>Rows written before validation existed may already carry a token in the URL, so this
     * runs on the way out as well as on the way in. A URL that cannot be parsed is returned
     * unchanged only when it holds no {@code @} before the first slash - otherwise the part
     * before it is dropped, because a token there matters more than a tidy URL.</p>
     */
    public static String sanitiseForDisplay(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return "";
        }
        String cleaned = raw.trim();
        if (!carriesSecret(cleaned))
        {
            // Nothing to hide: a bare user name stays, for the reason given in parse.
            return cleaned;
        }
        try
        {
            URI uri = new URI(cleaned);
            if (uri.getUserInfo() != null)
            {
                return rebuildWithoutUserInfo(uri, cleaned);
            }
            return cleaned;
        }
        catch (URISyntaxException e)
        {
            int scheme = cleaned.indexOf("://");
            if (scheme < 0)
            {
                return cleaned;
            }
            int slash = cleaned.indexOf('/', scheme + 3);
            int at = cleaned.lastIndexOf('@', slash < 0 ? cleaned.length() - 1 : slash);
            return at > scheme
                    ? cleaned.substring(0, scheme + 3) + cleaned.substring(at + 1)
                    : cleaned;
        }
    }

    /**
     * The form to use when asking "is this the same remote as that one".
     *
     * <p>Two decisions depend on that answer and have to agree: the cached per-commit rows are
     * dropped when a registration's URL changes, and the clone on disk is discarded when it no
     * longer matches the registration. A trailing slash, a trailing {@code .git} and the case of
     * the host do not make a different repository, and treating them as one would throw away a
     * full history replay over a typo fix. Userinfo is dropped for the same reason - rotating a
     * token does not move the remote.</p>
     */
    public static String canonical(String raw)
    {
        if (raw == null)
        {
            return "";
        }
        String cleaned = stripUserInfo(raw.trim());
        int scheme = cleaned.indexOf("://");
        if (scheme > 0)
        {
            // Scheme and host are case-insensitive; the path is not, and GitHub is only
            // forgiving about it by redirect.
            int slash = cleaned.indexOf('/', scheme + 3);
            int end = slash < 0 ? cleaned.length() : slash;
            cleaned = cleaned.substring(0, end).toLowerCase(Locale.ROOT) + cleaned.substring(end);
        }
        cleaned = trimTrailingSlashes(cleaned);
        if (cleaned.endsWith(".git"))
        {
            cleaned = trimTrailingSlashes(cleaned.substring(0, cleaned.length() - 4));
        }
        return cleaned;
    }

    /**
     * True when the URL's userinfo has to be treated as a credential.
     *
     * <p>Scheme-dependent, for the reason given in {@link #parse}: under https anything in the
     * user position is a secret, because a token alone is a documented way to authenticate;
     * under ssh only a password is, because the login name is addressing.</p>
     *
     * <p>Also used to recognise a clone written before validation existed - its
     * {@code remote.origin.url} still holds the token in clear text on disk, and the fix is to
     * throw that clone away rather than keep fetching with it. That is the aggressive
     * direction, which is the right one to err in.</p>
     */
    public static boolean carriesSecret(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return false;
        }
        String cleaned = raw.trim();
        int scheme = cleaned.indexOf("://");
        if (scheme < 0)
        {
            // The scp-like form has no password field at all.
            return false;
        }
        int slash = cleaned.indexOf('/', scheme + 3);
        String authority = slash < 0
                ? cleaned.substring(scheme + 3) : cleaned.substring(scheme + 3, slash);
        int at = authority.lastIndexOf('@');
        if (at <= 0)
        {
            return false;
        }
        if ("ssh".equals(cleaned.substring(0, scheme).toLowerCase(Locale.ROOT)))
        {
            return authority.substring(0, at).indexOf(':') >= 0;
        }
        return true;
    }

    /** Removes the whole userinfo from an authority, password or not. Identity, not secrecy. */
    private static String stripUserInfo(String cleaned)
    {
        int scheme = cleaned.indexOf("://");
        if (scheme < 0)
        {
            return cleaned;
        }
        int slash = cleaned.indexOf('/', scheme + 3);
        int end = slash < 0 ? cleaned.length() : slash;
        int at = cleaned.lastIndexOf('@', end - 1);
        return at > scheme
                ? cleaned.substring(0, scheme + 3) + cleaned.substring(at + 1) : cleaned;
    }

    private static String trimTrailingSlashes(String value)
    {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/')
        {
            end--;
        }
        return value.substring(0, end);
    }

    private static String rebuildWithoutUserInfo(URI uri, String original)
    {
        if (uri.getUserInfo() == null)
        {
            return original;
        }
        StringBuilder out = new StringBuilder();
        out.append(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() > 0)
        {
            out.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null)
        {
            out.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null)
        {
            out.append('?').append(uri.getRawQuery());
        }
        return out.toString();
    }

    /**
     * Re-runs the host check on an already-stored URL, immediately before it is used.
     *
     * <p>Validating only on save leaves a URL that was fine in March fetching from whatever
     * its name resolves to in September. This does not make the check atomic with the
     * connection - see the class comment - but it does mean the answer has to be acceptable
     * now rather than once.</p>
     */
    public static void revalidate(String raw) throws InvalidRemoteException
    {
        parse(raw);
    }

    /**
     * Refuses addresses no real remote lives at. Private ranges are deliberately not refused -
     * see the class comment.
     */
    private static void checkHost(String host) throws InvalidRemoteException
    {
        String lower = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lower) || lower.endsWith(".localhost")
                || "localhost.localdomain".equals(lower))
        {
            throw new InvalidRemoteException("localHost",
                    "That host is not a valid repository location");
        }
        InetAddress[] addresses;
        try
        {
            addresses = InetAddress.getAllByName(host);
        }
        catch (UnknownHostException e)
        {
            // An unresolvable name is not a security problem; the clone will fail on its own
            // and say so. Refusing here would break split-horizon DNS on a Confluence node.
            return;
        }
        for (InetAddress address : addresses)
        {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isMulticastAddress())
            {
                throw new InvalidRemoteException("localHost",
                        "That host is not a valid repository location");
            }
        }
    }
}
