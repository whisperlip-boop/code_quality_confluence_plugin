package co.bskim.confluence.codequality.rest;

import co.bskim.confluence.codequality.git.GitClient;
import co.bskim.confluence.codequality.git.RemoteUrl;
import co.bskim.confluence.codequality.git.RepoAuth;
import co.bskim.confluence.codequality.model.RepoSnapshot;
import co.bskim.confluence.codequality.service.AnalysisJobManager;
import co.bskim.confluence.codequality.service.RepositoryService;
import co.bskim.confluence.codequality.service.TokenCipher;
import co.bskim.confluence.codequality.web.AccessGuard;
import co.bskim.confluence.codequality.web.UiStrings;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.message.I18nResolver;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** REST surface behind the repository table and the analyse/report actions. */
@Named
@Path("/repos")
@Produces(MediaType.APPLICATION_JSON)
public class RepoResource
{
    private final RepositoryService repositories;
    private final AnalysisJobManager jobs;
    private final GitClient gitClient;
    private final AccessGuard access;
    private final I18nResolver i18n;

    @Inject
    public RepoResource(RepositoryService repositories, AnalysisJobManager jobs,
                        GitClient gitClient, AccessGuard access,
                        @ComponentImport I18nResolver i18n)
    {
        this.repositories = repositories;
        this.jobs = jobs;
        this.gitClient = gitClient;
        this.access = access;
        this.i18n = i18n;
    }

