package co.bskim.confluence.codequality.macro;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
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

    @Inject
    public CodeQualityMacro(@ComponentImport PageBuilderService pageBuilderService)
    {
        this.pageBuilderService = pageBuilderService;
    }

    @Override
    public String execute(Map<String, String> parameters, String body, ConversionContext context)
            throws MacroExecutionException
    {
        pageBuilderService.assembler().resources().requireContext("code-quality");

        String only = parameters.get("repository");
        String title = parameters.get("title");
        return "<div class=\"cq-app\""
                + " data-only=\"" + escape(only) + "\""
                + " data-title=\"" + escape(title) + "\">"
                + "<div class=\"cq-loading\">Loading repositories...</div>"
                + "</div>";
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

    private static String escape(String value)
    {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("\"", "&quot;").replace("<", "&lt;");
    }
}
