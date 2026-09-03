package co.bskim.confluence.codequality.service;

import co.bskim.confluence.codequality.analysis.AnalysisConfig;
import co.bskim.confluence.codequality.analysis.AnalysisEngine;
import co.bskim.confluence.codequality.analysis.CommitStats;
import co.bskim.confluence.codequality.analysis.DuplicateDetector;
import co.bskim.confluence.codequality.analysis.MirrorTrees;
import co.bskim.confluence.codequality.analysis.Thresholds;
import co.bskim.confluence.codequality.model.Author;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns raw per-commit metrics into the payload the report page renders.
 *
 * <p>Deliberately locale-independent: findings are emitted as a code plus parameters and are
 * worded at render time. A report is stored once and may be read in either language, and
 * re-analysing a repository to change its language would be absurd.</p>
 *
 * <p>Two rules from the reference analysis are enforced here rather than left to the UI.
 * Duplication is always published as an absolute count next to its ratio, because the ratio
 * alone stayed flat while the absolute count grew 42%. And churn for commits inside the
 * trailing 14 days is marked, never averaged in, because that window has not closed.</p>
 */
public final class ReportBuilder
{
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /**
     * How many clone pairs the table carries. The count is not the number found - the tile
     * says 412 and the table said 412 while listing 60 - so the page has to name both.
     */
    private static final int CLONE_TABLE_LIMIT = 60;

    private ReportBuilder()
    {
    }

    public static String build(String repoName, String repoUrl, AnalysisEngine.Outcome outcome,
                               Thresholds thresholds, long analysedAt)
    {
        List<CommitStats> all = new ArrayList<CommitStats>();
        for (CommitStats row : outcome.commits)
        {
            if (row != null)
            {
                all.add(row);
            }
        }

        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("repo", repoBlock(repoName, repoUrl, outcome, all, analysedAt));

        Aggregates agg = aggregate(all, outcome, thresholds);
        report.put("legacy", legacyBlock(agg));
        report.put("kpis", kpis(agg, thresholds, all));
        report.put("grades", grades(agg));
        report.put("series", series(all));
        report.put("clones", clones(outcome.headClones));
        report.put("authors", authors(outcome));
        report.put("findings", findings(agg, outcome, all, thresholds));
        report.put("caveats", caveats(agg, all, outcome));
        report.put("thresholds", thresholds);
        return new Gson().toJson(report);
    }

    // ------------------------------------------------------------------ header

    private static Map<String, Object> repoBlock(String name, String url,
                                                 AnalysisEngine.Outcome outcome,
                                                 List<CommitStats> all, long analysedAt)
    {
        long first = all.isEmpty() ? analysedAt : all.get(0).committedAt;
        long last = all.isEmpty() ? analysedAt : all.get(all.size() - 1).committedAt;

        Map<String, Object> repo = new LinkedHashMap<String, Object>();
        repo.put("name", name);
        repo.put("url", url);
        repo.put("browseBase", browseBase(url));
        repo.put("branch", outcome.branch);
        repo.put("headSha", outcome.headSha);
        repo.put("headShort", shortSha(outcome.headSha));
        repo.put("headCommittedAt", outcome.headCommittedAt);
        repo.put("analysedAt", analysedAt);
        repo.put("commits", all.size());
        repo.put("firstCommitAt", first);
        repo.put("spanDays", Math.max(1, (last - first) / DAY_MS));
        repo.put("loc", outcome.headLoc);
        repo.put("files", outcome.headFiles);
        repo.put("authorCount", outcome.authors.size());
        repo.put("identityCount", outcome.rawIdentityCount);
        repo.put("algoVersion", AnalysisConfig.ALGO_VERSION);
        repo.put("replayedFrom", outcome.replayedFrom);
        repo.put("cachedCommits", outcome.replayedFrom);
        return repo;
    }

