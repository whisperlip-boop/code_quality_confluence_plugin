package co.bskim.confluence.codequality.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

/**
 * Words a stored report in every supported language at once.
 *
 * <p>Reports are stored as codes plus numbers, never as prose, so one analysis can be read in
 * Korean or English without recomputing anything. All languages are embedded in the page
 * rather than fetched per switch: the switcher is then instant, it still works on a page that
 * was saved or printed, and the reader does not lose their scroll position or any open details
 * section on every toggle.</p>
 */
public final class ReportLocalizer
{
    private static final String[] LABEL_KEYS = {
            "report.title", "report.subtitle", "report.verdict", "report.analysedAt",
            "report.head", "report.branch", "report.back", "report.deterministic",
            "report.cached", "report.algo",
            "fact.commits", "fact.span", "fact.loc", "fact.files", "fact.authors",
            "fact.identities", "fact.days",
            "section.kpi", "section.duplication", "section.mix", "section.churn",
            "section.findings", "section.clones", "section.authors", "section.caveats",
            "section.legacy", "legacy.note",
            "legacy.duplicates", "legacy.duplicates.miss",
            "legacy.complexity", "legacy.complexity.miss",
            "legacy.commentDensity", "legacy.commentDensity.miss",
            "legacy.functionLength", "legacy.functionLength.miss",
            "unit.perFunction",
            "dir.improving", "dir.worsening", "dir.flat", "dir.unknown",
            "label.level", "label.direction", "label.noBasis", "label.levelBasis",
            "label.noBasisNote", "label.floorNote",
            "axis.copyPaste", "axis.level", "axis.direction", "axis.errorSwallow",
            "axis.connectivity", "axis.busFactor", "axis.churn",
            "grade.duplication", "grade.maintainability", "grade.changeSafety",
            "state.good", "state.warn", "state.crit", "state.unknown",
            "kpi.copyPaste", "kpi.refactor", "kpi.churn", "kpi.duplication",
            "kpi.errorSwallow", "kpi.connectivity",
            "kpi.copyPaste.note", "kpi.refactor.note", "kpi.churn.note",
            "kpi.duplication.note", "kpi.errorSwallow.note", "kpi.connectivity.note",
            "unit.percent", "unit.lines", "unit.perKloc",
            "chart.dupAbsolute", "chart.dupRatio", "chart.novel", "chart.copied",
            "chart.moved", "chart.churnPct", "chart.censored", "chart.commitAxis",
            "clones.header.a", "clones.header.b", "clones.header.lines", "clones.empty",
            "authors.header.name", "authors.header.commits", "authors.header.added",
            "authors.header.identities",
            "label.approximate", "label.censoredNote", "label.delta", "label.window",
            "label.showAll", "label.showLess", "label.noData", "label.importExcluded",
            "label.tableView", "label.clones", "label.clonesShown", "label.why",
            "label.hideWhy", "label.noBaseline", "label.dupShare", "label.churnCensored",
            "label.bucketPartial",
            "label.findings", "label.language", "lang.ko", "lang.en"
    };

    private ReportLocalizer()
    {
    }

    /** Adds an {@code i18n} block holding every language's strings, findings and caveats. */
    public static String localize(String reportJson)
    {
        JsonObject report = new JsonParser().parse(reportJson).getAsJsonObject();

        JsonArray languages = new JsonArray();
        JsonObject bundles = new JsonObject();
        for (String language : Messages.LANGUAGES)
        {
            languages.add(new JsonPrimitive(language));
            bundles.add(language, bundle(report, language));
        }
        report.add("langs", languages);
        report.add("i18n", bundles);
        return report.toString();
    }

    /** The empty-report message in every language, for a repository with no run yet. */
    public static String emptyState()
    {
        JsonObject bundles = new JsonObject();
        for (String language : Messages.LANGUAGES)
        {
            JsonObject bundle = new JsonObject();
            bundle.addProperty("title", Messages.get(language, "cq.report.empty.title"));
            bundle.addProperty("body", Messages.get(language, "cq.report.empty.body"));
            bundle.addProperty("label", Messages.get(language, "cq.lang." + language));
            bundles.add(language, bundle);
        }
        return bundles.toString();
    }

    private static JsonObject bundle(JsonObject report, String language)
    {
        JsonObject strings = new JsonObject();
        for (String key : LABEL_KEYS)
        {
            strings.addProperty(key, Messages.get(language, "cq." + key));
        }

        JsonObject bundle = new JsonObject();
        bundle.add("strings", strings);
        bundle.add("findings", worded(report, "findings", language, true));
        bundle.add("caveats", worded(report, "caveats", language, false));
        return bundle;
    }

