package co.bskim.confluence.codequality.analysis;

import co.bskim.confluence.codequality.model.Author;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folds commit identities into people.
 *
 * <p>Not an optional nicety. In the 27-commit reference repository a single developer showed
 * up as two authors ({@code whisperlip-boop} with 25 commits and {@code whisperlip} with 2),
 * which is enough to make ownership concentration and bus factor wrong. If this step is
 * skipped, every author-derived number in the report is wrong.</p>
 *
 * <p>Three rules, applied in order: {@code .mailmap} if the repository has one, GitHub's
 * {@code 12345+user@users.noreply.github.com} form reduced to the account, then a merge of
 * identities that share a display name.</p>
 */
public final class IdentityResolver
{
    private static final Pattern MAILMAP_FULL =
            Pattern.compile("^(.*?)\\s*<([^>]+)>\\s*(.*?)\\s*<([^>]+)>\\s*$");
    private static final Pattern MAILMAP_SHORT = Pattern.compile("^(.*?)\\s*<([^>]+)>\\s*$");
    private static final Pattern NOREPLY = Pattern.compile("^\\d+\\+(.+)$");

    /** Commit email (lower case) to canonical email. */
    private final Map<String, String> emailToCanonical = new HashMap<String, String>();
    /** Canonical email to preferred display name. */
    private final Map<String, String> canonicalName = new HashMap<String, String>();

    public IdentityResolver(String mailmap)
    {
        if (mailmap == null)
        {
            return;
        }
        for (String raw : mailmap.split("\n"))
        {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#"))
            {
                continue;
            }
            Matcher full = MAILMAP_FULL.matcher(line);
            if (full.matches())
            {
                String properName = full.group(1).trim();
                String properEmail = full.group(2).trim().toLowerCase(Locale.ROOT);
                String commitEmail = full.group(4).trim().toLowerCase(Locale.ROOT);
                emailToCanonical.put(commitEmail, properEmail);
                if (!properName.isEmpty())
                {
                    canonicalName.put(properEmail, properName);
                }
                continue;
            }
            Matcher short0 = MAILMAP_SHORT.matcher(line);
            if (short0.matches())
            {
                String properName = short0.group(1).trim();
                String email = short0.group(2).trim().toLowerCase(Locale.ROOT);
                if (!properName.isEmpty())
                {
                    canonicalName.put(email, properName);
                }
            }
        }
    }

    /** Stable key for one commit's author. */
    public String keyFor(String name, String email)
    {
        String normalised = normaliseEmail(email);
        String mapped = emailToCanonical.get(normalised);
        if (mapped != null)
        {
            return mapped;
        }
        if (!normalised.isEmpty())
        {
            return normalised;
        }
        return "name:" + displayKey(name);
    }

    public String preferredName(String key, String commitName)
    {
        String mapped = canonicalName.get(key);
        return mapped != null ? mapped : commitName;
    }

    static String normaliseEmail(String email)
    {
        if (email == null)
        {
            return "";
        }
        String lower = email.trim().toLowerCase(Locale.ROOT);
        int at = lower.indexOf('@');
        if (at < 0)
        {
            return lower;
        }
        String local = lower.substring(0, at);
        String host = lower.substring(at + 1);
        if ("users.noreply.github.com".equals(host))
        {
            Matcher m = NOREPLY.matcher(local);
            if (m.matches())
            {
                local = m.group(1);
            }
        }
        return local + "@" + host;
    }

    /**
     * Merges authors that share a display name into the one with the most commits. Runs after
     * the walk, because "most commits" is only known then.
     */
    public static Map<String, Author> mergeByDisplayName(Map<String, Author> authors)
    {
        Map<String, String> nameToWinner = new HashMap<String, String>();
        List<Author> ordered = new ArrayList<Author>(authors.values());
        // Deterministic winner: most commits, then lowest key.
        java.util.Collections.sort(ordered, new java.util.Comparator<Author>()
        {
            @Override
            public int compare(Author a, Author b)
            {
                if (a.commits != b.commits)
                {
                    return b.commits - a.commits;
                }
                return a.key.compareTo(b.key);
            }
        });

        for (Author author : ordered)
        {
            String nameKey = displayKey(author.name);
            if (nameKey.isEmpty())
            {
                continue;
            }
            if (!nameToWinner.containsKey(nameKey))
            {
                nameToWinner.put(nameKey, author.key);
            }
        }

        Map<String, Author> merged = new HashMap<String, Author>();
        for (Author author : ordered)
        {
            String target = nameToWinner.get(displayKey(author.name));
            if (target == null)
            {
                target = author.key;
            }
            Author into = merged.get(target);
            if (into == null)
            {
                into = new Author();
                into.key = target;
                into.name = author.name;
                into.email = author.email;
                merged.put(target, into);
            }
            into.commits += author.commits;
            into.addedLines += author.addedLines;
            into.identities.addAll(author.identities);
        }
        return merged;
    }

    /**
     * Identities that look like one person but were left separate.
     *
     * <p>Not merged on purpose. In the reference repository {@code whisperlip-boop} and
     * {@code whisperlip} are the same developer, and a prefix rule would fold them - but the
     * same rule folds {@code kim} into {@code kim.bs}, and a wrongly merged author corrupts
     * ownership and bus factor exactly as badly as a wrongly split one. So this reports the
     * suspicion and points at {@code .mailmap}, which is the only mechanism that actually
     * knows.</p>
     */
    public static List<String[]> suspects(Map<String, Author> authors)
    {
        List<Author> ordered = new ArrayList<Author>(authors.values());
        java.util.Collections.sort(ordered, new java.util.Comparator<Author>()
        {
            @Override
            public int compare(Author a, Author b)
            {
                return a.key.compareTo(b.key);
            }
        });
        List<String[]> pairs = new ArrayList<String[]>();
        for (int i = 0; i < ordered.size() && pairs.size() < 5; i++)
        {
            for (int j = i + 1; j < ordered.size() && pairs.size() < 5; j++)
            {
                if (related(ordered.get(i), ordered.get(j)))
                {
                    pairs.add(new String[] { ordered.get(i).name, ordered.get(j).name });
                }
            }
        }
        return pairs;
    }

    private static boolean related(Author a, Author b)
    {
        return sharesPrefix(displayKey(a.name), displayKey(b.name))
                || sharesPrefix(localPart(a.email), localPart(b.email));
    }

    private static boolean sharesPrefix(String a, String b)
    {
        if (a.length() < 4 || b.length() < 4 || a.equals(b))
        {
            return !a.isEmpty() && a.equals(b);
        }
        return a.startsWith(b) || b.startsWith(a);
    }

    private static String localPart(String email)
    {
        String normalised = normaliseEmail(email);
        int at = normalised.indexOf('@');
        return at < 0 ? normalised : normalised.substring(0, at);
    }

    static String displayKey(String name)
    {
        if (name == null)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++)
        {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c))
            {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
