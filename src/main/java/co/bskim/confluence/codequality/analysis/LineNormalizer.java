package co.bskim.confluence.codequality.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns a blob into the two line views the metrics need.
 *
 * <p>Comment stripping is quote-aware but deliberately not a parser: a {@code #} inside a
 * Python string or a {@code //} inside a JavaScript string is handled, a regex literal that
 * happens to contain {@code //} is not. The metrics are trend indicators, so a handful of
 * mis-stripped lines in a repository changes nothing; a hard dependency on a native parser
 * would have changed everything, because a Confluence P2 plugin cannot load one.</p>
 */
public final class LineNormalizer
{
    /**
     * Import, package and require lines are dropped from the matching corpus.
     *
     * <p>They are identical across every file of a package by construction, and counting them
     * is the same mistake the line-level copy-paste classifier made with language idioms. On a
     * Java plugin repository this alone accounted for roughly half of all "duplication": a
     * twelve-line {@code import javax.ws.rs.*} block shared by two REST resources was being
     * reported as a twelve-line clone. They stay in the LOC and comment counts - only the
     * duplication and copy-paste matching ignores them.</p>
     */
    private static final Pattern PY_DECLARATION =
            Pattern.compile("^(?:import|from)\\s+\\S.*$");
    private static final Pattern JAVA_DECLARATION =
            Pattern.compile("^(?:package|import)\\s+\\S.*$");
    private static final Pattern JS_DECLARATION = Pattern.compile(
            "^(?:import|export)\\s.*$"
            + "|^(?:const|let|var)\\s+[\\w{}$,\\s]+=\\s*require\\s*\\(.*$"
            + "|^require\\s*\\(.*$"
            + "|^['\"]use (?:strict|client)['\"];?$"
            + "|^\\}?\\s*from\\s+['\"].*$");

    /**
     * Lines that carry a literal and nothing else - no call, no assignment, no keyword.
     *
     * <p>A file made almost entirely of these is a generated lookup table, not code somebody
     * maintains, and counting it wrecks the duplication ratio. Measured across 20 public Python
     * repositories: rich's whole 20.4% came from {@code rich/_unicode_data/unicode*.py}, one
     * generated character-width table per Unicode version, which are near-identical to each
     * other by definition. No glob catches that class reliably, but its shape is unmistakable.</p>
     */
    private static final Pattern CALL_SHAPE =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(");
    private static final Pattern ASSIGN_SHAPE =
            Pattern.compile("(?<![=!<>+\\-*/%&|^])=(?!=)");
    private static final Pattern KEYWORD_SHAPE = Pattern.compile(
            "\\b(?:if|else|elif|for|while|return|def|class|function|import|from|try|except"
            + "|catch|finally|switch|case|new|throw|throws|yield|await|async|with|lambda|and"
            + "|or|not|in|is|var|let|const|public|private|protected|static|void|interface"
            + "|enum|extends|implements|package|assert|del|global|pass|raise|break|continue"
            + "|typeof|instanceof|delete|export|default)\\b");

    /** A file needs at least this many code lines before its shape is judged. */
    private static final int DATA_TABLE_MIN_LINES = 30;
    private static final double DATA_TABLE_SHARE = 0.8;

    private LineNormalizer()
    {
    }

    static boolean isLiteralOnly(String collapsed)
    {
        return !CALL_SHAPE.matcher(collapsed).find()
                && !ASSIGN_SHAPE.matcher(collapsed).find()
                && !KEYWORD_SHAPE.matcher(collapsed).find();
    }

    static boolean isDeclaration(String collapsed, Language lang)
    {
        switch (lang)
        {
            case PYTHON:
                return PY_DECLARATION.matcher(collapsed).matches();
            case JAVA:
                return JAVA_DECLARATION.matcher(collapsed).matches();
            case JAVASCRIPT:
                return JS_DECLARATION.matcher(collapsed).matches();
            default:
                return false;
        }
    }

    public static FileLines parse(String content, Language lang)
    {
        List<String> code = new ArrayList<String>();
        List<Integer> codeNo = new ArrayList<Integer>();
        List<String> norm = new ArrayList<String>();
        List<Integer> normNo = new ArrayList<Integer>();

        boolean[] inBlockComment = { false };
        int lineNo = 0;
        int from = 0;
        int len = content.length();
        int commentLines = 0;
        int totalLines = 0;
        int literalOnly = 0;

        while (from <= len)
        {
            int nl = content.indexOf('\n', from);
            int end = nl < 0 ? len : nl;
            String raw = content.substring(from, end);
            if (!raw.isEmpty() && raw.charAt(raw.length() - 1) == '\r')
            {
                raw = raw.substring(0, raw.length() - 1);
            }
            lineNo++;
            boolean rawBlank = raw.trim().isEmpty();
            if (!rawBlank)
            {
                totalLines++;
            }

            String stripped = lang == Language.PYTHON
                    ? stripPython(raw)
                    : stripCStyle(raw, inBlockComment);
            stripped = rtrim(stripped);

            if (!stripped.isEmpty() && !isBareTripleQuote(stripped, lang))
            {
                code.add(stripped);
                codeNo.add(lineNo);

                String collapsed = collapse(stripped);
                if (isLiteralOnly(collapsed))
                {
                    literalOnly++;
                }
                if (collapsed.length() >= AnalysisConfig.MIN_LINE_LENGTH
                        && !isDeclaration(collapsed, lang))
                {
                    norm.add(collapsed);
                    normNo.add(lineNo);
                }
            }
            else if (!rawBlank)
            {
                // A non-blank line that stripped away to nothing was a comment.
                commentLines++;
            }

            if (nl < 0)
            {
                break;
            }
            from = nl + 1;
        }

        boolean dataTable = code.size() >= DATA_TABLE_MIN_LINES
                && literalOnly >= code.size() * DATA_TABLE_SHARE;
        return new FileLines(code, toArray(codeNo), norm, toArray(normNo),
                commentLines, totalLines, dataTable);
    }

    /** A line that is nothing but a docstring delimiter carries no information. */
    private static boolean isBareTripleQuote(String stripped, Language lang)
    {
        if (lang != Language.PYTHON)
        {
            return false;
        }
        String t = stripped.trim();
        return "\"\"\"".equals(t) || "'''".equals(t);
    }

    static String stripPython(String line)
    {
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        int n = line.length();
        while (i < n)
        {
            char c = line.charAt(i);
            if (c == '#')
            {
                break;
            }
            if (c == '"' || c == '\'')
            {
                i = copyQuoted(line, i, out, true);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    static String stripCStyle(String line, boolean[] inBlockComment)
    {
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        int n = line.length();
        while (i < n)
        {
            if (inBlockComment[0])
            {
                int close = line.indexOf("*/", i);
                if (close < 0)
                {
                    return out.toString();
                }
                inBlockComment[0] = false;
                i = close + 2;
                continue;
            }
            char c = line.charAt(i);
            if (c == '/' && i + 1 < n)
            {
                char next = line.charAt(i + 1);
                if (next == '/')
                {
                    break;
                }
                if (next == '*')
                {
                    inBlockComment[0] = true;
                    i += 2;
                    continue;
                }
            }
            if (c == '"' || c == '\'' || c == '`')
            {
                i = copyQuoted(line, i, out, false);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Copies a quoted run starting at {@code start} and returns the index just past it.
     * An unterminated quote consumes the rest of the line, which keeps a stray apostrophe in
     * prose from swallowing a real comment marker further along.
     */
    private static int copyQuoted(String line, int start, StringBuilder out, boolean python)
    {
        char quote = line.charAt(start);
        int n = line.length();
        int i = start;

        int runLength = 1;
        if (python && i + 2 < n && line.charAt(i + 1) == quote && line.charAt(i + 2) == quote)
        {
            runLength = 3;
        }
        String delimiter = runLength == 3 ? line.substring(i, i + 3) : String.valueOf(quote);

        out.append(delimiter);
        i += runLength;
        while (i < n)
        {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < n)
            {
                out.append(c).append(line.charAt(i + 1));
                i += 2;
                continue;
            }
            if (runLength == 3)
            {
                if (line.startsWith(delimiter, i))
                {
                    out.append(delimiter);
                    return i + 3;
                }
            }
            else if (c == quote)
            {
                out.append(c);
                return i + 1;
            }
            out.append(c);
            i++;
        }
        return n;
    }

    /** Collapses whitespace runs to a single space so that reindentation is not a change. */
    static String collapse(String s)
    {
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (Character.isWhitespace(c))
            {
                space = true;
                continue;
            }
            if (space && sb.length() > 0)
            {
                sb.append(' ');
            }
            space = false;
            sb.append(c);
        }
        return sb.toString();
    }

    static String rtrim(String s)
    {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1)))
        {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    /** Word-ish token count, used for the duplicate detector's minimum-token rule. */
    public static int tokenCount(String line)
    {
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            boolean word = Character.isLetterOrDigit(c) || c == '_' || c == '$';
            if (word)
            {
                if (!inWord)
                {
                    count++;
                }
            }
            else if (c != ' ')
            {
                // Operators and punctuation are tokens too, the way jscpd counts them.
                count++;
            }
            inWord = word;
        }
        return count;
    }

    private static int[] toArray(List<Integer> list)
    {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = list.get(i);
        }
        return out;
    }
}