    /**
     * Titles and bodies for one array, indexed to match it. Kept parallel to the array rather
     * than merged into its entries so every number still lives in exactly one place.
     */
    private static JsonArray worded(JsonObject report, String field, String language,
                                    boolean isFinding)
    {
        JsonArray out = new JsonArray();
        if (!report.has(field))
        {
            return out;
        }
        for (JsonElement element : report.getAsJsonArray(field))
        {
            JsonObject entry = element.getAsJsonObject();
            String code = entry.get("code").getAsString();
            JsonObject params = entry.getAsJsonObject("params");
            String prefix = isFinding ? "cq.finding." : "cq.caveat.";
            Object[] args = isFinding
                    ? findingArgs(code, params, language)
                    : caveatArgs(code, params, language);

            JsonObject worded = new JsonObject();
            worded.addProperty("title",
                    Messages.format(language, prefix + code + ".title", args));
            worded.addProperty("body",
                    Messages.format(language, prefix + code + ".body", args));
            out.add(worded);
        }
        return out;
    }

    /**
     * Argument order per finding code. Kept next to the message keys it feeds so a reordered
     * placeholder in one language cannot silently swap two numbers.
     */
    private static Object[] findingArgs(String code, JsonObject p, String language)
    {
        switch (code)
        {
            case "crossFileClone":
                return new Object[] {
                        base(str(p, "fileA")), num(p, "lineA", language),
                        base(str(p, "fileB")), num(p, "lineB", language),
                        num(p, "lines", language), num(p, "count", language)
                };
            case "cloneConcentration":
                return new Object[] {
                        base(str(p, "file")), num(p, "lines", language),
                        num(p, "share", language), num(p, "total", language)
                };
            case "errorHandling":
                return new Object[] {
                        num(p, "bare", language), num(p, "broad", language),
                        num(p, "swallow", language), num(p, "suppress", language),
                        num(p, "density", language)
                };
            case "connectivityDrift":
                return new Object[] {
                        num(p, "from", language), num(p, "to", language),
                        num(p, "delta", language), num(p, "windowDays", language)
                };
            case "busFactor":
            case "busFactorClean":
                // Same arguments, two messages: the merged-identity sentence is only true when
                // merging actually happened, so the code carries which one applies.
                return new Object[] {
                        num(p, "busFactor", language), num(p, "authors", language),
                        num(p, "identities", language), str(p, "topName"),
                        num(p, "topCommits", language)
                };
            case "churnSpike":
                return new Object[] {
                        str(p, "sha"), str(p, "subject"), num(p, "pct", language),
                        num(p, "churn", language), num(p, "added", language),
                        num(p, "average", language)
                };
            case "identitySuspects":
                return new Object[] {
                        str(p, "nameA"), str(p, "nameB"), num(p, "pairs", language)
                };
            case "copyPasteHigh":
                return new Object[] {
                        num(p, "pct", language), num(p, "lines", language),
                        num(p, "added", language), num(p, "refactorPct", language)
                };
            default:
                // A code with no case here reaches the reader as a raw "{0}". Making that a
                // failure rather than a shrug is the only way it gets noticed before shipping:
                // it is caught by ReportLocalizerTest, which walks every code the builder can
                // emit.
                throw new IllegalStateException("No argument list for finding code: " + code);
        }
    }

    private static Object[] caveatArgs(String code, JsonObject p, String language)
    {
        switch (code)
        {
            case "mirrorTrees":
                return new Object[] {
                        num(p, "trees", language), num(p, "lines", language),
                        str(p, "names"), num(p, "measured", language)
                };
            case "historyTruncated":
                return new Object[] { num(p, "commits", language) };
            case "rightCensoring":
                return new Object[] { num(p, "commits", language), num(p, "days", language) };
            case "importExcluded":
                return new Object[] { num(p, "commits", language), num(p, "lines", language) };
            case "sampleSize":
                return new Object[] {
                        num(p, "commits", language), num(p, "days", language),
                        num(p, "authors", language)
                };
            default:
                return new Object[0];
        }
    }

    private static String str(JsonObject object, String key)
    {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    /**
     * Pre-formatted as a string rather than passed to MessageFormat as a number: handed a raw
     * number it would apply its own grouping and rounding, and these values are already rounded
     * to the precision the metric actually supports.
     */
    private static String num(JsonObject object, String key, String language)
    {
        JsonElement element = object == null ? null : object.get(key);
        if (element == null || element.isJsonNull())
        {
            return "0";
        }
        double value = element.getAsDouble();
        DecimalFormat format = value == Math.rint(value)
                ? new DecimalFormat("#,##0") : new DecimalFormat("#,##0.0##");
        format.setDecimalFormatSymbols(
                new DecimalFormatSymbols(Messages.localeOf(language)));
        return format.format(value);
    }

    /** File name only: full paths make a finding headline unreadable. */
    private static String base(String path)
    {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
