package co.bskim.confluence.codequality.web;

import co.bskim.confluence.codequality.model.RepoSnapshot;
import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.security.PermissionManager;
import com.atlassian.confluence.spaces.Space;
import com.atlassian.confluence.spaces.SpaceManager;
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
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
    private final UserManager userManager;
    private final PermissionManager permissionManager;
    private final SpaceManager spaceManager;

    @Inject
    public AccessGuard(@ComponentImport UserManager userManager,
                       @ComponentImport PermissionManager permissionManager,
                       @ComponentImport SpaceManager spaceManager)
    {
        this.userManager = userManager;
        this.permissionManager = permissionManager;
        this.spaceManager = spaceManager;
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
        if (repo == null || !isLoggedIn())
        {
            return false;
        }
        if (isAdmin())
        {
            return true;
        }
        if (repo.spaceKeys.isEmpty())
        {
            return false;
        }
        ConfluenceUser user = AuthenticatedUserThreadLocal.get();
        for (String key : repo.spaceKeys)
        {
            Space space = spaceManager.getSpace(key);
            if (space != null && permissionManager.hasPermission(user, Permission.VIEW, space))
            {
                return true;
            }
        }
        return false;
    }

    /** Spaces the current user can view, for the registration form's picker. */
    public Map<String, String> viewableSpaces()
    {
        Map<String, String> spaces = new LinkedHashMap<String, String>();
        ConfluenceUser user = AuthenticatedUserThreadLocal.get();
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
