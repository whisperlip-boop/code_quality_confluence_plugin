package co.bskim.confluence.codequality.web;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Who may do what.
 *
 * <p>Registering a repository means handing the instance a credential and asking it to clone
 * from the internet, so that stays with Confluence administrators. Reading a report is
 * ordinary page content and only needs a logged-in user.</p>
 */
@Named
public class AccessGuard
{
    private final UserManager userManager;

    @Inject
    public AccessGuard(@ComponentImport UserManager userManager)
    {
        this.userManager = userManager;
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
}
