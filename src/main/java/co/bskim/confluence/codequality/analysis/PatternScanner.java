package co.bskim.confluence.codequality.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B2 (error-swallowing density) and B3 (function connectivity).
 *
 * <p>The PoC used tree-sitter for both. A Confluence P2 plugin cannot load a native parser, so
 * this is a line scanner over comment-stripped code with block tracking. It catches the shapes
 * that matter - bare handlers, over-broad handlers, empty bodies, log-and-continue - and it
 * will miss a handler written across an unusual line break.</p>
 *
 * <p>Connectivity is explicitly an approximation and is labelled as such in the report: it
 * counts call-shaped tokens, not resolved symbols. Getting that right needs real name
 * resolution, which is a different order of cost.</p>
 */
public final class PatternScanner
{
    public static final class Result
    {
        public int bare;
        public int broad;
        public int swallow;
        public int suppress;
        public int calls;

        /** Legacy-metric inputs: function count, decision points, lines inside function bodies. */
        public int functions;
        public int decisions;
        public int functionLines;

        public int handlers()
        {
            return bare + broad + swallow + suppress;
        }
    }

    private static final Pattern PY_BARE = Pattern.compile("^except\\s*:.*$");
    private static final Pattern PY_BROAD =
            Pattern.compile("^except\\s+\\(?\\s*(?:Exception|BaseException)\\b.*$");
    private static final Pattern JAVA_BROAD = Pattern.compile(
            "\\bcatch\\s*\\(\\s*(?:final\\s+)?(?:Exception|Throwable|RuntimeException)\\b");
    private static final Pattern CALL = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    private static final Pattern LOG_ONLY = Pattern.compile(
            "^(?:\\}?\\s*)?(?:e|ex|err|error|t)?\\.?printStackTrace\\s*\\(\\s*\\)\\s*;?$"
            + "|^console\\s*\\.\\s*(?:log|warn|error|debug|info)\\s*\\(.*$"
            + "|^(?:logger|log|LOG|LOGGER)\\s*\\.\\s*(?:debug|trace)\\s*\\(.*$");

    /**
     * Decision points for the legacy cyclomatic-complexity figure. Counted by keyword and
     * operator, the way every pre-AST complexity tool did it - which is the point: this metric
     * is on the report as the thing being replaced, not as a measurement to trust.
     */
    private static final Pattern PY_DECISION = Pattern.compile(
            "\\b(?:if|elif|for|while|except|and|or|assert)\\b|\\bif\\s+.*\\belse\\b");
    private static final Pattern JAVA_DECISION = Pattern.compile(
            "\\b(?:if|for|while|case|catch)\\b|&&|\\|\\||\\?");
    private static final Pattern JS_DECISION = Pattern.compile(
            "\\b(?:if|for|while|case|catch)\\b|&&|\\|\\||\\?\\??");

    private static final Pattern PY_FUNCTION =
            Pattern.compile("^(?:async\\s+)?def\\s+\\w+\\s*\\(.*$");
    private static final Pattern JS_FUNCTION = Pattern.compile(
            "\\bfunction\\b|=>\\s*\\{?\\s*$|^\\w+\\s*\\([^)]*\\)\\s*\\{\\s*$");
    /** A signature-shaped line: has a parameter list and opens a block, and is not a control
     *  statement. Loose on purpose - a legacy average does not need to be exact. */
    private static final Pattern JAVA_FUNCTION = Pattern.compile(
            "^(?!\\s*(?:if|for|while|switch|catch|do|else|try|synchronized|return)\\b)"
            + "[\\w@<>\\[\\]., ]*\\w\\s*\\([^;]*\\)\\s*(?:throws[\\w, .]*)?\\{\\s*$");

    private static final Set<String> PY_KEYWORDS = keywords(
            "if,elif,while,for,return,def,class,lambda,with,except,assert,del,global,nonlocal,"
            + "yield,raise,not,and,or,in,is,else,try,finally,import,from,await,async");
    private static final Set<String> JAVA_KEYWORDS = keywords(
            "if,while,for,switch,catch,return,new,synchronized,assert,do,else,try,instanceof,"
            + "throw,throws,case");
    private static final Set<String> JS_KEYWORDS = keywords(
            "if,while,for,switch,catch,return,new,function,typeof,delete,void,await,yield,do,"
            + "else,try,throw,case,in,of");