    /** Blob URL prefix, so findings can link straight at the offending line on GitHub. */
    static String browseBase(String url)
    {
        if (url == null)
        {
            return "";
        }
        String cleaned = url.trim();
        if (cleaned.endsWith(".git"))
        {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        if (cleaned.startsWith("git@"))
        {
            int colon = cleaned.indexOf(':');
            if (colon > 0)
            {
                cleaned = "https://" + cleaned.substring(4, colon) + "/"
                        + cleaned.substring(colon + 1);
            }
        }
        int at = cleaned.indexOf('@');
        int scheme = cleaned.indexOf("://");
        if (scheme > 0 && at > scheme)
        {
            // Strip any credentials that were pasted into the URL.
            cleaned = cleaned.substring(0, scheme + 3) + cleaned.substring(at + 1);
        }
        return cleaned;
    }

    // ------------------------------------------------------------------ aggregates

    private static final class Aggregates
    {
        int countedCommits;
        int importCommits;
        int censoredCommits;
        int added;
        int copied;
        int moved;
        int novel;
        double copyPct;
        double refactorPct;
        double churnPct;
        int churnAdded;
        int churnLines;

        int dupLines;
        int dupClones;
        double dupPct;
        double dupDeltaPct;
        int dupDeltaLines;
        int dupLinesThen;
        /** Denominator of the duplication ratio: HEAD's code lines less any mirror subtree. */
        int dupMeasuredLoc;
        int windowDays;
        /**
         * Days between the reference commit and HEAD, which is not the window when there was
         * no sample inside it. Labelling a 300-day-old baseline "vs 90d ago" is a claim about
         * the data, not a caption.
         */
        int referenceDays;
        /** False when a ratio cannot be formed: no baseline, or a baseline of zero. */
        boolean dupDeltaKnown;
        boolean connDeltaKnown;
        /** Where the repository sits, separately from where it is heading. */
        String stateDupLevel = Thresholds.UNKNOWN;
        String dirDup = Thresholds.UNKNOWN;
        String dupAxis = "";
        boolean dupLevelApplicable;
        Thresholds.LevelBand dupBand;
        String dominantLanguage = "";

        double legacyComplexity;
        double legacyCommentDensity;
        double legacyFunctionLength;
        // Boxed on purpose: null is "no baseline to compare against", and a double would
        // report that as a measured 0.0% change.
        Double legacyDupDelta;
        Double legacyComplexityDelta;
        Double legacyCommentDelta;
        Double legacyFunctionLengthDelta;

        double errDensity;
        double connDensity;
        double connDeltaPct;
        double connThen;

        int busFactor;
        boolean identityCollision;

        CommitStats churnSpike;
        double churnSpikePct;

        String stateCopy = Thresholds.UNKNOWN;
        String stateRefactor = Thresholds.UNKNOWN;
        String stateChurn = Thresholds.UNKNOWN;
        String stateDupDelta = Thresholds.UNKNOWN;
        String stateErr = Thresholds.UNKNOWN;
        String stateConn = Thresholds.UNKNOWN;
    }

    private static Aggregates aggregate(List<CommitStats> all, AnalysisEngine.Outcome outcome,
                                        Thresholds t)
    {
        Aggregates a = new Aggregates();
        a.windowDays = t.windowDays;

        for (CommitStats row : all)
        {
            if (row.importCommit)
            {
                a.importCommits++;
                continue;
            }
            a.countedCommits++;
            a.added += row.added;
            a.copied += row.copied;
            a.moved += row.moved;
            a.novel += row.novel;

            if (row.churnCensored)
            {
                a.censoredCommits++;
            }
            else
            {
                a.churnAdded += row.added;
                a.churnLines += row.churn;
                double pct = row.churnRatio() * 100;
                if (row.added >= 10 && pct > a.churnSpikePct)
                {
                    a.churnSpikePct = pct;
                    a.churnSpike = row;
                }
            }
        }

        a.copyPct = a.added == 0 ? 0 : 100.0 * a.copied / a.added;
        a.refactorPct = (a.moved + a.copied) == 0 ? 0 : 100.0 * a.moved / (a.moved + a.copied);
        a.churnPct = a.churnAdded == 0 ? 0 : 100.0 * a.churnLines / a.churnAdded;

        a.dupLines = outcome.headDupLines;
        a.dupClones = outcome.headDupClones;
        // Measured lines, not total: a mirror subtree is out of the numerator, so leaving it
        // in the denominator would halve the answer rather than correct it.
        a.dupMeasuredLoc = outcome.headDupMeasuredLines > 0
                ? outcome.headDupMeasuredLines : outcome.headLoc;
        a.dupPct = a.dupMeasuredLoc == 0 ? 0
                : 100.0 * outcome.headDupLines / a.dupMeasuredLoc;

        double kloc = outcome.headLoc / 1000.0;
        int handlers = outcome.headBare + outcome.headBroad + outcome.headSwallow
                + outcome.headSuppress;
        a.errDensity = kloc == 0 ? 0 : handlers / kloc;
        a.connDensity = kloc == 0 ? 0 : outcome.headCalls / kloc;

        CommitStats reference = windowReference(all, t.windowDays);
        if (reference != null && reference.loc > 0)
        {
            a.referenceDays = all.isEmpty() ? 0 : (int) Math.round(
                    (all.get(all.size() - 1).committedAt - reference.committedAt)
                            / (double) DAY_MS);
            a.dupLinesThen = reference.dupLines;
            a.dupDeltaLines = a.dupLines - reference.dupLines;
            // A percentage of zero is not 100%, it is undefined. The absolute change is still
            // reported, and it is the honest half of the pair: 0 -> 4,000 lines is +4,000.
            a.dupDeltaKnown = reference.dupLines > 0;
            a.dupDeltaPct = a.dupDeltaKnown
                    ? 100.0 * a.dupDeltaLines / reference.dupLines : 0;
            a.connThen = reference.calls / (reference.loc / 1000.0);
            a.connDeltaKnown = a.connThen > 0;
            a.connDeltaPct = a.connDeltaKnown
                    ? 100.0 * (a.connDensity - a.connThen) / a.connThen : 0;
        }
        else
        {
            a.dupLinesThen = -1;
            a.referenceDays = 0;
        }

        legacy(a, outcome, reference);
        duplicationVerdicts(a, outcome, t, reference);

        a.busFactor = busFactor(outcome.authors);
        a.identityCollision = outcome.rawIdentityCount > outcome.authors.size();

        a.stateCopy = a.added == 0 ? Thresholds.UNKNOWN
                : Thresholds.lowerIsBetter(a.copyPct, t.copyPasteWarn, t.copyPasteCrit);
        a.stateRefactor = (a.moved + a.copied) == 0 ? Thresholds.UNKNOWN
                : Thresholds.higherIsBetter(a.refactorPct, t.refactorWarn, t.refactorCrit);
        a.stateChurn = a.churnAdded == 0 ? Thresholds.UNKNOWN
                : Thresholds.lowerIsBetter(a.churnPct, t.churnWarn, t.churnCrit);
        if (!Thresholds.WORSENING.equals(a.dirDup))
        {
            a.stateDupDelta = Thresholds.UNKNOWN.equals(a.dirDup)
                    ? Thresholds.UNKNOWN : Thresholds.GOOD;
        }
        else if (a.dupDeltaKnown)
        {
            String byRatio =
                    Thresholds.lowerIsBetter(a.dupDeltaPct, t.dupDeltaWarn, t.dupDeltaCrit);
            // A percentage may raise the alarm only when the lines behind it are worth one.
            a.stateDupDelta = Thresholds.CRIT.equals(byRatio)
                    && Math.abs(a.dupDeltaLines) < t.dupDeltaCritLines
                    ? Thresholds.WARN : byRatio;
        }
        else
        {
            // Growing from a baseline of zero: worth flagging, but there is no ratio to say
            // how badly, so it does not get to be critical.
            a.stateDupDelta = Thresholds.WARN;
        }
        a.stateErr = Thresholds.lowerIsBetter(a.errDensity, t.errDensityWarn, t.errDensityCrit);
        // Graded on the delta, so no usable delta means no grade. It used to key off
        // dupLinesThen and score an undefined 0% as "good".
        a.stateConn = !a.connDeltaKnown ? Thresholds.UNKNOWN
                : Thresholds.higherIsBetter(a.connDeltaPct, t.connDeltaWarn, t.connDeltaCrit);
        return a;
    }

    /**
     * Level and direction, kept apart.
     *
     * <p>One badge cannot answer both "is this bad now" and "is this getting worse", and the
     * two really do disagree: in the pair that prompted this, one repository sat at 4.3% and was
     * cleaning up while the other sat at 1.3% and was filling up fast. Collapsing them hid
     * whichever axis lost.</p>
     */
    private static void duplicationVerdicts(Aggregates a, AnalysisEngine.Outcome outcome,
                                            Thresholds t, CommitStats reference)
    {
        a.dominantLanguage = outcome.dominantLanguage();
        Thresholds.LevelBand band = t.bandFor(a.dominantLanguage);
        a.dupLevelApplicable = band != null;
        a.dupBand = band;
        a.stateDupLevel = band != null
                ? Thresholds.lowerIsBetter(a.dupPct, band.warn, band.crit)
                : Thresholds.UNKNOWN;

        if (reference == null || a.dupLinesThen < 0)
        {
            a.dirDup = Thresholds.UNKNOWN;
        }
        else if (Math.abs(a.dupDeltaLines) < t.dupDeltaFloorLines)
        {
            // Below the floor a percentage is arithmetic on noise: +52% on a 50-line base is
            // 26 lines, which is four clones.
            a.dirDup = Thresholds.FLAT;
        }
        else
        {
            a.dirDup = a.dupDeltaLines > 0 ? Thresholds.WORSENING : Thresholds.IMPROVING;
        }
    }

    /** The metrics this plugin replaces, computed the way the old tools computed them. */
    private static void legacy(Aggregates a, AnalysisEngine.Outcome outcome,
                               CommitStats reference)
    {
        a.legacyComplexity = complexity(outcome.headDecisions, outcome.headFunctions);
        a.legacyCommentDensity = ratio(outcome.headCommentLines, outcome.headTotalLines) * 100;
        a.legacyFunctionLength = ratio(outcome.headFunctionLines, outcome.headFunctions);

        if (reference == null || reference.functions <= 0 || reference.totalLines <= 0)
        {
            return;
        }
        a.legacyDupDelta = change(ratio(reference.dupLines,
                reference.dupMeasuredLines > 0 ? reference.dupMeasuredLines : reference.loc)
                * 100, a.dupPct);
        a.legacyComplexityDelta = change(
                complexity(reference.decisions, reference.functions), a.legacyComplexity);
        a.legacyCommentDelta = change(
                ratio(reference.commentLines, reference.totalLines) * 100,
                a.legacyCommentDensity);
        a.legacyFunctionLengthDelta = change(
                ratio(reference.functionLines, reference.functions), a.legacyFunctionLength);
    }

    /** Cyclomatic complexity per function: one plus its decision points. */
    private static double complexity(int decisions, int functions)
    {
        return functions <= 0 ? 0 : (double) (decisions + functions) / functions;
    }

    private static double ratio(int numerator, int denominator)
    {
        return denominator <= 0 ? 0 : (double) numerator / denominator;
    }

    /** Null rather than zero when there is nothing to divide by - see the field comment. */
    private static Double change(double from, double to)
    {
        return from == 0 ? null : Double.valueOf(100.0 * (to - from) / from);
    }

    /** Oldest sampled commit still inside the trend window, or the oldest sampled one. */
    private static CommitStats windowReference(List<CommitStats> all, int windowDays)
    {
        if (all.isEmpty())
        {
            return null;
        }
        long cutoff = all.get(all.size() - 1).committedAt - windowDays * DAY_MS;
        CommitStats oldestSampled = null;
        for (CommitStats row : all)
        {
            if (!row.sampled())
            {
                continue;
            }
            if (oldestSampled == null)
            {
                oldestSampled = row;
            }
            if (row.committedAt >= cutoff)
            {
                return row;
            }
        }
        return oldestSampled;
    }

    /** Fewest people whose commits add up to at least half of the history. */
    static int busFactor(Map<String, Author> authors)
    {
        if (authors.isEmpty())
        {
            return 0;
        }
        List<Author> ordered = new ArrayList<Author>(authors.values());
        Collections.sort(ordered, new Comparator<Author>()
        {
            @Override
            public int compare(Author x, Author y)
            {
                return y.commits - x.commits;
            }
        });
        int total = 0;
        for (Author author : ordered)
        {
            total += author.commits;
        }
        int running = 0;
        int people = 0;
        for (Author author : ordered)
        {
            running += author.commits;
            people++;
            if (running * 2 >= total)
            {
                break;
            }
        }
        return people;
    }

    // ------------------------------------------------------------------ KPI cards

    private static List<Map<String, Object>> kpis(Aggregates a, Thresholds t,
                                                  List<CommitStats> all)
    {
        List<Map<String, Object>> kpis = new ArrayList<Map<String, Object>>();

        kpis.add(kpi("copyPaste", round(a.copyPct, 1), "percent", a.stateCopy,
                "lower", rollingCopy(all), null,
                pair("lines", a.copied), pair("added", a.added)));

        kpis.add(kpi("refactor", round(a.refactorPct, 1), "percent", a.stateRefactor,
                "higher", rollingRefactor(all), null,
                pair("moved", a.moved), pair("copied", a.copied)));

        kpis.add(kpi("churn", round(a.churnPct, 1), "percent", a.stateChurn,
                "lower", churnSpark(all), null,
                pair("lines", a.churnLines), pair("added", a.churnAdded),
                pair("censored", a.censoredCommits)));

        // The percentage is emitted only when it survives both guards the verdict already
        // applies: a baseline to divide by, and a change big enough not to be arithmetic on
        // noise. Otherwise the tile showed "+42.9%" in large type directly above a chip
        // reading "no change" - the floor stopped the verdict and not the number.
        Double dupDelta = a.dupDeltaKnown && !Thresholds.FLAT.equals(a.dirDup)
                && !Thresholds.UNKNOWN.equals(a.dirDup)
                ? round(a.dupDeltaPct, 1) : null;
        Map<String, Object> dup = kpi("duplication", a.dupLines, "lines", a.stateDupLevel,
                "lower", sampledSpark(all, "dupLines"), dupDelta,
                pair("pct", round(a.dupPct, 2)), pair("clones", a.dupClones),
                pair("then", a.dupLinesThen), pair("windowDays", a.referenceDays),
                pair("deltaLines", a.dupDeltaLines),
                pair("level", a.stateDupLevel),
                pair("direction", a.dirDup),
                pair("levelApplicable", a.dupLevelApplicable ? 1 : 0),
                pair("language", a.dominantLanguage),
                pair("basis", a.dupBand == null ? "" : a.dupBand.basis),
                pair("cohortSize", a.dupBand == null ? 0 : a.dupBand.cohortSize),
                pair("critCohortSize", a.dupBand == null ? 0 : a.dupBand.critCohortSize),
                pair("levelWarn", a.dupBand == null ? 0 : a.dupBand.warn),
                pair("levelCrit", a.dupBand == null ? 0 : a.dupBand.crit),
                pair("floorLines", t.dupDeltaFloorLines));
        kpis.add(dup);

        kpis.add(kpi("errorSwallow", round(a.errDensity, 2), "perKloc", a.stateErr,
                "lower", sampledSpark(all, "errDensity"), null));

        kpis.add(kpi("connectivity", round(a.connDensity, 1), "perKloc", a.stateConn,
                "higher", sampledSpark(all, "connDensity"),
                a.connDeltaKnown ? round(a.connDeltaPct, 1) : null,
                pair("then", round(a.connThen, 1)), pair("windowDays", a.referenceDays),
                pair("approximate", 1)));

        return kpis;
    }

    private static Map<String, Object> kpi(String key, Object value, String unit, String state,
                                           String direction, List<Double> spark, Double delta,
                                           Map.Entry<?, ?>... extras)
    {
        Map<String, Object> kpi = new LinkedHashMap<String, Object>();
        kpi.put("key", key);
        kpi.put("value", value);
        kpi.put("unit", unit);
        kpi.put("state", state);
        kpi.put("direction", direction);
        kpi.put("spark", spark);
        kpi.put("delta", delta);
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> extra : extras)
        {
            detail.put(String.valueOf(extra.getKey()), extra.getValue());
        }
        kpi.put("detail", detail);
        return kpi;
    }

