package co.bskim.confluence.codequality.servlet;

import co.bskim.confluence.codequality.model.RepoSnapshot;
import co.bskim.confluence.codequality.service.Messages;
import co.bskim.confluence.codequality.service.ReportLocalizer;
import co.bskim.confluence.codequality.service.RepositoryService;
import co.bskim.confluence.codequality.web.AccessGuard;
import co.bskim.confluence.codequality.web.StaticAssets;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * The report page.
 *
 * <p>Served undecorated and self-contained rather than inside Confluence chrome: it is a
 * full-bleed dashboard, it must render identically on an instance with no outbound network
 * access, and it is meant to be opened in its own tab and left there.</p>
 *
 * <p>Every language is embedded in the one page. The switcher then costs no request, and the
 * page keeps working after someone saves or prints it.</p>
 */
@Named
public class ReportServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;

    private final RepositoryService repositories;
    private final AccessGuard access;

    @Inject
    public ReportServlet(RepositoryService repositories, AccessGuard access)
    {
        this.repositories = repositories;
        this.access = access;
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

        int repoId = parseInt(request.getParameter("repo"));
        RepoSnapshot repo = repoId > 0 ? repositories.byId(repoId) : null;
        if (repo == null)
        {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Repository not found");
            return;
        }
        String stored = repositories.reportJson(repoId);

        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Cache-Control", "no-store");

        String defaultLanguage = Messages.LANGUAGES[0];
        String payload = stored == null ? "null" : ReportLocalizer.localize(stored);

        PrintWriter out = response.getWriter();
        out.print("<!DOCTYPE html><html lang=\"");
        out.print(StaticAssets.escape(defaultLanguage));
        out.print("\"><head><meta charset=\"utf-8\">");
        out.print("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        out.print("<title>");
        out.print(StaticAssets.escape(repo.name));
        out.print(" - ");
        out.print(StaticAssets.escape(Messages.get(defaultLanguage, "cq.report.title")));
        out.print("</title><style>");
        out.print(StaticAssets.read("/report/report.css"));
        out.print("</style></head><body><div id=\"cq-report\"></div>");
        out.print("<script>window.__CQ_REPORT=");
        out.print(StaticAssets.forScript(payload));
        out.print(";window.__CQ_EMPTY=");
        out.print(StaticAssets.forScript(ReportLocalizer.emptyState()));
        out.print(";window.__CQ_LANGS=");
        out.print(StaticAssets.forScript(languagesJson()));
        out.print(";</script><script>");
        out.print(StaticAssets.read("/report/report.js"));
        out.print("</script></body></html>");
    }

    private static String languagesJson()
    {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < Messages.LANGUAGES.length; i++)
        {
            if (i > 0)
            {
                json.append(',');
            }
            json.append('"').append(Messages.LANGUAGES[i]).append('"');
        }
        return json.append(']').toString();
    }

    private static int parseInt(String value)
    {
        try
        {
            return value == null ? 0 : Integer.parseInt(value.trim());
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }
}
