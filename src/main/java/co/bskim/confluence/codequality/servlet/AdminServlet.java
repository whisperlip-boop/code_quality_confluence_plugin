package co.bskim.confluence.codequality.servlet;

import co.bskim.confluence.codequality.web.AccessGuard;
import co.bskim.confluence.codequality.web.StaticAssets;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
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
 */
@Named
public class AdminServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;

    private final AccessGuard access;
    private final I18nResolver i18n;
    private final ApplicationProperties applicationProperties;

    @Inject
    public AdminServlet(AccessGuard access, @ComponentImport I18nResolver i18n,
                        @ComponentImport ApplicationProperties applicationProperties)
    {
        this.access = access;
        this.i18n = i18n;
        this.applicationProperties = applicationProperties;
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
        out.print("<!DOCTYPE html><html lang=\"");
        out.print(StaticAssets.escape(i18n.getText("cq.report.lang")));
        out.print("\"><head><meta charset=\"utf-8\">");
        out.print("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        out.print("<title>");
        out.print(StaticAssets.escape(i18n.getText("cq.admin.title")));
        out.print("</title><style>");
        out.print(StaticAssets.read("/report/standalone.css"));
        out.print(StaticAssets.read("/css/code-quality.css"));
        out.print("</style></head><body><div class=\"cq-standalone\"><h1>");
        out.print(StaticAssets.escape(i18n.getText("cq.admin.title")));
        out.print("</h1><p class=\"cq-standalone-note\">");
        out.print(StaticAssets.escape(i18n.getText("cq.admin.note")));
        out.print("</p><div class=\"cq-app\" data-only=\"\" data-title=\"\">");
        out.print("<div class=\"cq-loading\">...</div></div><p class=\"cq-standalone-foot\">");
        out.print("<a href=\"");
        out.print(StaticAssets.escape(applicationProperties.getBaseUrl(
                com.atlassian.sal.api.UrlMode.RELATIVE)));
        out.print("/\">");
        out.print(StaticAssets.escape(i18n.getText("cq.admin.back")));
        out.print("</a></p></div><script>");
        out.print(StaticAssets.read("/js/code-quality.js"));
        out.print("</script></body></html>");
    }
}
