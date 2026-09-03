package co.bskim.confluence.codequality.analysis;

import java.util.List;

/**
 * Two views of one source file, both derived from the same blob.
 *
 * <p>{@code code} keeps indentation and short lines because the error-swallowing and call
 * scanners need block structure. {@code norm} drops anything under
 * {@link AnalysisConfig#MIN_LINE_LENGTH} characters, because {@code }}, {@code else:} and
 * {@code return} matching across files is the false positive that sank the line-level
 * classifier in the PoC.</p>
 */
public final class FileLines
{
    public static final FileLines EMPTY = new FileLines(
            java.util.Collections.<String>emptyList(), new int[0],
            java.util.Collections.<String>emptyList(), new int[0], 0, 0, false);

    /** Comment-stripped, right-trimmed, non-blank lines - indentation preserved. */
    public final List<String> code;
    /** 1-based source line number of each entry in {@link #code}. */
    public final int[] codeLineNo;
    /** Subset of {@link #code}, trimmed and whitespace-collapsed, long enough to be meaningful. */
    public final List<String> norm;
    /** 1-based source line number of each entry in {@link #norm}. */
    public final int[] normLineNo;
    /** Non-blank lines that were nothing but a comment - the legacy comment-density metric. */
    public final int commentLines;
    /** Non-blank raw lines, comments included. */
    public final int totalLines;
    /** Overwhelmingly literals: a generated lookup table rather than maintained code. */
    public final boolean dataTable;

    public FileLines(List<String> code, int[] codeLineNo, List<String> norm, int[] normLineNo,
                     int commentLines, int totalLines, boolean dataTable)
    {
        this.code = code;
        this.codeLineNo = codeLineNo;
        this.norm = norm;
        this.normLineNo = normLineNo;
        this.commentLines = commentLines;
        this.totalLines = totalLines;
        this.dataTable = dataTable;
    }

    /** Index of the first {@link #norm} entry whose source line is >= {@code lineNo}. */
    public int normIndexAtOrAfter(int lineNo)
    {
        int lo = 0;
        int hi = normLineNo.length;
        while (lo < hi)
        {
            int mid = (lo + hi) >>> 1;
            if (normLineNo[mid] < lineNo)
            {
                lo = mid + 1;
            }
            else
            {
                hi = mid;
            }
        }
        return lo;
    }
}