    private PatternScanner()
    {
    }

    public static void scan(Language lang, FileLines file, Result out)
    {
        switch (lang)
        {
            case PYTHON:
                scanPython(file.code, out);
                countCalls(file.code, PY_KEYWORDS, out);
                countPythonFunctions(file.code, out);
                countDecisions(file.code, PY_DECISION, out);
                break;
            case JAVA:
                scanBraced(file.code, true, out);
                countCalls(file.code, JAVA_KEYWORDS, out);
                countBracedFunctions(file.code, JAVA_FUNCTION, out);
                countDecisions(file.code, JAVA_DECISION, out);
                break;
            case JAVASCRIPT:
                scanBraced(file.code, false, out);
                countCalls(file.code, JS_KEYWORDS, out);
                countBracedFunctions(file.code, JS_FUNCTION, out);
                countDecisions(file.code, JS_DECISION, out);
                break;
            default:
                break;
        }
    }

    private static void scanPython(List<String> code, Result out)
    {
        for (int i = 0; i < code.size(); i++)
        {
            String line = code.get(i);
            String trimmed = line.trim();

            if (trimmed.contains("suppress(")
                    && (trimmed.contains("contextlib.suppress(") || trimmed.startsWith("with suppress(")))
            {
                out.suppress++;
            }
            if (!trimmed.startsWith("except"))
            {
                continue;
            }

            boolean bare = PY_BARE.matcher(trimmed).matches();
            boolean broad = !bare && PY_BROAD.matcher(trimmed).matches();
            List<String> body = pythonBody(code, i, indentOf(line));
            boolean swallowed = !body.isEmpty() && onlyNoOps(body);

            if (swallowed)
            {
                out.swallow++;
            }
            else if (bare)
            {
                out.bare++;
            }
            else if (broad)
            {
                out.broad++;
            }
        }
    }

    private static boolean onlyNoOps(List<String> body)
    {
        for (String line : body)
        {
            String t = line.trim();
            if (!"pass".equals(t) && !"...".equals(t) && !LOG_ONLY.matcher(t).matches())
            {
                return false;
            }
        }
        return true;
    }

    private static List<String> pythonBody(List<String> code, int headerIndex, int headerIndent)
    {
        List<String> body = new ArrayList<String>();
        for (int i = headerIndex + 1; i < code.size(); i++)
        {
            if (indentOf(code.get(i)) <= headerIndent)
            {
                break;
            }
            body.add(code.get(i));
        }
        return body;
    }

    private static void scanBraced(List<String> code, boolean java, Result out)
    {
        for (int i = 0; i < code.size(); i++)
        {
            String trimmed = code.get(i).trim();

            // Promise-style swallowing: .catch(() => {}) and .catch(e => console.log(e)).
            if (!java && trimmed.contains(".catch("))
            {
                String tail = trimmed.substring(trimmed.indexOf(".catch(") + 7);
                if (tail.replaceAll("\\s", "").matches("^\\(?\\w*\\)?=>\\{?\\}?\\)?;?$")
                        || tail.contains("console."))
                {
                    out.swallow++;
                }
            }

            int catchAt = indexOfCatch(trimmed);
            if (catchAt < 0)
            {
                continue;
            }

            boolean broad = java && JAVA_BROAD.matcher(trimmed).find();
            List<String> body = bracedBody(code, i, catchAt);
            boolean swallowed = body == null || body.isEmpty() || onlyNoOps(body);

            if (swallowed)
            {
                out.swallow++;
            }
            else if (broad)
            {
                out.broad++;
            }
        }
    }

    /** Position of a {@code catch} keyword, ignoring identifiers that merely contain it. */
    private static int indexOfCatch(String line)
    {
        int from = 0;
        while (true)
        {
            int at = line.indexOf("catch", from);
            if (at < 0)
            {
                return -1;
            }
            boolean startOk = at == 0 || !isWordChar(line.charAt(at - 1));
            int after = at + 5;
            boolean endOk = after >= line.length() || !isWordChar(line.charAt(after));
            if (startOk && endOk)
            {
                return at;
            }
            from = at + 5;
        }
    }

