package co.bskim.confluence.codequality.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Every finding the builder can emit has to come out as a sentence, in every language.
 *
 * <p>The wording lives in three places that have to stay in step - the code the builder emits,
 * the argument list keyed off that code, and the message in each bundle - and nothing connects
 * them at compile time. Adding a finding without its argument list published
 * "bus factor {0}, across {1} author(s)" to the page, and nothing failed: the report built, the
 * page rendered, the sentence was simply wrong. Anything that only shows up by looking at the
 * screen will eventually not be looked at.</p>
 */
public class ReportLocalizerTest
{
    /** Every code {@code ReportBuilder.findings} can produce. */
    private static final List<String> FINDING_CODES = Arrays.asList(
            "crossFileClone", "cloneConcentration", "errorHandling", "connectivityDrift",
            "busFactor", "busFactorClean", "churnSpike", "identitySuspects", "copyPasteHigh");

    private static final List<String> CAVEAT_CODES = Arrays.asList(
            "rightCensoring", "importExcluded", "sampleSize", "approximation", "aiAttribution",
            "firstParent");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+\\}");

    @Test
    public void everyFindingIsWordedInEveryLanguage()
    {
        JsonObject worded = localizeAll();
        for (String language : Messages.LANGUAGES)
        {
            JsonArray findings =
                    worded.getAsJsonObject(language).getAsJsonArray("findings");
            assertEquals(language, FINDING_CODES.size(), findings.size());
            for (int i = 0; i < findings.size(); i++)
            {
                JsonObject entry = findings.get(i).getAsJsonObject();
                String where = language + " / " + FINDING_CODES.get(i);
                checkSentence(where + " title", entry.get("title").getAsString());
                checkSentence(where + " body", entry.get("body").getAsString());
            }
        }
    }

    @Test
    public void everyCaveatIsWordedInEveryLanguage()
    {
        JsonObject worded = localizeAll();
        for (String language : Messages.LANGUAGES)
        {
            JsonArray caveats = worded.getAsJsonObject(language).getAsJsonArray("caveats");
            assertEquals(language, CAVEAT_CODES.size(), caveats.size());
            for (int i = 0; i < caveats.size(); i++)
            {
                JsonObject entry = caveats.get(i).getAsJsonObject();
                String where = language + " / " + CAVEAT_CODES.get(i);
                checkSentence(where + " title", entry.get("title").getAsString());
                checkSentence(where + " body", entry.get("body").getAsString());
            }
        }
    }

    /**
     * A code with no argument list must fail the build rather than reach a reader.
     *
     * <p>This is the behaviour that would have caught the bug above, so it is worth a test of
     * its own: silence was the whole problem.</p>
     */
    @Test
    public void anUnknownFindingCodeIsRefused()
    {
        JsonObject report = new JsonObject();
        JsonArray findings = new JsonArray();
        findings.add(entry("somethingAddedLaterAndForgotten"));
        report.add("findings", findings);
        report.add("caveats", new JsonArray());
        try
        {
            ReportLocalizer.localize(report.toString());
            fail("an unworded finding code must not reach the page");
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage(),
                    e.getMessage().contains("somethingAddedLaterAndForgotten"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void checkSentence(String where, String text)
    {
        assertFalse(where + " is empty", text.trim().isEmpty());
        assertFalse(where + " is a bare message key: " + text, text.startsWith("cq."));
        Matcher placeholder = PLACEHOLDER.matcher(text);
        assertFalse(where + " still holds an unfilled placeholder: " + text,
                placeholder.find());
    }

    private static JsonObject localizeAll()
    {
        JsonObject report = new JsonObject();
        JsonArray findings = new JsonArray();
        for (String code : FINDING_CODES)
        {
            findings.add(entry(code));
        }
        JsonArray caveats = new JsonArray();
        for (String code : CAVEAT_CODES)
        {
            caveats.add(entry(code));
        }
        report.add("findings", findings);
        report.add("caveats", caveats);

        JsonObject localized =
                new JsonParser().parse(ReportLocalizer.localize(report.toString()))
                        .getAsJsonObject();
        return localized.getAsJsonObject("i18n");
    }

    /**
     * One entry carrying every parameter any code reads. The union rather than the exact set
     * per code: a missing parameter renders as 0 or an empty string, which would hide the very
     * thing this is checking.
     */
    private static JsonObject entry(String code)
    {
        JsonObject params = new JsonObject();
        params.addProperty("fileA", "capture/canvas_view.py");
        params.addProperty("lineA", 107);
        params.addProperty("fileB", "capture/region_overlay.py");
        params.addProperty("lineB", 126);
        params.addProperty("lines", 12);
        params.addProperty("count", 3);
        params.addProperty("file", "capture/canvas_view.py");
        params.addProperty("share", 74);
        params.addProperty("total", 56);
        params.addProperty("bare", 2);
        params.addProperty("broad", 5);
        params.addProperty("swallow", 1);
        params.addProperty("suppress", 0);
        params.addProperty("density", 0.71);
        params.addProperty("from", 827.6);
        params.addProperty("to", 802.2);
        params.addProperty("delta", -3.1);
        params.addProperty("windowDays", 19);
        params.addProperty("busFactor", 1);
        params.addProperty("authors", 2);
        params.addProperty("identities", 3);
        params.addProperty("topName", "whisperlip-boop");
        params.addProperty("topCommits", 24);
        params.addProperty("sha", "ae41336f");
        params.addProperty("subject", "Add arrow-key nudge");
        params.addProperty("pct", 40.9);
        params.addProperty("churn", 9);
        params.addProperty("added", 22);
        params.addProperty("average", 2.3);
        params.addProperty("nameA", "whisperlip-boop");
        params.addProperty("nameB", "whisperlip");
        params.addProperty("pairs", 1);
        params.addProperty("refactorPct", 19.7);
        params.addProperty("commits", 27);
        params.addProperty("days", 14);

        JsonObject entry = new JsonObject();
        entry.addProperty("code", code);
        entry.addProperty("severity", "warn");
        entry.add("params", params);
        entry.addProperty("evidence", "");
        return entry;
    }

    private static void assertEquals(String message, int expected, int actual)
    {
        org.junit.Assert.assertEquals(message, expected, actual);
    }
}
