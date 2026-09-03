package co.bskim.confluence.codequality.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Glob matching for the exclude list. Supports {@code *}, {@code **} and {@code ?}. */
public final class PathMatcher
{
    private final List<Pattern> patterns = new ArrayList<Pattern>();

    public PathMatcher(String[] defaults, String extra)
    {
        for (String glob : defaults)
        {
            add(glob);
        }
        if (extra != null)
        {
            for (String line : extra.split("[\\r\\n,]+"))
            {
                add(line.trim());
            }
        }
    }

    private void add(String glob)
    {
        if (glob == null || glob.isEmpty() || glob.startsWith("#"))
        {
            return;
        }
        patterns.add(Pattern.compile(toRegex(glob)));
    }

    public boolean excludes(String path)
    {
        for (Pattern p : patterns)
        {
            if (p.matcher(path).matches())
            {
                return true;
            }
        }
        return false;
    }

    static String toRegex(String glob)
    {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++)
        {
            char c = glob.charAt(i);
            switch (c)
            {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*')
                    {
                        // "a/**/b" must also match "a/b", so swallow the slash that follows.
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/')
                        {
                            i++;
                            sb.append("(?:.*/)?");
                        }
                        else
                        {
                            sb.append(".*");
                        }
                    }
                    else
                    {
                        sb.append("[^/]*");
                    }
                    break;
                case '?':
                    sb.append("[^/]");
                    break;
                case '.':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '+':
                case '^':
                case '$':
                case '|':
                case '\\':
                    sb.append('\\').append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.append('$').toString();
    }
}
