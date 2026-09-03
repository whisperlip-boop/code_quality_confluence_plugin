import co.bskim.confluence.codequality.service.Messages;
import co.bskim.confluence.codequality.service.ReportLocalizer;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Renders the report page to a file outside Confluence, so the layout can actually be looked
 * at. Mirrors what ReportServlet writes; keep the two in step.
 *
 *   javac -cp target/code-quality-1.0.0.jar -d /tmp/cq-probe tools/RenderProbe.java
 *   java -cp target/code-quality-1.0.0.jar:/tmp/cq-probe RenderProbe \
 *        /tmp/cq-report.json src/main/resources /tmp/report.html
 */
public final class RenderProbe
{
    public static void main(String[] args) throws Exception
    {
        String json = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        File resources = new File(args[1]);
        String output = args[2];

        String defaultLanguage = Messages.LANGUAGES[0];
        String localized = ReportLocalizer.localize(json);

        StringBuilder languages = new StringBuilder("[");
        for (int i = 0; i < Messages.LANGUAGES.length; i++)
        {
            languages.append(i > 0 ? ",\"" : "\"").append(Messages.LANGUAGES[i]).append('"');
        }
        languages.append(']');

        PrintWriter out = new PrintWriter(output, "UTF-8");
        out.print("<!DOCTYPE html><html lang=\"" + defaultLanguage + "\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + Messages.get(defaultLanguage, "cq.report.title")
                + "</title><style>");
        out.print(read(new File(resources, "report/report.css")));
        out.print("</style></head><body><div id=\"cq-report\"></div><script>window.__CQ_REPORT=");
        out.print(escape(localized));
        out.print(";window.__CQ_EMPTY=");
        out.print(escape(ReportLocalizer.emptyState()));
        out.print(";window.__CQ_LANGS=");
        out.print(languages);
        out.print(";</script><script>");
        out.print(read(new File(resources, "report/report.js")));
        out.print("</script></body></html>");
        out.close();
        System.out.println("wrote " + output);
    }

    private static String escape(String json)
    {
        return json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
    }

    private static String read(File file) throws Exception
    {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
