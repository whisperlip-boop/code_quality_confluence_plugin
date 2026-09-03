package co.bskim.confluence.codequality.analysis;

import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs one commit through {@link CopyPasteClassifier} without going near git.
 *
 * <p>Mirrors what {@link AnalysisEngine} does per commit - build the changed files' before and
 * after views, diff them in normalised space, collect the deleted ranges - so the classifier
 * can be pinned down by fixtures instead of by a manual reading of one repository.</p>
 */
final class ClassifierFixture
{
    private final Language language;
    private final TreeState parent = new TreeState();
    private final Map<String, String> after = new LinkedHashMap<String, String>();
    private final Set<String> removed = new HashSet<String>();

    ClassifierFixture(Language language)
    {
        this.language = language;
    }

    /** A file as it stands in the parent commit. */
    ClassifierFixture before(String path, String content)
    {
        parent.put(path, LineNormalizer.parse(content, language));
        return this;
    }

    /**
     * Loads the parent in a given path order, which is the whole point of one of the tests:
     * a full run inserts files as commits touch them, an incremental run in path order.
     */
    ClassifierFixture beforeInOrder(List<String> paths, Map<String, String> contents)
    {
        for (String path : paths)
        {
            parent.put(path, LineNormalizer.parse(contents.get(path), language));
        }
        return this;
    }

    /** A file as it stands after the commit. Absent files are treated as unchanged. */
    ClassifierFixture change(String path, String content)
    {
        after.put(path, content);
        return this;
    }

    ClassifierFixture delete(String path)
    {
        removed.add(path);
        return this;
    }

    LineMix classify()
    {
        List<FileChange> changes = new ArrayList<FileChange>();
        Map<String, List<int[]>> deletedRanges = new HashMap<String, List<int[]>>();
        Set<String> gone = new HashSet<String>(removed);
        HistogramDiff diff = new HistogramDiff();

        for (Map.Entry<String, String> entry : after.entrySet())
        {
            String path = entry.getKey();
            FileLines was = parent.get(path) == null ? FileLines.EMPTY : parent.get(path);
            FileLines now = LineNormalizer.parse(entry.getValue(), language);
            EditList edits = diff.diff(NormSequence.CMP, new NormSequence(was.norm),
                    new NormSequence(now.norm));
            changes.add(new FileChange(parent.get(path) == null ? null : path, path, was, now,
                    edits));
            deletedRanges.put(path, deletions(edits));
        }
        for (String path : removed)
        {
            FileLines was = parent.get(path) == null ? FileLines.EMPTY : parent.get(path);
            EditList edits = diff.diff(NormSequence.CMP, new NormSequence(was.norm),
                    new NormSequence(FileLines.EMPTY.norm));
            changes.add(new FileChange(path, null, was, FileLines.EMPTY, edits));
            deletedRanges.put(path, deletions(edits));
        }

        LineMix mix = new LineMix();
        CopyPasteClassifier.classify(changes, parent, deletedRanges, gone, mix);
        return mix;
    }

    private static List<int[]> deletions(EditList edits)
    {
        List<int[]> ranges = new ArrayList<int[]>();
        for (org.eclipse.jgit.diff.Edit edit : edits)
        {
            if (edit.getEndA() > edit.getBeginA())
            {
                ranges.add(new int[] { edit.getBeginA(), edit.getEndA() });
            }
        }
        return ranges;
    }

    static String lines(String... values)
    {
        StringBuilder out = new StringBuilder();
        for (String value : values)
        {
            out.append(value).append('\n');
        }
        return out.toString();
    }
}
