package co.bskim.confluence.codequality.macro;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.message.I18nResolver;
import com.atlassian.webresource.api.assembler.PageBuilderService;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Map;

/**
 * Mounts the repository table on a Confluence page.
 *
 * <p>The repository list is global, so every macro shows the same registrations - which is the
 * point: one clone and one analysis per repository, however many pages reference it.</p>
 */
@Named
public class CodeQualityMacro implements Macro
{
    private final PageBuilderService pageBuilderService;
    private final I18nResolver i18n;

    @Inject
    public CodeQualityMacro(@ComponentImport PageBuilderService pageBuilderService,
                            @ComponentImport I18nResolver i18n)
    {
        this.pageBuilderService = pageBuilderService;
        this.i18n = i18n;
    }

    @Override
    public String execute(Map<String, String> parameters, String body, ConversionContext context)
            throws MacroExecutionException
    {
        pageBuilderService.assembler().resources().requireContext("code-quality");

        String only = parameters.get("repository");
        String title = parameters.get("title");
        // data-context tells the script which surface it is on. On a page an empty selection
        // means nothing was picked and nothing should be shown; on the administration screen
        // it means the whole list, which is the point of that screen.
        return "<div class=\"cq-app\" data-context=\"macro\""
                + " data-only=\"" + escape(only) + "\""
                + " data-title=\"" + escape(title) + "\">"
                // Resolved here rather than written in English: this is what the reader sees
                // if the script never runs, and it is the one string on the page the script
                // cannot come back and translate.
                + "<div class=\"cq-loading\">" + escape(i18n.getText("cq.ui.loading"))
                + "</div></div>";
    }

    @Override
    public BodyType getBodyType()
    {
        return BodyType.NONE;
    }

    @Override
    public OutputType getOutputType()
    {
        return OutputType.BLOCK;
    }

    /** All five, like {@code StaticAssets.escape}. This was the only partial one left. */
    private static String escape(String value)
    {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
