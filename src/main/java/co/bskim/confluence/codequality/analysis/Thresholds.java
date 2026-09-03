package co.bskim.confluence.codequality.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

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
     * Absolute duplication ratio bands, per dominant language.
     *
     * <p>Level and direction are separate questions. A repository can sit high and be cleaning
     * up, or sit low and be filling up fast; one badge covering both hides whichever it did not
     * pick.</p>
     *
     * <p>Each band is the 75th and 90th percentile of a measured cohort of public repositories
     * run through this same detector with the same exclusions - see
     * {@code tools/cohort-*.tsv}. A language with no cohort gets no level grade at all: the
     * distribution differs per language, and borrowing another language's number would be a
     * guess wearing a measurement's clothes. Getting the Python cohort usable needed the
     * exclusions right first - with tests and generated tables left in, black measured 22%
     * (its {@code profiling/} data dumps) and rich 20% (one Unicode table per version), and the
     * percentiles were meaningless.</p>
     *
     * <p>Replace all of this with your own repositories' distribution once you have five or
     * more per language.</p>
     */
    public Map<String, LevelBand> dupRatioByLanguage = defaultDupBands();

    /** One language's warn/act band and where it came from. */
    public static final class LevelBand
    {
        public double warn;
        public double crit;
        /** Repositories behind the warn band: this language's cohort. */
        public int cohortSize;
        /** Repositories behind the act band, which is pooled across languages - see below. */
        public int critCohortSize;
        public String basis = "";

        public LevelBand()
        {
        }

        LevelBand(double warn, double crit, int cohortSize, int critCohortSize, String basis)
        {
            this.warn = warn;
            this.crit = crit;
            this.cohortSize = cohortSize;
            this.critCohortSize = critCohortSize;
            this.basis = basis;
        }
    }

    /**
     * Where the duplication bands come from, measured 2026-09-03.
     *
     * <p>Warn is this language's own 75th percentile. Act is the <b>pooled</b> 90th percentile
     * across all three cohorts, and that is a deliberate choice rather than a shortcut: a
     * per-language p90 moves by up to 2.9 points when any single repository is dropped from a
     * cohort of thirty-odd, while the pooled one moves by 0.26. Publishing an "act now" line
     * that one repository can shift by three points would be exactly the guessing this band
     * exists to replace. Pooling is defensible because the three distributions sit on top of
     * each other - medians 2.96 / 2.37 / 2.62 - so the tail is measuring the detector and the
     * kind of code people write, not the language.</p>
     *
     * <table>
     *   <tr><th>language</th><th>n</th><th>p25</th><th>median</th><th>p75</th><th>p90</th>
     *       <th>p90 leave-one-out</th></tr>
     *   <tr><td>Java</td><td>41</td><td>1.62</td><td>2.96</td><td>6.04</td><td>11.01</td>
     *       <td>0.49</td></tr>
     *   <tr><td>JS/TS</td><td>33</td><td>1.31</td><td>2.37</td><td>4.53</td><td>13.05</td>
     *       <td>2.87</td></tr>
     *   <tr><td>Python</td><td>38</td><td>1.59</td><td>2.62</td><td>5.46</td><td>11.69</td>
     *       <td>2.39</td></tr>
     *   <tr><td>pooled</td><td>112</td><td></td><td>2.56</td><td>5.63</td><td>11.27</td>
     *       <td>0.26</td></tr>
     * </table>
     *
     * <p>Raw measurements are in {@code tools/cohort-*-2026-09-03.tsv}, with every excluded
     * repository and the reason. Regenerate with {@code tools/clone-cohort.sh},
     * {@code tools/CohortProbe.java} and {@code tools/cohort-stats.py}.</p>
     */
    private static Map<String, LevelBand> defaultDupBands()
    {
        Map<String, LevelBand> bands = new LinkedHashMap<String, LevelBand>();
        bands.put("JAVA", new LevelBand(6.0, POOLED_CRIT, 41, POOLED_N, BASIS));
        bands.put("JAVASCRIPT", new LevelBand(4.5, POOLED_CRIT, 33, POOLED_N, BASIS));
        bands.put("PYTHON", new LevelBand(5.5, POOLED_CRIT, 38, POOLED_N, BASIS));
        return bands;
    }

    private static final double POOLED_CRIT = 11.3;
    private static final int POOLED_N = 112;
    private static final String BASIS = "cohort-2026-09-03";

    /** The band for a dominant language, or null when that language has no cohort. */
    public LevelBand bandFor(String language)
    {
        if (dupRatioByLanguage == null || language == null || language.isEmpty())
        {
            return null;
        }
        LevelBand band = dupRatioByLanguage.get(language);
        return band != null && band.warn > 0 && band.crit > 0 ? band : null;
    }

    /**
     * Below this many lines of absolute change, no direction is claimed.
     *
     * <p>Not a taste call: a percentage over a small base explodes. captureV's duplication grew
     * 52% - which is 26 lines. Derived from the detector's own minimum clone size, so a change
     * smaller than about four clones is treated as noise rather than a trend.</p>
     */
    public int dupDeltaFloorLines = AnalysisConfig.DUP_MIN_LINES * 4;
    /**
     * How many lines a growth has to be worth before a percentage may call it critical.
     *
     * <p>The floor above stops a small base from inventing a direction; this stops it from
     * inventing a severity. A repository holding 50 duplicated lines that reaches 76 is up
     * 52%, which crosses any percentage band worth setting - and it is 26 lines. The direction
     * is real and still reported; "act now" is not.</p>
     */
    public int dupDeltaCritLines = AnalysisConfig.DUP_MIN_LINES * 20;

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
