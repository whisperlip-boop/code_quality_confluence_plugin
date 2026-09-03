package co.bskim.confluence.codequality.analysis;

import org.eclipse.jgit.diff.Edit;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A1 and A2: splits a commit's added lines into new, copy-pasted and moved.
 *
 * <p>The first version of this judged every added line on its own and reported a 13.7%
 * copy-paste ratio for the reference repository - almost all of it idioms like
 * {@code painter.end()} that appear in every file of a Qt codebase. Requiring
 * {@link AnalysisConfig#RUN} consecutive matching lines dropped it to 4.4%, and every block
 * left was a real duplicate. Anything that loosens the block rule will bring the false
 * positives straight back.</p>
 *
 * <p>Copy versus move is decided by what happened to the source: if the matched block is gone
 * from its original path once the commit is applied it was a move (refactoring), otherwise
 * the code now exists twice.</p>
 */
public final class CopyPasteClassifier
{
    private CopyPasteClassifier()
    {
    }

    /**
     * @param deletedRanges normalised index ranges this commit removed, per old path
     * @param goneFromOldPath paths this commit deleted or renamed away
     */
    public static void classify(List<FileChange> changes, TreeState parent,
                                Map<String, List<int[]>> deletedRanges,
                                Set<String> goneFromOldPath, LineMix out)
    {
        for (FileChange change : changes)
        {
            for (Edit edit : change.edits)
            {
                out.deleted += edit.getEndA() - edit.getBeginA();

                int begin = edit.getBeginB();
                int end = edit.getEndB();
                if (end <= begin)
                {
                    continue;
                }
                classifyRun(change.newLines.norm, begin, end, parent, deletedRanges,
                        goneFromOldPath, out);
            }
        }
    }

    private static void classifyRun(List<String> lines, int begin, int end, TreeState parent,
                                    Map<String, List<int[]>> deletedRanges,
                                    Set<String> goneFromOldPath, LineMix out)
    {
        int i = begin;
        while (i < end)
        {
            int available = end - i;
            if (available < AnalysisConfig.RUN)
            {
                out.novel += available;
                return;
            }

            long hash = NgramIndex.hash(lines, i);
            long[] candidates = parent.index().lookup(hash);

            int bestLength = 0;
            int bestPathId = -1;
            int bestStart = -1;

            // Slot 0 is the count; the array behind it is the index's storage and is longer.
            int candidateCount = (int) candidates[0];
            for (int c = 1; c <= candidateCount; c++)
            {
                long packed = candidates[c];
                int pathId = NgramIndex.unpackPath(packed);
                int start = NgramIndex.unpackIndex(packed);
                FileLines source = parent.get(parent.path(pathId));
                if (source == null)
                {
                    continue;
                }
                int length = matchLength(lines, i, end, source.norm, start);
                if (length < AnalysisConfig.RUN)
                {
                    continue;
                }
                // Ties break on the lexicographically smaller source path so that a rerun of
                // the same commit reports the same block.
                if (length > bestLength
                        || (length == bestLength && bestPathId >= 0
                            && parent.path(pathId).compareTo(parent.path(bestPathId)) < 0))
                {
                    bestLength = length;
                    bestPathId = pathId;
                    bestStart = start;
                }
            }

            if (bestLength < AnalysisConfig.RUN)
            {
                out.novel++;
                i++;
                continue;
            }

            String sourcePath = parent.path(bestPathId);
            if (sourceSurvives(sourcePath, bestStart, bestLength, deletedRanges,
                    goneFromOldPath))
            {
                out.copied += bestLength;
            }
            else
            {
                out.moved += bestLength;
            }
            i += bestLength;
        }
    }

    private static int matchLength(List<String> added, int addedFrom, int addedEnd,
                                   List<String> source, int sourceFrom)
    {
        int length = 0;
        while (addedFrom + length < addedEnd && sourceFrom + length < source.size()
                && added.get(addedFrom + length).equals(source.get(sourceFrom + length)))
        {
            length++;
        }
        return length;
    }

    /**
     * A move is a block whose source lines this very commit removed. Searching the post-commit
     * file for the block instead would call a within-file move a copy, because the block it
     * finds is the copy that was just added - which is how the first cut of this reported zero
     * moves on a repository that had twelve.
     */
    private static boolean sourceSurvives(String sourcePath, int start, int length,
                                          Map<String, List<int[]>> deletedRanges,
                                          Set<String> goneFromOldPath)
    {
        if (goneFromOldPath.contains(sourcePath))
        {
            return false;
        }
        List<int[]> ranges = deletedRanges.get(sourcePath);
        if (ranges == null)
        {
            // The commit did not touch the source file, so the block is still there.
            return true;
        }
        for (int index = start; index < start + length; index++)
        {
            if (!covered(ranges, index))
            {
                // Part of the block stayed behind: the code now exists twice.
                return true;
            }
        }
        return false;
    }

    private static boolean covered(List<int[]> ranges, int index)
    {
        for (int[] range : ranges)
        {
            if (index >= range[0] && index < range[1])
            {
                return true;
            }
        }
        return false;
    }
}
