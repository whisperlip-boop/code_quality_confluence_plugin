package co.bskim.confluence.codequality.rest;

import co.bskim.confluence.codequality.git.GitClient;
import co.bskim.confluence.codequality.git.RepoAuth;
import co.bskim.confluence.codequality.model.RepoSnapshot;
import co.bskim.confluence.codequality.service.AnalysisJobManager;
import co.bskim.confluence.codequality.service.RepositoryService;
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
        for (RepoSnapshot repo : repositories.all())
        {
            rows.add(toDto(repo));
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
        String url = string(input, "url");
        if (url.isEmpty())
        {
            return error(Response.Status.BAD_REQUEST, "Repository URL is required");
        }
        String name = string(input, "name");
        RepoSnapshot repo = repositories.create(
                name.isEmpty() ? deriveName(url) : name,
                url,
                string(input, "branch"),
                string(input, "authUser"),
                string(input, "token"),
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
        // A missing token field means "keep what is stored"; an empty one means "remove it".
        String token = input.has("token") ? string(input, "token") : null;
        RepoSnapshot repo = repositories.update(id, string(input, "name"), string(input, "url"),
                string(input, "branch"), string(input, "authUser"), token,
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
        if (repo == null)
        {
            return error(Response.Status.NOT_FOUND, "Repository not found");
        }
        AnalysisJobManager.JobState state = jobs.state(id);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", state != null ? state.status : repo.status);
        payload.put("phase", state != null ? state.phase : "");
        payload.put("current", state != null ? state.current : 0);
        payload.put("total", state != null ? state.total : 0);
        payload.put("message", state != null && !state.message.isEmpty()
                ? state.message : repo.statusMessage);
        payload.put("lastSyncedAt", repo.lastSyncedAt);
        payload.put("hasReport", repositories.hasReport(id));
        return Response.ok(new Gson().toJson(payload)).build();
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
        String url = string(input, "url");
        String token = string(input, "token");
        int id = input.has("id") ? input.get("id").getAsInt() : 0;
        if (token.isEmpty() && id > 0)
        {
            // Editing an existing repository without retyping the token.
            RepoAuth stored = repositories.authFor(repositories.byId(id));
            token = stored.token;
        }
        GitClient.ProbeResult probe =
                gitClient.probe(url, new RepoAuth(string(input, "authUser"), token));

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("ok", probe.reachable);
        payload.put("branches", probe.branches);
        payload.put("anonymous", probe.anonymous);
        payload.put("tokenVerified", probe.tokenVerified);
        // Whether a token was offered at all, so the form can say it went unchecked.
        payload.put("tokenOffered", !token.isEmpty());
        payload.put("error", probe.error);
        return Response.ok(new Gson().toJson(payload)).build();
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> toDto(RepoSnapshot repo)
    {
        Map<String, Object> dto = new LinkedHashMap<String, Object>();
        dto.put("id", repo.id);
        dto.put("name", repo.name);
        dto.put("url", repo.url);
        dto.put("branch", repo.branch);
        dto.put("authUser", repo.authUser);
        // The token itself never leaves the server.
        dto.put("hasToken", repo.hasToken());
        dto.put("excludes", repo.excludes);
        dto.put("thresholds", repo.thresholds);
        dto.put("status", repo.status);
        dto.put("statusMessage", repo.statusMessage);
        dto.put("lastSyncedAt", repo.lastSyncedAt);
        dto.put("hasReport", repositories.hasReport(repo.id));
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
