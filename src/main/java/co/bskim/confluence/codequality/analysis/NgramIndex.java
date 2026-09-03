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
 * whether the run was full or incremental - see {@link AnalysisConfig#MAX_INDEX_ENTRIES}. The
 * index is now a pure function of the tree it holds, which is what lets the same commit produce
 * the same numbers however the analysis got there.</p>
 */
final class NgramIndex
{
    /** Raised rather than degrading into wrong-but-plausible numbers. */
    static final class IndexTooLargeException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        IndexTooLargeException(int entries)
        {
            super("The analysable tree is too large to index (" + entries
                    + " windows, limit " + AnalysisConfig.MAX_INDEX_ENTRIES
                    + "). Narrow the exclude patterns or analyse a smaller subtree.");
        }
    }

    private static final long[] EMPTY = new long[0];

    private final Map<Long, long[]> buckets = new HashMap<Long, long[]>();
    private int entries;

    void addFile(int pathId, List<String> norm)
    {
        int limit = norm.size() - AnalysisConfig.RUN;
        for (int i = 0; i <= limit; i++)
        {
            if (++entries > AnalysisConfig.MAX_INDEX_ENTRIES)
            {
                throw new IndexTooLargeException(entries);
            }
            long h = hash(norm, i);
            long packed = pack(pathId, i);
            long[] bucket = buckets.get(h);
            if (bucket == null)
            {
                buckets.put(h, new long[] { packed });
            }
            else
            {
                long[] grown = Arrays.copyOf(bucket, bucket.length + 1);
                grown[bucket.length] = packed;
                buckets.put(h, grown);
            }
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
            int keep = 0;
            for (long packed : bucket)
            {
                if (unpackPath(packed) != pathId)
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
            if (keep == bucket.length)
            {
                continue;
            }
            long[] next = new long[keep];
            int at = 0;
            for (long packed : bucket)
            {
                if (unpackPath(packed) != pathId)
                {
                    next[at++] = packed;
                }
            }
            buckets.put(h, next);
        }
    }

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
