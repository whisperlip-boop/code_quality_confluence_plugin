package co.bskim.confluence.codequality.web;

import co.bskim.confluence.codequality.model.RepoSnapshot;
import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.security.PermissionManager;
import com.atlassian.confluence.spaces.Space;
import com.atlassian.confluence.spaces.SpaceManager;
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.confluence.user.UserAccessor;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who may do what.
 *
 * <p>Registering a repository means handing the instance a credential and asking it to clone
 * from the internet, so that stays with Confluence administrators.</p>
 *
 * <p>Reading is scoped by space. A repository is linked to one or more spaces and is visible to
 * whoever can view any of them, which reuses the permission model the instance already has
 * instead of inventing a second one - a personal repository goes in a personal space, a team's
 * goes in the team's. A repository with no spaces linked is visible to administrators only:
 * these reports carry a private codebase's file paths, commit subjects and author addresses,
 * and the safe reading of "not configured yet" is "not shared".</p>
 */
@Named
public class AccessGuard
{
    private static final Logger log = LoggerFactory.getLogger(AccessGuard.class);

    private final UserManager userManager;
    private final PermissionManager permissionManager;
    private final SpaceManager spaceManager;
    private final UserAccessor userAccessor;

    @Inject
    public AccessGuard(@ComponentImport UserManager userManager,
                       @ComponentImport PermissionManager permissionManager,
                       @ComponentImport SpaceManager spaceManager,
                       @ComponentImport UserAccessor userAccessor)
    {
        this.userManager = userManager;
        this.permissionManager = permissionManager;
        this.spaceManager = spaceManager;
        this.userAccessor = userAccessor;
    }

    public UserProfile currentUser()
    {
        return userManager.getRemoteUser();
    }

    public boolean isLoggedIn()
    {
        return userManager.getRemoteUser() != null;
    }

    public boolean isAdmin()
    {
        UserProfile user = userManager.getRemoteUser();
        return user != null
                && (userManager.isAdmin(user.getUserKey())
                    || userManager.isSystemAdmin(user.getUserKey()));
    }

    public String currentUserName()
    {
        UserProfile user = userManager.getRemoteUser();
        return user == null ? "" : user.getUsername();
    }

    /** True when the current user may see this repository's row and report. */
    public boolean canView(RepoSnapshot repo)
    {
        return repo != null && !viewable(Collections.singletonList(repo)).isEmpty();
    }

    /**
     * The subset of these repositories the current user may see.
     *
     * <p>The list endpoint used to call {@link #canView} per row, and each call asked SAL who
     * is calling, resolved them to a {@code ConfluenceUser}, and then looked up and checked
     * every one of that row's spaces. Forty repositories across three spaces came to forty user
     * resolutions and up to a hundred and twenty space lookups - on every page that hosts the
     * macro, and again every two seconds while a run is in flight. None of those answers can
     * change within one response, so each is worked out once.</p>
     *
     * <p>{@code canView} is this method with one element, deliberately: a permission rule with
     * two implementations is a permission rule with two behaviours.</p>
     */
    public List<RepoSnapshot> viewable(List<RepoSnapshot> repos)
    {
        List<RepoSnapshot> visible = new ArrayList<RepoSnapshot>();
        if (repos.isEmpty() || !isLoggedIn())
        {
            return visible;
        }
        if (isAdmin())
        {
            visible.addAll(repos);
            return visible;
        }
        ConfluenceUser user = confluenceUser();
        if (user == null)
        {
            // isLoggedIn() said there is a user, so failing to resolve them is a bug or an
            // unusual REST stack - not a licence to check permissions as anonymous.
            log.warn("Could not resolve {} to a Confluence user; refusing access",
                    currentUserName());
            return visible;
        }
        Map<String, Boolean> bySpace = new HashMap<String, Boolean>();
        for (RepoSnapshot repo : repos)
        {
            // No spaces linked means administrators only, and this user is not one.
            for (String key : repo.spaceKeys)
            {
                Boolean allowed = bySpace.get(key);
                if (allowed == null)
                {
                    Space space = spaceManager.getSpace(key);
                    allowed = space != null
                            && permissionManager.hasPermission(user, Permission.VIEW, space);
                    bySpace.put(key, allowed);
                }
                if (allowed)
                {
                    visible.add(repo);
                    break;
                }
            }
        }
        return visible;
    }

    /**
     * Resolves the request's user through one path only.
     *
     * <p>{@code isLoggedIn} and {@code isAdmin} ask SAL's {@link UserManager}, while the space
     * check needs a {@code ConfluenceUser}. Taking the second from
     * {@link AuthenticatedUserThreadLocal} meant two different answers to "who is asking":
     * on a REST stack where SAL sees a user and the thread local is empty,
     * {@code hasPermission(null, ...)} is an <b>anonymous</b> check, so one space open to
     * anonymous viewers would have let that request through. Resolving from the same user key
     * SAL answered with keeps it to one subject.</p>
     */
    private ConfluenceUser confluenceUser()
    {
        UserProfile profile = userManager.getRemoteUser();
        if (profile == null || profile.getUserKey() == null)
        {
            return null;
        }
        ConfluenceUser user = userAccessor.getExistingUserByKey(profile.getUserKey());
        if (user != null)
        {
            return user;
        }
        // Falls back to the thread local, which is right whenever it is populated.
        return AuthenticatedUserThreadLocal.get();
    }

    /** Spaces the current user can view, for the registration form's picker. */
    public Map<String, String> viewableSpaces()
    {
        Map<String, String> spaces = new LinkedHashMap<String, String>();
        ConfluenceUser user = confluenceUser();
        if (user == null)
        {
            return spaces;
        }
        for (Space space : spaceManager.getAllSpaces())
        {
            if (permissionManager.hasPermission(user, Permission.VIEW, space))
            {
                spaces.put(space.getKey(), space.getName());
            }
        }
        return spaces;
    }

    /** Of the given keys, the ones that are not existing spaces. */
    public List<String> unknownSpaceKeys(List<String> keys)
    {
        List<String> unknown = new ArrayList<String>();
        for (String key : keys)
        {
            if (spaceManager.getSpace(key) == null)
            {
                unknown.add(key);
            }
        }
        return unknown;
    }
}
