package co.bskim.confluence.codequality.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds subtrees that are copies of other subtrees, so a duplication ratio measures the code
 * rather than the repository's layout.
 *
 * <p>Guava is the case that forced this. It ships {@code guava/src} and
 * {@code android/guava/src}: 602 files at the same relative paths, most of them byte-identical,
 * because it maintains a JRE flavour and an Android flavour of the same library. Measured
 * whole, half the repository is a copy of the other half and the ratio is 56% - arithmetically
 * right and useless. One flavour alone is 22%, and <em>that</em> is a number about Java:
 * {@code ImmutableIntArray} / {@code ImmutableLongArray} / {@code ImmutableDoubleArray},
 * {@code IntMath} / {@code LongMath}, and the several dozen {@code checkArgument} overloads in
 * {@code Preconditions} are duplication the language forces on anyone who wants primitives
 * without boxing.</p>
 *
 * <p>The shape is not specific to Guava - a vendored copy of a dependency, a multi-platform
 * source root, a monorepo with per-target clones of the same package - and dropping one side is
 * only defensible if a rule does it and it is <b>said out loud</b>. The result carries a
 * description of every pair found and the report shows it as a caveat. A tool that quietly
 * removes half a repository from its own denominator is worse than one that reports 56%.</p>
 *
 * <p>Only the static duplication measurement uses this. The copy-paste ratio still counts a
 * line added to both flavours as copied, because that is what it is: work done twice.</p>
 */
public final class MirrorTrees
{
    /** One subtree judged a copy of another. */
    public static final class Mirror
    {
        /** Root kept, e.g. {@code guava/src}. */
        public final String original;
        /** Root left out of the duplication measurement, e.g. {@code android/guava/src}. */
        public final String mirror;
        public final int sharedPaths;
        public final int droppedLines;
        /** Share of the sampled shared files that matched after normalisation. */
        public final double identicalShare;

        Mirror(String original, String mirror, int sharedPaths, int droppedLines,
               double identicalShare)
        {
            this.original = original;
            this.mirror = mirror;
            this.sharedPaths = sharedPaths;
            this.droppedLines = droppedLines;
            this.identicalShare = identicalShare;
        }
    }

    public static final class Result
    {
        /** Paths to leave out of the duplication measurement. */
        public final Set<String> excluded = new HashSet<String>();
        public final List<Mirror> mirrors = new ArrayList<Mirror>();

        public boolean isEmpty()
        {
            return mirrors.isEmpty();
        }
    }

    /** Below this many shared relative paths, two directories resembling each other is noise. */
    private static final int MIN_SHARED_PATHS = 25;
    /** Shared paths as a share of the smaller root's files: a partial overlap is not a mirror. */
    private static final double MIN_PATH_SHARE = 0.70;
    /** Shared files sampled for content, and how many of them have to match. */
    private static final int CONTENT_SAMPLE = 24;
    private static final double MIN_IDENTICAL_SHARE = 0.75;
    /** A file matches when this share of its normalised lines are the same. */
    private static final double FILE_SAME_SHARE = 0.90;
    /**
     * Files per basename considered when hunting for candidate roots. Groups like
     * {@code index.js} run to hundreds and pairing them all is quadratic for no gain: a real
     * mirror is found through dozens of different basenames.
     */
    private static final int MAX_PER_BASENAME = 40;

    private MirrorTrees()
    {
    }

