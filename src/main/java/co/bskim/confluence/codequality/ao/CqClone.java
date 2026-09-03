package co.bskim.confluence.codequality.ao;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

/** A duplicated block pair found at HEAD, kept so the report can link straight to it. */
@Table("CLONE")
public interface CqClone extends Entity
{
    @Indexed
    int getRepoId();
    void setRepoId(int repoId);

    @StringLength(450)
    String getFileA();
    void setFileA(String fileA);

    int getLineA();
    void setLineA(int lineA);

    @StringLength(450)
    String getFileB();
    void setFileB(String fileB);

    int getLineB();
    void setLineB(int lineB);

    int getLines();
    void setLines(int lines);
}
