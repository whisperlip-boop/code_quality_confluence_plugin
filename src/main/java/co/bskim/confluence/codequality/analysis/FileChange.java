package co.bskim.confluence.codequality.analysis;

import org.eclipse.jgit.diff.EditList;

/** One file's before/after normalised content plus the edit script between them. */
public final class FileChange
{
    /** Null for an addition. */
    public final String oldPath;
    /** Null for a deletion. */
    public final String newPath;
    public final FileLines oldLines;
    public final FileLines newLines;
    public final EditList edits;

    public FileChange(String oldPath, String newPath, FileLines oldLines, FileLines newLines,
                      EditList edits)
    {
        this.oldPath = oldPath;
        this.newPath = newPath;
        this.oldLines = oldLines == null ? FileLines.EMPTY : oldLines;
        this.newLines = newLines == null ? FileLines.EMPTY : newLines;
        this.edits = edits;
    }
}
