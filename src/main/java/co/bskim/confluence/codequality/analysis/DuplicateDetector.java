package co.bskim.confluence.codequality.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B1: duplicated blocks at one point in history, in place of jscpd.
 *
 * <p>Reports the absolute line count as well as the ratio, on purpose. The reference
 * repository's duplication ratio sat between 1.0% and 1.6% for its whole life while the
 * absolute count went from 60 to 85 lines: the denominator grew at the same rate as the
 * problem, so the ratio alone said "no change". A dashboard that only shows the ratio
 * reproduces exactly the blind spot this plugin exists to remove.</p>
 */
public final class DuplicateDetector
{
    public static final class CloneHit
    {
        public final String fileA;
        public final int lineA;
        public final String fileB;
        public final int lineB;
        public final int lines;

        CloneHit(String fileA, int lineA, String fileB, int lineB, int lines)
        {
            this.fileA = fileA;
            this.lineA = lineA;
            this.fileB = fileB;
            this.lineB = lineB;
            this.lines = lines;
        }
    }

    public static final class Result
    {
        public int duplicatedLines;
        public int cloneCount;
        public final List<CloneHit> hits = new ArrayList<CloneHit>();
        /** Duplicated lines per file, for the "one file holds most of the clones" finding. */
        public final Map<String, Integer> byFile = new HashMap<String, Integer>();
        /** Subtrees left out as copies of other subtrees, for the report to declare. */
        public final List<MirrorTrees.Mirror> mirrors = new ArrayList<MirrorTrees.Mirror>();
        /**
         * Code lines actually measured - the tree's lines less any mirror subtree.
         *
         * <p>The ratio has to use this as its denominator. Taking mirror lines out of the
         * numerator while leaving them in the denominator halves the answer, which is a
         * different wrong number rather than a fix.</p>
         */
        public int measuredLines;
    }

    private DuplicateDetector()
    {
    }

    public static Result detect(TreeState state)
    {
        return detect(state, MirrorTrees.detect(state));
    }

    /**
     * @param mirrors subtrees to leave out because they are copies of other subtrees - see
     *                {@link MirrorTrees}. The result carries them so the report can say so.
     */
    public static Result detect(TreeState state, MirrorTrees.Result mirrors)
    {
        Result result = new Result();
        result.mirrors.addAll(mirrors.mirrors);
        List<String> paths = new ArrayList<String>();
        for (String path : state.sortedPaths())
        {
            if (!mirrors.excluded.contains(path))
            {
                paths.add(path);
                result.measuredLines += state.get(path).code.size();
            }
        }

        List<List<String>> norms = new ArrayList<List<String>>(paths.size());
        List<boolean[]> covered = new ArrayList<boolean[]>(paths.size());
        for (String path : paths)
        {
            List<String> norm = state.get(path).norm;
            norms.add(norm);
            covered.add(new boolean[norm.size()]);
        }

        // Filled as the walk proceeds, so a clone is always reported against the earlier
        // occurrence. Path/index order makes the output identical on every rerun.
        Map<Long, List<long[]>> seen = new HashMap<Long, List<long[]>>();
        int min = AnalysisConfig.DUP_MIN_LINES;

        for (int pi = 0; pi < paths.size(); pi++)
        {
            List<String> norm = norms.get(pi);
            int limit = norm.size() - min;
            for (int i = 0; i <= limit; i++)
            {
                long hash = NgramIndex.hash(norm, i, min);
                List<long[]> prior = seen.get(hash);
                if (prior != null && !covered.get(pi)[i])
                {
                    for (long[] loc : prior)
                    {
                        int qi = (int) loc[0];
                        int j = (int) loc[1];
                        if (covered.get(qi)[j])
                        {
                            continue;
                        }
                        int length = extend(norms.get(qi), j, norm, i, covered.get(qi),
                                covered.get(pi));
                        if (length < min || tokens(norm, i, length) < AnalysisConfig.DUP_MIN_TOKENS)
                        {
                            continue;
                        }
                        mark(covered.get(qi), j, length);
                        mark(covered.get(pi), i, length);
                        result.cloneCount++;
                        if (result.hits.size() < AnalysisConfig.MAX_CLONE_PAIRS)
                        {
                            result.hits.add(new CloneHit(
                                    paths.get(qi), state.get(paths.get(qi)).normLineNo[j],
                                    paths.get(pi), state.get(paths.get(pi)).normLineNo[i],
                                    length));
                        }
                        break;
                    }
                }
                List<long[]> bucket = prior;
                if (bucket == null)
                {
                    bucket = new ArrayList<long[]>(2);
                    seen.put(hash, bucket);
                }
                if (bucket.size() < 16)
                {
                    bucket.add(new long[] { pi, i });
                }
            }
        }

        for (int pi = 0; pi < paths.size(); pi++)
        {
            int count = 0;
            for (boolean flag : covered.get(pi))
            {
                if (flag)
                {
                    count++;
                }
            }
            if (count > 0)
            {
                result.byFile.put(paths.get(pi), count);
                result.duplicatedLines += count;
            }
        }

        Collections.sort(result.hits, new Comparator<CloneHit>()
        {
            @Override
            public int compare(CloneHit a, CloneHit b)
            {
                if (a.lines != b.lines)
                {
                    return b.lines - a.lines;
                }
                int byFile = a.fileA.compareTo(b.fileA);
                return byFile != 0 ? byFile : a.lineA - b.lineA;
            }
        });
        return result;
    }

    /** Longest equal run from the two starts that does not run into an existing clone. */
    private static int extend(List<String> a, int ai, List<String> b, int bi,
                              boolean[] coveredA, boolean[] coveredB)
    {
        int length = 0;
        while (ai + length < a.size() && bi + length < b.size()
                && !coveredA[ai + length] && !coveredB[bi + length]
                && a.get(ai + length).equals(b.get(bi + length)))
        {
            length++;
        }
        // Overlapping occurrences inside one file would otherwise report a block as its own clone.
        if (a == b && Math.abs(ai - bi) < length)
        {
            return 0;
        }
        return length;
    }

    private static int tokens(List<String> lines, int from, int length)
    {
        int total = 0;
        for (int i = from; i < from + length; i++)
        {
            total += LineNormalizer.tokenCount(lines.get(i));
        }
        return total;
    }

    private static void mark(boolean[] flags, int from, int length)
    {
        for (int i = from; i < from + length && i < flags.length; i++)
        {
            flags[i] = true;
        }
    }
}