    /**
     * Trimmed lines inside the block that opens at or after {@code (index, column)}.
     * Returns null when no opening brace is found within a few lines, which is the shape this
     * scanner cannot read and therefore does not judge.
     */
    private static List<String> bracedBody(List<String> code, int index, int column)
    {
        int depth = 0;
        boolean opened = false;
        List<String> body = new ArrayList<String>();
        int limit = Math.min(code.size(), index + 400);

        for (int i = index; i < limit; i++)
        {
            String line = code.get(i);
            int from = i == index ? column : 0;
            StringBuilder fragment = new StringBuilder();
            for (int c = from; c < line.length(); c++)
            {
                char ch = line.charAt(c);
                if (ch == '{')
                {
                    depth++;
                    if (depth == 1)
                    {
                        opened = true;
                        fragment.setLength(0);
                        continue;
                    }
                }
                else if (ch == '}')
                {
                    depth--;
                    if (depth == 0)
                    {
                        addIfCode(body, fragment.toString());
                        return body;
                    }
                }
                if (opened)
                {
                    fragment.append(ch);
                }
            }
            if (!opened && i > index + 2)
            {
                return null;
            }
            if (opened)
            {
                addIfCode(body, fragment.toString());
            }
        }
        return opened ? body : null;
    }

    private static void addIfCode(List<String> body, String fragment)
    {
        String t = fragment.trim();
        if (!t.isEmpty() && !";".equals(t))
        {
            body.add(t);
        }
    }

    private static void countCalls(List<String> code, Set<String> keywords, Result out)
    {
        for (String line : code)
        {
            Matcher m = CALL.matcher(line);
            while (m.find())
            {
                if (!keywords.contains(m.group(1)))
                {
                    out.calls++;
                }
            }
        }
    }

    private static void countDecisions(List<String> code, Pattern decision, Result out)
    {
        for (String line : code)
        {
            Matcher m = decision.matcher(line);
            while (m.find())
            {
                out.decisions++;
            }
        }
    }

    private static void countPythonFunctions(List<String> code, Result out)
    {
        for (int i = 0; i < code.size(); i++)
        {
            String line = code.get(i);
            if (!PY_FUNCTION.matcher(line.trim()).matches())
            {
                continue;
            }
            out.functions++;
            out.functionLines += pythonBody(code, i, indentOf(line)).size();
        }
    }

    private static void countBracedFunctions(List<String> code, Pattern signature, Result out)
    {
        int i = 0;
        while (i < code.size())
        {
            String line = code.get(i);
            if (!signature.matcher(line.trim()).find())
            {
                i++;
                continue;
            }
            int brace = line.indexOf('{');
            List<String> body = brace < 0 ? null : bracedBody(code, i, brace);
            out.functions++;
            if (body != null)
            {
                out.functionLines += body.size();
                // Skip past the body so nested arrow callbacks are not counted as functions
                // of their own - a legacy average counts declared methods, not closures.
                i = skipPast(code, i, brace);
                continue;
            }
            i++;
        }
    }

    /** Index of the line after the block opening at {@code (index, column)}. */
    private static int skipPast(List<String> code, int index, int column)
    {
        int depth = 0;
        boolean opened = false;
        int limit = Math.min(code.size(), index + 400);
        for (int i = index; i < limit; i++)
        {
            String line = code.get(i);
            int from = i == index ? column : 0;
            for (int c = from; c < line.length(); c++)
            {
                char ch = line.charAt(c);
                if (ch == '{')
                {
                    depth++;
                    opened = true;
                }
                else if (ch == '}')
                {
                    depth--;
                    if (opened && depth == 0)
                    {
                        return i + 1;
                    }
                }
            }
        }
        return index + 1;
    }

    static int indentOf(String line)
    {
        int indent = 0;
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            if (c == ' ')
            {
                indent++;
            }
            else if (c == '\t')
            {
                indent += 4;
            }
            else
            {
                break;
            }
        }
        return indent;
    }

    private static boolean isWordChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static Set<String> keywords(String csv)
    {
        return new HashSet<String>(Arrays.asList(csv.split(",")));
    }
}
