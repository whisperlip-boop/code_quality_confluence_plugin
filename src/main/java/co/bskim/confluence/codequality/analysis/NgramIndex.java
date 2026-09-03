package co.bskim.confluence.codequality.analysis;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hash index over every {@link AnalysisConfig#RUN}-line window of the tree, maintained as a
 * delta.
 *
 * <p>Rebuilding this from the whole tree on every commit was the PoC's bottleneck and would
 * not survive a real repository, so files are added and removed one at a time as commits are
 * replayed. Buckets only carry candidate locations - the caller always re-compares the actual
 * lines, so a hash collision costs a comparison rather than a wrong number.</p>
 *
 * <p><b>Every</b> location is kept. An earlier version capped each bucket, which made the
 * contents depend on the order files were inserted and so made the copy/move verdict depend on
 * whether the run was full or incremental - see {@link AnalysisConfig#maxIndexEntries()}. The
 * index is now a pure function of the tree it holds, which is what lets the same commit produce
 * the same numbers however the analysis got there.</p>
 *
 * <p>Removing the cap changed what the bucket has to be. A capped bucket could be grown one
 * slot at a time because it stopped at 64; uncapped, the same code copies the whole bucket on
 * every insert, so a window appearing N times costs N-squared/2 long copies. Buckets therefore
 * double, and carry their own length in slot 0 - {@link #lookup} hands back the raw array and
 * the caller reads the count from it, which keeps this to one map lookup and no allocation on
 * the hot path.</p>
 */
final class NgramIndex
{
    /** Raised rather than degrading into wrong-but-plausible numbers. */
    static final class IndexTooLargeException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        IndexTooLargeException(int entries, int limit)
        {
            super("The analysable tree is too large to index (" + entries
                    + " windows, limit " + limit + " for a "
                    + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "MB heap"
                    + "). Narrow the exclude patterns or analyse a smaller subtree.");
        }
    }

    /** A bucket holding nothing: slot 0 is the count, so an empty one is {@code {0}}. */
    private static final long[] EMPTY = new long[] { 0 };

    private final Map<Long, long[]> buckets = new HashMap<Long, long[]>();
    /**
     * Read once per index, so one run cannot change its own ceiling part way through.
     *
     * <p>Named for the field it is, not {@code limit}: {@link #addFile} and
     * {@link #removeFile} both use a local {@code limit} for the last window offset, and a
     * field by that name is shadowed by it - which silently turned the ceiling into "the
     * number of windows in the file being added".</p>
     */
    private final int entryCeiling = AnalysisConfig.maxIndexEntries();
    private int entries;

    void addFile(int pathId, List<String> norm)
    {
        int limit = norm.size() - AnalysisConfig.RUN;
        for (int i = 0; i <= limit; i++)
        {
            if (++entries > entryCeiling)
            {
                throw new IndexTooLargeException(entries, entryCeiling);
            }
            long h = hash(norm, i);
            long packed = pack(pathId, i);
            long[] bucket = buckets.get(h);
            if (bucket == null)
            {
                // Two longs: the count and one location. Most windows in a tree are unique,
                // so this is the size that decides the index's footprint.
                buckets.put(h, new long[] { 1, packed });
                continue;
            }
            int used = (int) bucket[0];
            if (used + 1 >= bucket.length)
            {
                bucket = Arrays.copyOf(bucket, bucket.length * 2);
                buckets.put(h, bucket);
            }
            bucket[used + 1] = packed;
            bucket[0] = used + 1;
        }
    }

    void removeFile(int pathId, List<String> norm)
    {
        int limit = norm.size() - AnalysisConfig.RUN;
        for (int i = 0; i <= limit; i++)
        {
            long h = hash(norm, i);
            long[] bucket = buckets.get(h);
            if (bucket == null)
            {
                continue;
            }
            int used = (int) bucket[0];
            int keep = 0;
            for (int k = 1; k <= used; k++)
            {
                if (unpackPath(bucket[k]) != pathId)
                {
                    keep++;
                }
                else
                {
                    entries--;
                }
            }
            if (keep == 0)
            {
                buckets.remove(h);
                continue;
            }
            if (keep == used)
            {
                continue;
            }
            // Compacted in place: the array keeps its capacity, so a file removed and re-added
            // as commits replay does not reallocate every time.
            int at = 1;
            for (int k = 1; k <= used; k++)
            {
                if (unpackPath(bucket[k]) != pathId)
                {
                    bucket[at++] = bucket[k];
                }
            }
            bucket[0] = keep;
        }
    }

    /**
     * The raw bucket for a hash: slot 0 is how many locations follow it.
     *
     * <p>The array is the index's own storage and longer than the count - iterate
     * {@code 1..bucket[0]}, never the whole thing.</p>
     */
    long[] lookup(long hash)
    {
        long[] bucket = buckets.get(hash);
        return bucket == null ? EMPTY : bucket;
    }

    static long hash(List<String> lines, int offset)
    {
        long h = 1125899906842597L;
        for (int k = 0; k < AnalysisConfig.RUN; k++)
        {
            h = h * 31 + lines.get(offset + k).hashCode();
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return h;
    }

    /** Hash of a window of arbitrary length, used by the duplicate detector. */
    static long hash(List<String> lines, int offset, int length)
    {
        long h = 1125899906842597L;
        for (int k = 0; k < length; k++)
        {
            h = h * 31 + lines.get(offset + k).hashCode();
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return h;
    }

    static long pack(int pathId, int index)
    {
        return ((long) pathId << 32) | (index & 0xffffffffL);
    }

    static int unpackPath(long packed)
    {
        return (int) (packed >>> 32);
    }

    static int unpackIndex(long packed)
    {
        return (int) packed;
    }
}