    private static Map.Entry<String, Object> pair(String key, Object value)
    {
        return new java.util.AbstractMap.SimpleEntry<String, Object>(key, value);
    }

    /** Copy-paste share over a trailing window of commits, so the sparkline is not all spikes. */
    private static List<Double> rollingCopy(List<CommitStats> all)
    {
        return rolling(all, true);
    }

    private static List<Double> rollingRefactor(List<CommitStats> all)
    {
        return rolling(all, false);
    }

    private static List<Double> rolling(List<CommitStats> all, boolean copyShare)
    {
        List<Double> out = new ArrayList<Double>();
        int window = 10;
        List<CommitStats> counted = new ArrayList<CommitStats>();
        for (CommitStats row : all)
        {
            if (!row.importCommit)
            {
                counted.add(row);
            }
        }
        for (int i = 0; i < counted.size(); i++)
        {
            int from = Math.max(0, i - window + 1);
            int added = 0;
            int copied = 0;
            int moved = 0;
            for (int k = from; k <= i; k++)
            {
                added += counted.get(k).added;
                copied += counted.get(k).copied;
                moved += counted.get(k).moved;
            }
            if (copyShare)
            {
                out.add(added == 0 ? 0.0 : round(100.0 * copied / added, 2));
            }
            else
            {
                out.add((moved + copied) == 0 ? 0.0
                        : round(100.0 * moved / (moved + copied), 2));
            }
        }
        return out;
    }

