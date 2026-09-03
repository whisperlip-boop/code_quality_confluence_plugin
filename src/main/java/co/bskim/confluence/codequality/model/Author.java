package co.bskim.confluence.codequality.model;

import java.util.LinkedHashSet;
import java.util.Set;

/** One person, after identity merging. */
public final class Author
{
    public String key;
    public String name;
    public String email;
    public int commits;
    public int addedLines;
    /** Every raw name/email pair that folded into this person. */
    public final Set<String> identities = new LinkedHashSet<String>();
}