    @GET
    public Response list()
    {
        if (!access.isLoggedIn())
        {
            return error(Response.Status.UNAUTHORIZED, "Login required");
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        // One query for the whole column rather than one per row - see repoIdsWithReports.
        Set<Integer> withReports = repositories.repoIdsWithReports();
        for (RepoSnapshot repo : repositories.all())
        {
            // Space-scoped: a row the caller cannot view is not listed at all, so probing
            // sequential ids reveals nothing either.
            if (access.canView(repo))
            {
                rows.add(toDto(repo, withReports.contains(repo.id)));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("repos", rows);
        payload.put("canManage", access.isAdmin());
        payload.put("strings", UiStrings.resolve(i18n));
        return Response.ok(new Gson().toJson(payload)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(String body)
    {
        if (!access.isAdmin())
        {
            return error(Response.Status.FORBIDDEN, "Confluence administrator required");
        }
        JsonObject input = parse(body);
        RemoteUrl remote;
        try
        {
            remote = RemoteUrl.parse(string(input, "url"));
        }
        catch (RemoteUrl.InvalidRemoteException e)
        {
            return invalidUrl(e);
        }
        String url = remote.url;
        // A token pasted into the URL is treated as a typed token: stored encrypted, and gone
        // from the URL that gets saved and handed back.
        String token = string(input, "token");
        if (token.isEmpty() && remote.carriedCredentials())
        {
            token = remote.embeddedSecret;
        }
        String authUser = string(input, "authUser");
        if (authUser.isEmpty() && !remote.embeddedUser.isEmpty())
        {
            authUser = remote.embeddedUser;
        }
        List<String> spaces = RepoSnapshot.splitKeys(string(input, "spaceKeys"));
        List<String> unknown = access.unknownSpaceKeys(spaces);
        if (!unknown.isEmpty())
        {
            return unknownSpaces(unknown);
        }
        String name = string(input, "name");
        RepoSnapshot repo = repositories.create(
                name.isEmpty() ? deriveName(url) : name,
                url,
                string(input, "branch"),
                authUser,
                token,
                RepoSnapshot.joinKeys(spaces),
                string(input, "excludes"),
                string(input, "thresholds"),
                access.currentUserName());
        return Response.ok(new Gson().toJson(toDto(repo))).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") int id, String body)
    {
        if (!access.isAdmin())
        {
            return error(Response.Status.FORBIDDEN, "Confluence administrator required");
        }
        JsonObject input = parse(body);
        RemoteUrl remote;
        try
        {
            remote = RemoteUrl.parse(string(input, "url"));
        }
        catch (RemoteUrl.InvalidRemoteException e)
        {
            return invalidUrl(e);
        }
        // A missing token field means "keep what is stored"; an empty one means "remove it".
        String token = input.has("token") ? string(input, "token") : null;
        if (remote.carriedCredentials() && (token == null || token.isEmpty()))
        {
            token = remote.embeddedSecret;
        }
        String authUser = string(input, "authUser");
        if (authUser.isEmpty() && !remote.embeddedUser.isEmpty())
        {
            authUser = remote.embeddedUser;
        }
        List<String> spaces = RepoSnapshot.splitKeys(string(input, "spaceKeys"));
        List<String> unknown = access.unknownSpaceKeys(spaces);
        if (!unknown.isEmpty())
        {
            return unknownSpaces(unknown);
        }
        RepoSnapshot repo = repositories.update(id, string(input, "name"), remote.url,
                string(input, "branch"), authUser, token, RepoSnapshot.joinKeys(spaces),
                string(input, "excludes"), string(input, "thresholds"));
        if (repo == null)
        {
            return error(Response.Status.NOT_FOUND, "Repository not found");
        }
        return Response.ok(new Gson().toJson(toDto(repo))).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") int id)
    {
        if (!access.isAdmin())
        {
            return error(Response.Status.FORBIDDEN, "Confluence administrator required");
        }
        jobs.cancel(id);
        jobs.forget(id);
        repositories.delete(id);
        gitClient.discard(id);
        return Response.ok("{\"deleted\":true}").build();
    }

    @POST
    @Path("/{id}/analyze")
    public Response analyze(@PathParam("id") int id)
    {
        if (!access.isAdmin())
        {
            return error(Response.Status.FORBIDDEN, "Confluence administrator required");
        }
        if (repositories.byId(id) == null)
        {
            return error(Response.Status.NOT_FOUND, "Repository not found");
        }
        boolean started = jobs.submit(id);
        return Response.ok("{\"queued\":" + started + "}").build();
    }

    @GET
    @Path("/{id}/progress")
    public Response progress(@PathParam("id") int id)
    {
        if (!access.isLoggedIn())
        {
            return error(Response.Status.UNAUTHORIZED, "Login required");
        }
        RepoSnapshot repo = repositories.byId(id);
        // Same answer for "no such repository" and "not yours", so ids cannot be enumerated.
        if (repo == null || !access.canView(repo))
        {
            return error(Response.Status.NOT_FOUND, "Repository not found");
        }
        AnalysisJobManager.JobState state = jobs.state(id);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", state != null ? state.status : repo.status);
        payload.put("phase", state != null ? state.phase : "");
        payload.put("current", state != null ? state.current : 0);
        payload.put("total", state != null ? state.total : 0);
        payload.put("message", failureDetail(state != null && !state.message.isEmpty()
                ? state.message : repo.statusMessage));
        payload.put("lastSyncedAt", repo.lastSyncedAt);
        payload.put("hasReport", repositories.hasReport(id));
        return Response.ok(new Gson().toJson(payload)).build();
    }

    /** Spaces the caller can view, so the form offers a picker instead of typed keys. */
    @GET
    @Path("/spaces")
    public Response spaces()
    {
        if (!access.isAdmin())
        {
            return error(Response.Status.FORBIDDEN, "Confluence administrator required");
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, String> space : access.viewableSpaces().entrySet())
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("key", space.getKey());
            row.put("name", space.getValue());
            rows.add(row);
        }
        return Response.ok(new Gson().toJson(rows)).build();
    }

    private Response unknownSpaces(List<String> unknown)
    {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("error", "Unknown space keys: " + RepoSnapshot.joinKeys(unknown));
        payload.put("reason", "unknownSpaces");
        payload.put("keys", unknown);
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new Gson().toJson(payload)).type(MediaType.APPLICATION_JSON).build();
    }

    /** Checks a URL and its credentials before the repository is saved. */
    @POST
    @Path("/probe")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response probe(String body)
    {
        if (!access.isAdmin())
        {
            return error(Response.Status.FORBIDDEN, "Confluence administrator required");
        }
        JsonObject input = parse(body);
        RemoteUrl remote;
        try
        {
            remote = RemoteUrl.parse(string(input, "url"));
        }
        catch (RemoteUrl.InvalidRemoteException e)
        {
            return invalidUrl(e);
        }
        String url = remote.url;
        String token = string(input, "token");
        if (token.isEmpty() && remote.carriedCredentials())
        {
            token = remote.embeddedSecret;
        }
        int id = integer(input, "id");
        // Editing an existing repository without retyping the token - but only for the remote
        // that token belongs to. The URL here is whatever the caller sent, independent of the
        // id, so reusing the stored token against a different host would hand somebody's access
        // token to that host. That is the one thing toDto promises never happens.
        RepoSnapshot stored = id > 0 ? repositories.byId(id) : null;
        if (token.isEmpty() && stored != null && RemoteUrl.sameRemote(url, stored.url))
        {
            try
            {
                token = repositories.authFor(stored).token;
            }
            catch (TokenCipher.TokenUnreadableException e)
            {
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("ok", false);
                payload.put("branches", 0);
                payload.put("anonymous", false);
                payload.put("tokenVerified", false);
                payload.put("tokenOffered", false);
                payload.put("urlHadCredentials", false);
                payload.put("error", "tokenUnreadable");
                return Response.ok(new Gson().toJson(payload)).build();
            }
        }
        String probeUser = string(input, "authUser");
        if (probeUser.isEmpty() && !remote.embeddedUser.isEmpty())
        {
            probeUser = remote.embeddedUser;
        }
        GitClient.ProbeResult probe = gitClient.probe(url, new RepoAuth(probeUser, token));

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("ok", probe.reachable);
        payload.put("branches", probe.branches);
        payload.put("anonymous", probe.anonymous);
        payload.put("tokenVerified", probe.tokenVerified);
        // Whether a token was offered at all, so the form can say it went unchecked.
        payload.put("tokenOffered", !token.isEmpty());
        payload.put("urlHadCredentials", remote.carriedCredentials());
        payload.put("error", probe.error);
        return Response.ok(new Gson().toJson(payload)).build();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A failure message, for the people who are allowed to read one.
     *
     * <p>It is the remote's or the filesystem's own words - {@code AnalysisJobManager.describe}
     * takes them verbatim - so it carries things a space's readers have no business seeing:
     * {@code https://gitlab.internal.corp:8443/platform/billing.git: not authorized} names an
     * internal host, and a storage refusal reports how much disk this node has left. The probe
     * endpoint already answers in categories for this reason; the list did not. Administrators
     * still get the detail, and it is in the log either way.</p>
     */
    private String failureDetail(String message)
    {
        return access.isAdmin() ? (message == null ? "" : message) : "";
    }

    private Map<String, Object> toDto(RepoSnapshot repo)
    {
        return toDto(repo, repositories.hasReport(repo.id));
    }

    private Map<String, Object> toDto(RepoSnapshot repo, boolean hasReport)
    {
        Map<String, Object> dto = new LinkedHashMap<String, Object>();
        dto.put("id", repo.id);
        dto.put("name", repo.name);
        // What every screen displays. Derived from the URL rather than taken from the stored
        // name, because the stored names come in two shapes: registrations from when the form
        // had a Name field carry whatever was typed, and everything since is owner/repo. One
        // table showing "captureV" beside "whisperlip-boop/dept_calendar" invites exactly the
        // question of which form is which - so the display is computed in one place and the
        // stored name stays what the macro parameter matches on.
        dto.put("label", label(repo));
        dto.put("url", repo.url);
        dto.put("branch", repo.branch);
        dto.put("authUser", repo.authUser);
        // The token itself never leaves the server.
        dto.put("hasToken", repo.hasToken());
        dto.put("spaceKeys", RepoSnapshot.joinKeys(repo.spaceKeys));
        dto.put("excludes", repo.excludes);
        dto.put("thresholds", repo.thresholds);
        dto.put("status", repo.status);
        dto.put("statusMessage", failureDetail(repo.statusMessage));
        dto.put("lastSyncedAt", repo.lastSyncedAt);
        dto.put("hasReport", hasReport);
        return dto;
    }

    /**
     * Display name derived from the clone URL as {@code owner/repo}.
     *
     * <p>Owner included on purpose. The bare repository name collides - {@code acme/api} and
     * {@code acme-fork/api} would both be "api" - and {@code owner/repo} is how everyone refers
     * to a GitHub repository anyway. Handles scp-style remotes and strips any credentials that
     * were pasted into the URL.</p>
     */
    /** {@code owner/repo} where the URL yields one, and the stored name where it does not. */
    private static String label(RepoSnapshot repo)
    {
        String derived = deriveName(repo.url);
        return derived == null || derived.isEmpty() ? repo.name : derived;
    }

    static String deriveName(String url)
    {
        String cleaned = url.trim();
        if (cleaned.endsWith(".git"))
        {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        while (cleaned.endsWith("/"))
        {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        int scheme = cleaned.indexOf("://");
        if (scheme >= 0)
        {
            String afterScheme = cleaned.substring(scheme + 3);
            int slash = afterScheme.indexOf('/');
            // Drops "user:password@host" along with the host.
            cleaned = slash < 0 ? afterScheme : afterScheme.substring(slash + 1);
        }
        else
        {
            int colon = cleaned.indexOf(':');
            if (colon >= 0)
            {
                cleaned = cleaned.substring(colon + 1);
            }
        }

        List<String> parts = new ArrayList<String>();
        for (String part : cleaned.split("/"))
        {
            if (!part.trim().isEmpty())
            {
                parts.add(part.trim());
            }
        }
        if (parts.isEmpty())
        {
            return url.trim();
        }
        if (parts.size() == 1)
        {
            return parts.get(0);
        }
        return parts.get(parts.size() - 2) + "/" + parts.get(parts.size() - 1);
    }

    private Response invalidUrl(RemoteUrl.InvalidRemoteException e)
    {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("error", e.getMessage());
        // A stable key so the browser can word it in the reader's language.
        payload.put("reason", e.reason());
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new Gson().toJson(payload)).type(MediaType.APPLICATION_JSON).build();
    }

    /** Tolerates a non-numeric id instead of throwing a 500 with a stack trace. */
    private static int integer(JsonObject object, String key)
    {
        try
        {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsInt() : 0;
        }
        catch (RuntimeException e)
        {
            return 0;
        }
    }

    private static JsonObject parse(String body)
    {
        if (body == null || body.trim().isEmpty())
        {
            return new JsonObject();
        }
        try
        {
            return new JsonParser().parse(body).getAsJsonObject();
        }
        catch (RuntimeException e)
        {
            return new JsonObject();
        }
    }

    private static String string(JsonObject object, String key)
    {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString().trim() : "";
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }

    private static Response error(Response.Status status, String message)
    {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("error", message);
        return Response.status(status).entity(new Gson().toJson(payload))
                .type(MediaType.APPLICATION_JSON).build();
    }
}
