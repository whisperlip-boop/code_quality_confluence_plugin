package co.bskim.confluence.codequality.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A registered repository read out as plain values.
 *
 * <p>Active Objects hands back proxies that hit the database on every getter, so an entity
 * fetched inside a transaction and read after it closed fails on a background thread with
 * "No Hibernate Session bound to thread". Everything the analysis job and the REST layer need
 * is therefore copied out while the transaction is still open.</p>
 */
public final class RepoSnapshot
{
    public final int id;
    public final String name;
    public final String url;
    public final String branch;
    public final String authType;
    public final String authUser;
    /** Still encrypted; only RepositoryService decrypts it. */
    public final String authSecret;
    /** Space keys this repository is visible in; empty means administrators only. */
    public final List<String> spaceKeys;
    public final String excludes;
    public final String thresholds;
    public final Long lastSyncedAt;
    public final String status;
    public final String statusMessage;

    public RepoSnapshot(int id, String name, String url, String branch, String authType,
                        String authUser, String authSecret, String spaceKeys, String excludes,
                        String thresholds, Long lastSyncedAt, String status,
                        String statusMessage)
    {
        this.spaceKeys = splitKeys(spaceKeys);
        this.id = id;
        this.name = blank(name);
        this.url = blank(url);
        this.branch = blank(branch);
        this.authType = blank(authType);
        this.authUser = blank(authUser);
        this.authSecret = blank(authSecret);
        this.excludes = blank(excludes);
        this.thresholds = blank(thresholds);
        this.lastSyncedAt = lastSyncedAt;
        this.status = status == null || status.isEmpty() ? "NEW" : status;
        this.statusMessage = blank(statusMessage);
    }

    public boolean hasToken()
    {
        return "PAT".equals(authType);
    }

    /** Space keys are stored as one string; the list is what every caller actually wants. */
    public static List<String> splitKeys(String raw)
    {
        List<String> keys = new ArrayList<String>();
        if (raw == null)
        {
            return keys;
        }
        for (String part : raw.split("[,\\s]+"))
        {
            String key = part.trim();
            if (!key.isEmpty() && !keys.contains(key))
            {
                keys.add(key);
            }
        }
        return keys;
    }

    public static String joinKeys(List<String> keys)
    {
        StringBuilder out = new StringBuilder();
        for (String key : keys)
        {
            if (out.length() > 0)
            {
                out.append(',');
            }
            out.append(key);
        }
        return out.toString();
    }

    private static String blank(String value)
    {
        return value == null ? "" : value;
    }
}