    private static List<Double> churnSpark(List<CommitStats> all)
    {
        List<Double> out = new ArrayList<Double>();
        for (CommitStats row : all)
        {
            if (row.importCommit || row.churnCensored)
            {
                continue;
            }
            out.add(round(row.churnRatio() * 100, 2));
        }
        return out;
    }

    private static List<Double> sampledSpark(List<CommitStats> all, String metric)
    {
        List<Double> out = new ArrayList<Double>();
        for (CommitStats row : all)
        {
            if (!row.sampled())
            {
                continue;
            }
            double kloc = row.loc / 1000.0;
            if ("dupLines".equals(metric))
            {
                out.add((double) row.dupLines);
            }
            else if ("errDensity".equals(metric))
            {
                out.add(kloc == 0 ? 0.0 : round(row.errSwallow / kloc, 3));
            }
            else
            {
                out.add(kloc == 0 ? 0.0 : round(row.calls / kloc, 2));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ grade summary

    private static List<Map<String, Object>> grades(Aggregates a)
    {
        List<Map<String, Object>> grades = new ArrayList<Map<String, Object>>();

        // A combined badge takes the worse axis and says which one, so the reader is never
        // left guessing whether "act" means the level or the trend.
        String duplication = a.stateCopy;
        String axis = "copyPaste";
        if (Thresholds.rank(a.stateDupLevel) > Thresholds.rank(duplication))
        {
            duplication = a.stateDupLevel;
            axis = "level";
        }
        // Scaled by how much it grew, not by the fact that it grew: +21 lines and +30,000
        // lines used to earn the same badge, which made dupDeltaWarn/dupDeltaCrit dead
        // settings that an administrator could edit with no effect.
        String directionState = a.stateDupDelta;
        if (!Thresholds.UNKNOWN.equals(a.dirDup)
                && Thresholds.rank(directionState) > Thresholds.rank(duplication))
        {
            duplication = directionState;
            axis = "direction";
        }
        grades.add(grade("duplication", duplication, axis));

        grades.add(grade("maintainability", Thresholds.worst(a.stateErr, a.stateConn),
                Thresholds.rank(a.stateErr) >= Thresholds.rank(a.stateConn)
                        ? "errorSwallow" : "connectivity"));

        String bus = a.busFactor <= 1 ? Thresholds.CRIT
                : (a.busFactor == 2 ? Thresholds.WARN : Thresholds.GOOD);
        grades.add(grade("changeSafety", Thresholds.worst(a.stateChurn, bus),
                Thresholds.rank(bus) >= Thresholds.rank(a.stateChurn) ? "busFactor" : "churn"));
        return grades;
    }

    private static Map<String, Object> grade(String key, String state, String axis)
    {
        Map<String, Object> grade = new LinkedHashMap<String, Object>();
        grade.put("key", key);
        grade.put("state", state);
        grade.put("axis", axis);
        return grade;
    }

    /**
     * The legacy metrics, shown for comparison and deliberately ungraded.
     *
     * <p>All four divide by lines of code or by function count, which is the whole point: they
     * are on the page so a reader can watch them stay flat, or improve, while the metrics below
     * them move. Grading them would suggest they are worth acting on.</p>
     */
    private static List<Map<String, Object>> legacyBlock(Aggregates a)
    {
        List<Map<String, Object>> legacy = new ArrayList<Map<String, Object>>();
        legacy.add(legacyMetric("duplicates", round(a.dupPct, 2), "percent",
                round(a.legacyDupDelta, 1)));
        legacy.add(legacyMetric("complexity", round(a.legacyComplexity, 2), "perFunction",
                round(a.legacyComplexityDelta, 1)));
        legacy.add(legacyMetric("commentDensity", round(a.legacyCommentDensity, 1), "percent",
                round(a.legacyCommentDelta, 1)));
        legacy.add(legacyMetric("functionLength", round(a.legacyFunctionLength, 1), "lines",
                round(a.legacyFunctionLengthDelta, 1)));
        return legacy;
    }

    private static Map<String, Object> legacyMetric(String key, Object value, String unit,
                                                    Double delta)
    {
        Map<String, Object> metric = new LinkedHashMap<String, Object>();
        metric.put("key", key);
        metric.put("value", value);
        metric.put("unit", unit);
        metric.put("delta", delta);
        return metric;
    }

    // ------------------------------------------------------------------ series

    private static Map<String, Object> series(List<CommitStats> all)
    {
        List<Object> points = new ArrayList<Object>();
        for (int i = 0; i < all.size(); i++)
        {
            CommitStats row = all.get(i);
            Map<String, Object> point = new LinkedHashMap<String, Object>();
            point.put("i", i);
            point.put("sha", shortSha(row.sha));
            point.put("subject", row.subject);
            point.put("at", row.committedAt);
            point.put("author", row.authorName);
            point.put("imported", row.importCommit);
            point.put("novel", row.novel);
            point.put("copied", row.copied);
            point.put("moved", row.moved);
            point.put("added", row.added);
            point.put("deleted", row.deleted);
            point.put("churn", row.churn);
            point.put("churnPct", round(row.churnRatio() * 100, 2));
            point.put("censored", row.churnCensored);
            if (row.sampled())
            {
                point.put("loc", row.loc);
                point.put("files", row.files);
                point.put("dupLines", row.dupLines);
                // Same denominator as the headline ratio, or the trend line and the tile
                // would disagree on a repository with a mirror subtree.
                int dupBase = row.dupMeasuredLines > 0 ? row.dupMeasuredLines : row.loc;
                point.put("dupPct", dupBase == 0 ? 0
                        : round(100.0 * row.dupLines / dupBase, 3));
                point.put("dupClones", row.dupClones);
                point.put("errDensity", row.loc == 0 ? 0
                        : round(row.errSwallow / (row.loc / 1000.0), 3));
                point.put("connDensity", row.loc == 0 ? 0
                        : round(row.calls / (row.loc / 1000.0), 2));
            }
            points.add(point);
        }
        Map<String, Object> series = new LinkedHashMap<String, Object>();
        series.put("commits", points);
        return series;
    }

    private static List<Map<String, Object>> clones(List<DuplicateDetector.CloneHit> hits)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (DuplicateDetector.CloneHit hit : hits)
        {
            Map<String, Object> clone = new LinkedHashMap<String, Object>();
            clone.put("fileA", hit.fileA);
            clone.put("lineA", hit.lineA);
            clone.put("fileB", hit.fileB);
            clone.put("lineB", hit.lineB);
            clone.put("lines", hit.lines);
            out.add(clone);
            if (out.size() >= CLONE_TABLE_LIMIT)
            {
                break;
            }
        }
        return out;
    }

    private static List<Map<String, Object>> authors(AnalysisEngine.Outcome outcome)
    {
        List<Author> ordered = new ArrayList<Author>(outcome.authors.values());
        Collections.sort(ordered, new Comparator<Author>()
        {
            @Override
            public int compare(Author x, Author y)
            {
                return y.commits != x.commits ? y.commits - x.commits
                        : x.key.compareTo(y.key);
            }
        });
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Author author : ordered)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("name", author.name);
            row.put("commits", author.commits);
            row.put("added", author.addedLines);
            row.put("identities", new ArrayList<String>(author.identities));
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------ findings

    private static List<Map<String, Object>> findings(Aggregates a,
                                                      AnalysisEngine.Outcome outcome,
                                                      List<CommitStats> all, Thresholds t)
    {
        List<Map<String, Object>> findings = new ArrayList<Map<String, Object>>();

        DuplicateDetector.CloneHit crossFile = null;
        int crossFileCount = 0;
        for (DuplicateDetector.CloneHit hit : outcome.headClones)
        {
            if (!hit.fileA.equals(hit.fileB))
            {
                crossFileCount++;
                if (crossFile == null)
                {
                    crossFile = hit;
                }
            }
        }
        if (crossFile != null)
        {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("fileA", crossFile.fileA);
            params.put("lineA", crossFile.lineA);
            params.put("fileB", crossFile.fileB);
            params.put("lineB", crossFile.lineB);
            params.put("lines", crossFile.lines);
            params.put("count", crossFileCount);
            findings.add(finding(crossFileCount >= 3 ? Thresholds.CRIT : Thresholds.WARN,
                    "crossFileClone", params,
                    crossFile.fileA + ":" + crossFile.lineA + " <-> "
                            + crossFile.fileB + ":" + crossFile.lineB));
        }

        String hottest = null;
        int hottestLines = 0;
        for (Map.Entry<String, Integer> entry : outcome.headDupByFile.entrySet())
        {
            if (entry.getValue() > hottestLines
                    || (entry.getValue() == hottestLines
                        && (hottest == null || entry.getKey().compareTo(hottest) < 0)))
            {
                hottestLines = entry.getValue();
                hottest = entry.getKey();
            }
        }
        if (hottest != null && outcome.headDupLines > 0)
        {
            double share = 100.0 * hottestLines / outcome.headDupLines;
            if (share >= 40)
            {
                Map<String, Object> params = new LinkedHashMap<String, Object>();
                params.put("file", hottest);
                params.put("lines", hottestLines);
                params.put("share", round(share, 0));
                params.put("total", outcome.headDupLines);
                findings.add(finding(share >= 60 ? Thresholds.WARN : Thresholds.GOOD,
                        "cloneConcentration", params, hottest));
            }
        }

        Map<String, Object> errParams = new LinkedHashMap<String, Object>();
        errParams.put("bare", outcome.headBare);
        errParams.put("broad", outcome.headBroad);
        errParams.put("swallow", outcome.headSwallow);
        errParams.put("suppress", outcome.headSuppress);
        errParams.put("density", round(a.errDensity, 2));
        findings.add(finding(a.stateErr, "errorHandling", errParams, ""));

        if (!Thresholds.UNKNOWN.equals(a.stateConn))
        {
            Map<String, Object> connParams = new LinkedHashMap<String, Object>();
            connParams.put("from", round(a.connThen, 1));
            connParams.put("to", round(a.connDensity, 1));
            connParams.put("delta", round(a.connDeltaPct, 1));
            connParams.put("windowDays", t.windowDays);
            findings.add(finding(a.stateConn, "connectivityDrift", connParams, ""));
        }

        Author top = null;
        for (Author author : outcome.authors.values())
        {
            if (top == null || author.commits > top.commits)
            {
                top = author;
            }
        }
        Map<String, Object> busParams = new LinkedHashMap<String, Object>();
        busParams.put("busFactor", a.busFactor);
        busParams.put("authors", outcome.authors.size());
        busParams.put("identities", outcome.rawIdentityCount);
        busParams.put("topName", top == null ? "" : top.name);
        busParams.put("topCommits", top == null ? 0 : top.commits);
        busParams.put("collision", a.identityCollision);
        findings.add(finding(a.busFactor <= 1 ? Thresholds.CRIT
                        : (a.identityCollision ? Thresholds.WARN : Thresholds.GOOD),
                a.identityCollision ? "busFactor" : "busFactorClean", busParams, ""));

        if (!outcome.identitySuspects.isEmpty())
        {
            String[] first = outcome.identitySuspects.get(0);
            StringBuilder evidence = new StringBuilder();
            for (String[] pair : outcome.identitySuspects)
            {
                if (evidence.length() > 0)
                {
                    evidence.append(" \u00b7 ");
                }
                evidence.append(pair[0]).append(" <-> ").append(pair[1]);
            }
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("nameA", first[0]);
            params.put("nameB", first[1]);
            params.put("pairs", outcome.identitySuspects.size());
            findings.add(finding(Thresholds.WARN, "identitySuspects", params,
                    evidence.toString()));
        }

        if (a.churnSpike != null && a.churnSpikePct >= t.churnWarn
                && a.churnSpikePct >= a.churnPct * 2)
        {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("sha", shortSha(a.churnSpike.sha));
            params.put("fullSha", a.churnSpike.sha);
            params.put("subject", a.churnSpike.subject);
            params.put("pct", round(a.churnSpikePct, 1));
            params.put("added", a.churnSpike.added);
            params.put("churn", a.churnSpike.churn);
            params.put("at", a.churnSpike.committedAt);
            params.put("average", round(a.churnPct, 1));
            findings.add(finding(a.churnSpikePct >= t.churnCrit ? Thresholds.CRIT
                    : Thresholds.WARN, "churnSpike", params, shortSha(a.churnSpike.sha)));
        }

        if (!Thresholds.GOOD.equals(a.stateCopy) && !Thresholds.UNKNOWN.equals(a.stateCopy))
        {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("pct", round(a.copyPct, 1));
            params.put("lines", a.copied);
            params.put("added", a.added);
            params.put("refactorPct", round(a.refactorPct, 1));
            findings.add(finding(a.stateCopy, "copyPasteHigh", params, ""));
        }

        // Severity first, then how actionable the finding is. The tie-break matters because
        // only five make the page: a duplicated block someone can go and fix outranks a 3%
        // drift in an approximated metric, even though both read as "watch".
        Collections.sort(findings, new Comparator<Map<String, Object>>()
        {
            @Override
            public int compare(Map<String, Object> x, Map<String, Object> y)
            {
                int bySeverity = Thresholds.rank(String.valueOf(y.get("severity")))
                        - Thresholds.rank(String.valueOf(x.get("severity")));
                if (bySeverity != 0)
                {
                    return bySeverity;
                }
                return priority(String.valueOf(x.get("code")))
                        - priority(String.valueOf(y.get("code")));
            }
        });
        return findings.size() > 5 ? new ArrayList<Map<String, Object>>(findings.subList(0, 5))
                : findings;
    }

    private static final List<String> FINDING_PRIORITY = java.util.Arrays.asList(
            "crossFileClone", "cloneConcentration", "busFactor", "identitySuspects",
            "churnSpike", "copyPasteHigh", "errorHandling", "connectivityDrift");

    private static int priority(String code)
    {
        int at = FINDING_PRIORITY.indexOf(code);
        return at < 0 ? FINDING_PRIORITY.size() : at;
    }

    private static Map<String, Object> finding(String severity, String code,
                                               Map<String, Object> params, String evidence)
    {
        Map<String, Object> finding = new LinkedHashMap<String, Object>();
        finding.put("severity", severity);
        finding.put("code", code);
        finding.put("params", params);
        finding.put("evidence", evidence);
        return finding;
    }

    // ------------------------------------------------------------------ caveats

    private static List<Map<String, Object>> caveats(Aggregates a, List<CommitStats> all,
                                                     AnalysisEngine.Outcome outcome)
    {
        List<Map<String, Object>> caveats = new ArrayList<Map<String, Object>>();

        // First, because it changes the duplication denominator - a reader who meets it after
        // the numbers has already read them as covering the whole repository.
        if (!outcome.headMirrors.isEmpty())
        {
            int droppedLines = 0;
            StringBuilder names = new StringBuilder();
            for (MirrorTrees.Mirror mirror : outcome.headMirrors)
            {
                droppedLines += mirror.droppedLines;
                if (names.length() > 0)
                {
                    names.append(", ");
                }
                names.append(mirror.mirror).append(" (= ").append(mirror.original).append(')');
            }
            Map<String, Object> mirrors = new LinkedHashMap<String, Object>();
            mirrors.put("trees", outcome.headMirrors.size());
            mirrors.put("lines", droppedLines);
            mirrors.put("names", names.toString());
            mirrors.put("measured", outcome.headDupMeasuredLines);
            caveats.add(caveat("mirrorTrees", mirrors));
        }

        if (outcome.historyTruncated)
        {
            // Also first: it says the facts row describes a window, not the repository.
            Map<String, Object> truncated = new LinkedHashMap<String, Object>();
            truncated.put("commits", AnalysisConfig.MAX_COMMITS);
            truncated.put("oldest", all.isEmpty() ? 0 : all.get(0).committedAt);
            caveats.add(caveat("historyTruncated", truncated));
        }

        Map<String, Object> censoring = new LinkedHashMap<String, Object>();
        censoring.put("commits", a.censoredCommits);
        censoring.put("days", 14);
        caveats.add(caveat("rightCensoring", censoring));

        int importedLines = 0;
        for (CommitStats row : all)
        {
            if (row.importCommit)
            {
                importedLines += row.added;
            }
        }
        Map<String, Object> imports = new LinkedHashMap<String, Object>();
        imports.put("commits", a.importCommits);
        imports.put("lines", importedLines);
        caveats.add(caveat("importExcluded", imports));

        Map<String, Object> sample = new LinkedHashMap<String, Object>();
        sample.put("commits", all.size());
        sample.put("days", all.isEmpty() ? 0
                : Math.max(1, (all.get(all.size() - 1).committedAt - all.get(0).committedAt)
                    / DAY_MS));
        sample.put("authors", outcome.authors.size());
        caveats.add(caveat("sampleSize", sample));

        caveats.add(caveat("approximation", new LinkedHashMap<String, Object>()));
        caveats.add(caveat("aiAttribution", new LinkedHashMap<String, Object>()));
        caveats.add(caveat("firstParent", new LinkedHashMap<String, Object>()));
        return caveats;
    }

    private static Map<String, Object> caveat(String code, Map<String, Object> params)
    {
        Map<String, Object> caveat = new LinkedHashMap<String, Object>();
        caveat.put("code", code);
        caveat.put("params", params);
        return caveat;
    }

    // ------------------------------------------------------------------ helpers

    static String shortSha(String sha)
    {
        return sha == null ? "" : (sha.length() > 8 ? sha.substring(0, 8) : sha);
    }

    /** Keeps null as null: a delta with no baseline must not become 0.0 on the way out. */
    static Double round(Double value, int decimals)
    {
        return value == null ? null : Double.valueOf(round(value.doubleValue(), decimals));
    }

    static double round(double value, int decimals)
    {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