    public static Result detect(TreeState state)
    {
        Result result = new Result();
        List<String> paths = state.sortedPaths();
        if (paths.size() < MIN_SHARED_PATHS * 2)
        {
            return result;
        }

        Map<String, Set<String>> sharedByPair = candidatePairs(paths);
        List<String> keys = new ArrayList<String>(sharedByPair.keySet());
        Collections.sort(keys);

        for (String key : keys)
        {
            Set<String> shared = sharedByPair.get(key);
            if (shared.size() < MIN_SHARED_PATHS)
            {
                continue;
            }
            int split = key.indexOf(' ');
            String rootA = key.substring(0, split);
            String rootB = key.substring(split + 1);

            int filesA = countUnder(paths, rootA);
            int filesB = countUnder(paths, rootB);
            int smaller = Math.min(filesA, filesB);
            if (smaller == 0 || shared.size() < smaller * MIN_PATH_SHARE)
            {
                continue;
            }
            if (alreadyExcluded(result, paths, rootA) || alreadyExcluded(result, paths, rootB))
            {
                // A wider pair already covers this one.
                continue;
            }
            double identical = identicalShare(state, rootA, rootB, shared);
            if (identical < MIN_IDENTICAL_SHARE)
            {
                continue;
            }

            // Which side to keep, in order: the fuller tree, then the shallower root, then
            // path order. Every criterion is a property of the tree, so the same commit makes
            // the same choice however the analysis reached it.
            //
            // Depth matters for the naming as much as the arithmetic. A variant usually sits
            // under a qualifier - android/guava beside guava, third_party/zlib beside zlib -
            // and when the two are byte-identical the line counts tie, at which point path
            // order alone would keep "android/guava" and report the original as the copy.
            // Worse for a vendored dependency: dropping the shallower root would drop your
            // own code and keep the vendored copy.
            int linesA = linesUnder(state, paths, rootA);
            int linesB = linesUnder(state, paths, rootB);
            boolean dropB;
            if (linesA != linesB)
            {
                dropB = linesB < linesA;
            }
            else
            {
                int depthA = depth(rootA);
                int depthB = depth(rootB);
                dropB = depthB != depthA ? depthB > depthA : rootB.compareTo(rootA) > 0;
            }
            String keep = dropB ? rootA : rootB;
            String drop = dropB ? rootB : rootA;

            int dropped = 0;
            for (String path : paths)
            {
                if (under(path, drop))
                {
                    result.excluded.add(path);
                    dropped += state.get(path).code.size();
                }
            }
            result.mirrors.add(new Mirror(keep, drop, shared.size(), dropped, identical));
        }
        Collections.sort(result.mirrors, BY_SIZE);
        return result;
    }

    /**
     * Roots that might mirror each other.
     *
     * <p>Two files at the same relative path under different prefixes are the signal: split a
     * pair of same-named files at their longest common run of trailing segments and the two
     * remaining prefixes are a candidate pair, with that run as one shared path.</p>
     */
    private static Map<String, Set<String>> candidatePairs(List<String> paths)
    {
        Map<String, List<String>> byBasename = new HashMap<String, List<String>>();
        for (String path : paths)
        {
            String base = path.substring(path.lastIndexOf('/') + 1);
            List<String> group = byBasename.get(base);
            if (group == null)
            {
                group = new ArrayList<String>();
                byBasename.put(base, group);
            }
            if (group.size() < MAX_PER_BASENAME)
            {
                group.add(path);
            }
        }

        Map<String, Set<String>> sharedByPair = new HashMap<String, Set<String>>();
        for (List<String> group : byBasename.values())
        {
            for (int i = 0; i < group.size(); i++)
            {
                for (int j = i + 1; j < group.size(); j++)
                {
                    String suffix = commonSuffix(group.get(i), group.get(j));
                    if (suffix.isEmpty())
                    {
                        continue;
                    }
                    String rootA = rootOf(group.get(i), suffix);
                    String rootB = rootOf(group.get(j), suffix);
                    if (rootA.equals(rootB) || rootA.isEmpty() || rootB.isEmpty())
                    {
                        continue;
                    }
                    String key = rootA.compareTo(rootB) < 0
                            ? rootA + ' ' + rootB : rootB + ' ' + rootA;
                    Set<String> shared = sharedByPair.get(key);
                    if (shared == null)
                    {
                        shared = new HashSet<String>();
                        sharedByPair.put(key, shared);
                    }
                    shared.add(suffix);
                }
            }
        }
        return sharedByPair;
    }

