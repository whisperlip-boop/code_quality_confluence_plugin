package co.bskim.confluence.codequality.git;

/** Credentials for one remote. */
public final class RepoAuth
{
    public static final RepoAuth NONE = new RepoAuth("", "");

    public final String username;
    public final String token;

    public RepoAuth(String username, String token)
    {
        this.username = username == null ? "" : username;
        this.token = token == null ? "" : token;
    }

    public boolean isEmpty()
    {
        return token.isEmpty();
    }
}
