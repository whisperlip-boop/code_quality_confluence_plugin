package co.bskim.confluence.codequality.web;

import org.junit.Before;
import org.junit.Test;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What the macro's {@code repository} parameter accepts.
 *
 * <p>It used to be compared to the stored name exactly, and the stored names come in two
 * shapes: entered by hand, from when the registration form still had a Name field, and
 * {@code owner/repo} derived from the URL ever since. So a reader of one table saw
 * {@code captureV} on one row and {@code whisperlip-boop/dept_calendar} on the next with no way
 * to know which form the parameter wanted, and pasting the clone URL - the obvious guess -
 * matched nothing.</p>
 *
 * <p>Runs the shipping <b>file</b> through Nashorn rather than a Java copy of the same rules,
 * because a copy is the thing that drifts. Two small stubs stand in for the browser: the
 * matcher itself touches neither, and the file needs them only to finish loading.</p>
 */
public class RepoMatchTest
{
    /** Enough of a browser for the file to load. The matcher uses none of it. */
    private static final String BROWSER_STUB =
            "var window = { location: { pathname: '/' } };"
            + "var document = { readyState: 'complete', documentElement: {},"
            + "  createElement: function () { return { style: {} }; },"
            + "  querySelectorAll: function () { return []; } };";

    private ScriptEngine engine;

    @Before
    public void setUp() throws Exception
    {
        engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull("no JavaScript engine in this JDK, so this test cannot run", engine);
        engine.eval(BROWSER_STUB);
        engine.eval(source("src/main/resources/js/code-quality.js"));
        assertEquals("the file must export its matcher", "object",
                engine.eval("typeof window.CqRepoMatch"));

        engine.eval("var repo = { id: 7, name: 'captureV',"
                + " url: 'https://github.com/whisperlip-boop/captureV.git' };");
    }

    /** Everything a person could reasonably type for the reference repository. */
    @Test
    public void acceptsEveryFormOfTheSameRepository() throws Exception
    {
        assertMatches("7", "the id");
        assertMatches("captureV", "the stored name");
        assertMatches("whisperlip-boop/captureV", "owner and repository");
        assertMatches("captureV.git", "the repository with the git suffix");
        assertMatches("https://github.com/whisperlip-boop/captureV.git", "the clone URL");
        assertMatches("https://github.com/whisperlip-boop/captureV", "the browse URL");
        assertMatches("git@github.com:whisperlip-boop/captureV.git", "the scp-like remote");
        assertMatches("  CAPTUREV  ", "case and padding");
    }

    /**
     * A partial name must not match.
     *
     * <p>Not an oversight: {@code api} would answer for {@code acme/api} and
     * {@code acme-fork/api} both, and quietly rendering two rows is not what "show only this
     * repository" asked for. Better to show the no-match message, which lists the names that
     * would have worked.</p>
     */
    @Test
    public void refusesAPartialName() throws Exception
    {
        assertDoesNotMatch("capture", "a prefix of the name");
        assertDoesNotMatch("V", "a suffix of the name");
        assertDoesNotMatch("whisperlip-boop", "the owner alone");
        assertDoesNotMatch("github.com", "the host alone");
    }

    /** Nor may it match a different repository, however similar. */
    @Test
    public void refusesADifferentRepository() throws Exception
    {
        assertDoesNotMatch("dept_calendar", "another repository");
        assertDoesNotMatch("whisperlip-boop/captureV2", "a name that starts the same");
        assertDoesNotMatch("someone-else/captureV", "the same repository under another owner");
        assertDoesNotMatch("8", "another id");
    }

    /**
     * An empty selection selects nothing.
     *
     * <p>It used to mean "all of them". With a picker in the dialog, nothing ticked plainly
     * means nothing ticked - and the old reading made the preview show a full list before any
     * choice had been made, which is what prompted the change. The administration screen keeps
     * listing everything; that is decided by {@code data-context}, not here.</p>
     */
    @Test
    public void anEmptySelectionSelectsNothing() throws Exception
    {
        assertNotSelected("", "an empty selection");
        assertNotSelected("   ", "whitespace only");
        assertNotSelected(",,", "separators with nothing between them");
    }

    /** The parameter holds a list, because the dialog's picker takes several. */
    @Test
    public void aListSelectsEveryRepositoryInIt() throws Exception
    {
        assertSelected("captureV", "one entry");
        assertSelected("dept_calendar,captureV", "second of two");
        assertSelected("captureV,dept_calendar", "first of two");
        assertSelected(" dept_calendar , captureV ", "padding around the separators");
        assertSelected("11,7", "a list of ids");
        assertNotSelected("dept_calendar,field_template_jira_server", "a list without it");
    }

    /**
     * A repository stored under {@code owner/repo} answers to its bare name too.
     *
     * <p>This is the row the reporter was looking at when they asked what to type.</p>
     */
    @Test
    public void aStoredOwnerRepoNameAlsoAnswersToItsBareName() throws Exception
    {
        engine.eval("repo = { id: 11, name: 'whisperlip-boop/dept_calendar',"
                + " url: 'https://github.com/whisperlip-boop/dept_calendar.git' };");

        assertMatches("whisperlip-boop/dept_calendar", "the stored name");
        assertMatches("dept_calendar", "the bare repository name");
        assertMatches("https://github.com/whisperlip-boop/dept_calendar.git", "the clone URL");
        assertMatches("11", "the id");
        assertDoesNotMatch("captureV", "a different repository");
    }

    /** A repository with no URL - not expected, but it must not throw. */
    @Test
    public void survivesAMissingUrl() throws Exception
    {
        engine.eval("repo = { id: 3, name: 'orphan', url: '' };");

        assertMatches("orphan", "the stored name");
        assertMatches("3", "the id");
        assertDoesNotMatch("captureV", "a different repository");
    }

    // ------------------------------------------------------------------ helpers

    private void assertMatches(String typed, String what) throws ScriptException
    {
        assertTrue(what + " (" + typed + ") must identify the repository", identifies(typed));
    }

    private void assertDoesNotMatch(String typed, String what) throws ScriptException
    {
        assertFalse(what + " (" + typed + ") must not identify the repository",
                identifies(typed));
    }

    private void assertSelected(String parameter, String what) throws ScriptException
    {
        assertTrue(what + " (" + parameter + ") must select the repository",
                selected(parameter));
    }

    private void assertNotSelected(String parameter, String what) throws ScriptException
    {
        assertFalse(what + " (" + parameter + ") must not select the repository",
                selected(parameter));
    }

    private boolean identifies(String typed) throws ScriptException
    {
        engine.put("typed", typed);
        return Boolean.TRUE.equals(engine.eval("window.CqRepoMatch.identifies(repo, typed)"));
    }

    private boolean selected(String parameter) throws ScriptException
    {
        engine.put("parameter", parameter);
        return Boolean.TRUE.equals(
                engine.eval("window.CqRepoMatch.matchesSelection(repo, parameter)"));
    }

    private static String source(String path) throws Exception
    {
        File file = new File(path);
        assertTrue("cannot find " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