    private static boolean alreadyExcluded(Result result, List<String> paths, String root)
    {
        for (String path : paths)
        {
            if (under(path, root) && result.excluded.contains(path))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Longest suffix of whole path segments the two share, leaving at least one segment as a
     * root on each side.
     *
     * <p>The trim matters. {@code guava/src/.../Preconditions.java} and
     * {@code android/guava/src/.../Preconditions.java} share every segment of the shorter
     * path, so an untrimmed suffix leaves the roots {@code ""} and {@code android} - the first
     * of which is not a subtree, and the pair is discarded. One segment back gives
     * {@code guava} and {@code android/guava}, which is the pair that exists.</p>
     */
    private static String commonSuffix(String a, String b)
    {
        String[] one = a.split("/");
        String[] two = b.split("/");
        int k = 0;
        while (k < one.length && k < two.length
                && one[one.length - 1 - k].equals(two[two.length - 1 - k]))
        {
            k++;
        }
        k = Math.min(k, Math.min(one.length - 1, two.length - 1));
        if (k == 0)
        {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = one.length - k; i < one.length; i++)
        {
            if (out.length() > 0)
            {
                out.append('/');
            }
            out.append(one[i]);
        }
        return out.toString();
    }

    private static String rootOf(String path, String suffix)
    {
        int cut = path.length() - suffix.length();
        return cut <= 1 ? "" : path.substring(0, cut - 1);
    }

    private static String join(String root, String suffix)
    {
        return root.isEmpty() ? suffix : root + '/' + suffix;
    }

    private static boolean under(String path, String root)
    {
        return root.isEmpty() ? !path.contains("/") : path.startsWith(root + '/');
    }

    private static int depth(String root)
    {
        int segments = 1;
        for (int i = 0; i < root.length(); i++)
        {
            if (root.charAt(i) == '/')
            {
                segments++;
            }
        }
        return segments;
    }

    private static int countUnder(List<String> paths, String root)
    {
        int count = 0;
        for (String path : paths)
        {
            if (under(path, root))
            {
                count++;
            }
        }
        return count;
    }

    private static int linesUnder(TreeState state, List<String> paths, String root)
    {
        int lines = 0;
        for (String path : paths)
        {
            if (under(path, root))
            {
                lines += state.get(path).code.size();
            }
        }
        return lines;
    }

    /** Share of a sample of the shared files whose normalised content matches. */
    private static double identicalShare(TreeState state, String rootA, String rootB,
                                         Set<String> shared)
    {
        List<String> sample = new ArrayList<String>(shared);
        Collections.sort(sample);
        int step = Math.max(1, sample.size() / CONTENT_SAMPLE);
        int checked = 0;
        int same = 0;
        for (int i = 0; i < sample.size() && checked < CONTENT_SAMPLE; i += step)
        {
            FileLines a = state.get(join(rootA, sample.get(i)));
            FileLines b = state.get(join(rootB, sample.get(i)));
            if (a == null || b == null)
            {
                continue;
            }
            checked++;
            if (sameEnough(a.norm, b.norm))
            {
                same++;
            }
        }
        return checked == 0 ? 0 : (double) same / checked;
    }

    private static boolean sameEnough(List<String> a, List<String> b)
    {
        int longer = Math.max(a.size(), b.size());
        if (longer == 0)
        {
            return true;
        }
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (String line : a)
        {
            Integer n = counts.get(line);
            counts.put(line, n == null ? 1 : n + 1);
        }
        int common = 0;
        for (String line : b)
        {
            Integer n = counts.get(line);
            if (n != null && n > 0)
            {
                counts.put(line, n - 1);
                common++;
            }
        }
        return common >= longer * FILE_SAME_SHARE;
    }

    /** Biggest drop first, for reporting. */
    static final Comparator<Mirror> BY_SIZE = new Comparator<Mirror>()
    {
        @Override
        public int compare(Mirror x, Mirror y)
        {
            return y.droppedLines - x.droppedLines;
        }
    };
}
