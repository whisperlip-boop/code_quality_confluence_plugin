package co.bskim.confluence.codequality.analysis;

/** How one commit's added lines break down. */
public final class LineMix
{
    public int novel;
    public int copied;
    public int moved;
    public int deleted;

    public int added()
    {
        return novel + copied + moved;
    }
}
