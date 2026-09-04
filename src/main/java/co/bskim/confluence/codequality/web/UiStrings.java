package co.bskim.confluence.codequality.web;

import com.atlassian.sal.api.message.I18nResolver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Labels for the repository table, resolved server-side and sent with the list response.
 *
 * <p>The table renders in two places - inside a page macro and on a standalone admin page -
 * and only one of those has Confluence's client-side i18n available, so both get their strings
 * the same way.</p>
 */
public final class UiStrings
{
    private static final String[] KEYS = {
            "ui.repositories", "ui.header.name", "ui.header.url", "ui.header.lastSync",
            "ui.header.status", "ui.header.actions", "ui.new", "ui.edit", "ui.delete",
            "ui.analyze", "ui.report", "ui.cancel", "ui.save", "ui.test", "ui.never",
            "ui.empty", "ui.emptyAdmin", "ui.noneSelected", "ui.pickInDialog",
            "ui.pickNone", "ui.selectedCount", "ui.filterPlaceholder", "ui.loading",
            "ui.keptUnseen",
            "ui.previewNote", "ui.filterNoMatch",
            "ui.filterAvailable",
            "ui.confirmDelete", "ui.noReport", "ui.adminOnly", "ui.failedAskAdmin",
            "ui.probeOk", "ui.probePublic", "ui.probePublicToken", "ui.probeAuthed",
            "ui.probeFail", "ui.probing", "ui.probeNetwork", "ui.urlCredentialsMoved",
            "ui.probeError.notAuthorized", "ui.probeError.notFound",
            "ui.probeError.notGitRepository", "ui.probeError.timeout",
            "ui.probeError.unreachable", "ui.probeError.tokenUnreadable",
            "ui.urlError.scheme", "ui.urlError.localHost", "ui.urlError.malformed",
            "ui.urlError.host", "ui.urlError.control", "ui.urlError.tooLong",
            "ui.urlError.empty", "ui.deterministic", "ui.error",
            "ui.form.required", "ui.form.urlRequired",
            "ui.form.title.new", "ui.form.title.edit", "ui.form.name", "ui.form.url",
            "ui.form.nameHint", "ui.form.urlHint", "ui.form.branch", "ui.form.branchHint", "ui.form.authUser",
            "ui.form.authUserHint", "ui.form.token", "ui.form.tokenHint", "ui.form.tokenKeep",
            "ui.form.spaces", "ui.form.spacesHint", "ui.form.spacesLoading",
            "ui.form.spacesNone", "ui.form.spacesEmptyConfirm", "ui.spacesAdminOnly",
            "ui.urlError.unknownSpaces",
            "ui.form.excludes", "ui.form.excludesHint", "ui.form.advanced",
            "ui.status.NEW", "ui.status.QUEUED", "ui.status.RUNNING", "ui.status.OK",
            "ui.status.FAILED",
            "ui.phase.queued", "ui.phase.fetch", "ui.phase.materialise", "ui.phase.commits",
            "ui.phase.head", "ui.phase.report", "ui.phase.store", "ui.phase.done"
    };

    private UiStrings()
    {
    }

    public static Map<String, String> resolve(I18nResolver i18n)
    {
        Map<String, String> strings = new LinkedHashMap<String, String>();
        for (String key : KEYS)
        {
            strings.put(key, i18n.getText("cq." + key));
        }
        return strings;
    }
}
