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
 */
final class NgramIndex
{
    private static final long[] EMPTY = new long[0];

    private final Map<Long, long[]> buckets = new HashMap<Long, long[]>();

    void addFile(int pathId, List<String> norm)
    {
        int limit = norm.size() - AnalysisConfig.RUN;
        for (int i = 0; i <= limit; i++)
        {
            long h = hash(norm, i);
            long packed = pack(pathId, i);
            long[] bucket = buckets.get(h);
            if (bucket == null)
            {
                buckets.put(h, new long[] { packed });
            }
            else if (bucket.length < AnalysisConfig.MAX_INDEX_BUCKET)
            {
                long[] grown = Arrays.copyOf(bucket, bucket.length + 1);
                grown[bucket.length] = packed;
                buckets.put(h, grown);
            }
            // A window occurring more than MAX_INDEX_BUCKET times is boilerplate; one more
            // location would not change any verdict.
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
