package co.bskim.confluence.codequality.servlet;

import co.bskim.confluence.codequality.web.AccessGuard;
import co.bskim.confluence.codequality.web.StaticAssets;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.message.I18nResolver;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Repository management outside a page, for administrators who would rather not create a
 * Confluence page just to register a remote. Mounts the same table component as the macro.
 *
 * <p>The response is a fragment, not a whole document: it asks for Confluence's
 * administration decorator so the screen keeps the console's left-hand menu instead of
 * dropping the reader onto a bare page with no way back into the section.
 */
@Named
public class AdminServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;

    private final AccessGuard access;
    private final I18nResolver i18n;

    @Inject
    public AdminServlet(AccessGuard access, @ComponentImport I18nResolver i18n)
    {
        this.access = access;
        this.i18n = i18n;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        if (!access.isLoggedIn())
        {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Login required");
            return;
        }
        if (!access.isAdmin())
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Confluence administrator required");
            return;
        }

        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");

        PrintWriter out = response.getWriter();
        // No doctype and no <html lang>: Confluence's sitemesh wraps this fragment in the
        // administration console shell, which supplies both. The decorator meta tag is what
        // buys the shell - without it the servlet answers with a bare page and the console's
        // left-hand menu disappears the moment someone opens this screen.
        out.print("<html><head><title>");
        out.print(StaticAssets.escape(i18n.getText("cq.admin.title")));
        out.print("</title>");
        out.print("<meta name=\"decorator\" content=\"atl.admin\">");
        out.print("<style>");
        out.print(StaticAssets.read("/report/standalone.css"));
        out.print(StaticAssets.read("/css/code-quality.css"));
        // The heading is not printed here: the decorator already draws <title> at the top
        // of the admin screen, so a heading of our own would show the same words twice.
        out.print("</style></head><body>");
        // How the left-hand menu knows which entry to highlight. Confluence compares this
        // against the web-item key, not the link id or the URL, and it has to be a sitemesh
        // <content> tag in the body - the "admin.active.tab" meta tag that does this job in
        // Jira is read by nothing here.
        out.print("<content tag=\"selectedWebItem\">code-quality-admin-link</content>");
        out.print("<div class=\"cq-standalone\">");
        out.print("<p class=\"cq-standalone-note\">");
        out.print(StaticAssets.escape(i18n.getText("cq.admin.note")));
        out.print("</p><div class=\"cq-app\" data-context=\"admin\""
                + " data-only=\"\" data-title=\"\">");
        out.print("<div class=\"cq-loading\">...</div></div></div><script>");
        // The matcher first: code-quality.js reads it through the window object.
        out.print(StaticAssets.read("/js/repo-match.js"));
        out.print(StaticAssets.read("/js/code-quality.js"));
        out.print("</script></body></html>");
    }
}
