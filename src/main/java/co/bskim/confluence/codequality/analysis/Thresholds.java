package co.bskim.confluence.codequality.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Where each metric stops being fine.
 *
 * <p>These defaults are weak evidence and the UI says so. They were back-solved from the
 * GitClear industry figures and a single reference repository, which is enough to make a
 * dashboard readable and not enough to argue with. Run five to ten of your own repositories
 * through this, look at the actual distribution, and override per repository - a metric with
 * no threshold is just a number nobody looks at.</p>
 */
public final class Thresholds
{
    /** Lower is better: copy-paste share of added lines, percent. */
    public double copyPasteWarn = 8;
    public double copyPasteCrit = 15;

    /** Higher is better: moved / (moved + copied), percent. */
    public double refactorWarn = 15;
    public double refactorCrit = 8;

    /** Lower is better: share of added lines rewritten within 14 days, percent. */
    public double churnWarn = 10;
    public double churnCrit = 20;

    /** Lower is better: growth of absolute duplicated lines over the window, percent. */
    public double dupDeltaWarn = 5;
    public double dupDeltaCrit = 15;

    /**
     * Absolute duplication ratio, percent. Negative means "no basis" - and then the report
     * shows no level grade rather than inventing one.
     *
     * <p>Level and direction are separate questions. A repository can sit high and be cleaning
     * up, or sit low and be filling up fast; one badge covering both hides whichever it did not
     * pick.</p>
     *
     * <p>The defaults are the 75th and 90th percentiles of a measured cohort: 19 public Python
     * repositories (requests, flask, django-adjacent libraries, sqlalchemy, pydantic, fastapi
     * and so on) run through this same detector with the same exclusions on 2026-09-02.
     * Distribution: p25 1.2%, p50 2.7%, p75 5.3%, p90 8.7%. Getting there needed the
     * exclusions to be right first - with tests and generated tables left in, black measured
     * 22% (its {@code profiling/} data dumps) and rich 20% (one Unicode table per version),
     * and the percentiles were meaningless.</p>
     *
     * <p>They apply only to a repository whose dominant language is
     * {@link #dupRatioLanguage}: the distribution differs per language, and a Python-derived
     * number applied to Java would be a guess wearing a measurement's clothes. Other languages
     * get no level grade until their own cohort is measured. Replace all of this with your own
     * repositories' distribution once you have five or more.</p>
     */
    public double dupRatioWarn = 5.3;
    public double dupRatioCrit = 8.7;
    public String dupRatioLanguage = "PYTHON";
    public String dupRatioBasis = "python-cohort-2026-09-02-n19-p75-p90";

    /**
     * Below this many lines of absolute change, no direction is claimed.
     *
     * <p>Not a taste call: a percentage over a small base explodes. captureV's duplication grew
     * 52% - which is 26 lines. Derived from the detector's own minimum clone size, so a change
     * smaller than about four clones is treated as noise rather than a trend.</p>
     */
    public int dupDeltaFloorLines = AnalysisConfig.DUP_MIN_LINES * 4;

    /** Lower is better: error-swallowing handlers per KLOC. */
    public double errDensityWarn = 1.0;
    public double errDensityCrit = 3.0;

    /** Higher is better: change in calls per KLOC over the window, percent. */
    public double connDeltaWarn = -2;
    public double connDeltaCrit = -8;

    /** Trend window for the delta metrics, in days. */
    public int windowDays = 90;

    /** Direction verdicts, deliberately separate from the level states. */
    public static final String IMPROVING = "improving";
    public static final String WORSENING = "worsening";
    public static final String FLAT = "flat";

    public static final String GOOD = "good";
    public static final String WARN = "warn";
    public static final String CRIT = "crit";
    public static final String UNKNOWN = "unknown";

    public static Thresholds parse(String json)
    {
        Thresholds defaults = new Thresholds();
        if (json == null || json.trim().isEmpty())
        {
            return defaults;
        }
        try
        {
            JsonObject object = new JsonParser().parse(json).getAsJsonObject();
            Thresholds parsed = new Gson().fromJson(object, Thresholds.class);
            return parsed == null ? defaults : parsed;
        }
        catch (RuntimeException e)
        {
            return defaults;
        }
    }

    public String toJson()
    {
        return new Gson().toJson(this);
    }

    public static String lowerIsBetter(double value, double warn, double crit)
    {
        if (value >= crit)
        {
            return CRIT;
        }
        return value >= warn ? WARN : GOOD;
    }

    public static String higherIsBetter(double value, double warn, double crit)
    {
        if (value <= crit)
        {
            return CRIT;
        }
        return value <= warn ? WARN : GOOD;
    }

    public static int rank(String state)
    {
        if (CRIT.equals(state))
        {
            return 3;
        }
        if (WARN.equals(state))
        {
            return 2;
        }
        return GOOD.equals(state) ? 1 : 0;
    }

    public static String worst(String a, String b)
    {
        return rank(a) >= rank(b) ? a : b;
    }
}
