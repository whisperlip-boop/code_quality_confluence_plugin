package co.bskim.confluence.codequality.analysis;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A3: how much of a commit's new code gets rewritten within the next two weeks.
 *
 * <p>Lines are remembered per path and matched by normalised text, so a later commit that
 * deletes the same text is attributed back to whoever introduced it. Entries older than the
 * window are pruned as the walk advances, which keeps memory proportional to two weeks of
 * activity rather than to the whole history.</p>
 *
 * <p>Censoring is the caller's job and it is not optional: for a commit less than 14 days old
 * the window has not closed, so a zero here means "not known yet". Reporting that as a good
 * score would make the newest commits always look healthiest.</p>
 */
public final class ChurnTracker
{
    private static final class Pending
    {
        final int commitIndex;
        final long timestamp;
        /** Follows the file across renames, so pruning can always find its slot. */
        String path;
        final String line;
        boolean consumed;

        Pending(int commitIndex, long timestamp, String path, String line)
        {
            this.commitIndex = commitIndex;
            this.timestamp = timestamp;
            this.path = path;
            this.line = line;
        }
    }

    private final Map<String, Map<String, Deque<Pending>>> byPath =
            new HashMap<String, Map<String, Deque<Pending>>>();
    private final Deque<Pending> chronological = new ArrayDeque<Pending>();
    private final long windowMs;
    /** When the analysis started: a commit dated after this is not telling the truth. */
    private final long analysedAt;
    /** Latest believable commit time seen so far. The window is measured back from here. */
    private long latestPlausible = Long.MIN_VALUE;

    public ChurnTracker(long windowMs, long analysedAt)
    {
        this.windowMs = windowMs;
        this.analysedAt = analysedAt;
    }

    /**
     * Drops everything that can no longer be churned, given the commit now being processed.
     *
     * <p>Commit timestamps are author-supplied and not monotonic: a wrong clock, a rebase or a
     * deliberately dated commit can put one years ahead. Taking the cutoff straight from the
     * current commit let a single such commit prune the whole tracker, after which every
     * following commit reported zero churn - a silent zero, which is the worst kind.</p>
     *
     * <p>So the window is measured back from the latest <em>believable</em> commit time seen,
     * where believable means "not after the analysis started". A commit dated two years out
     * no longer moves the window at all, and the walk carries on from where the real history
     * had reached. The watermark also never moves backwards, because the walk is ordered by
     * parentage rather than by date and re-pruning an earlier window would drop lines that are
     * still churnable.</p>
     *
     * <p>Capping the cutoff at the analysis clock instead is not enough, and finding that out
     * took writing the test: commits are replayed oldest-first by parentage, so while the walk
     * is at a commit a hundred days old, "now minus the window" is still a hundred days ahead
     * of it and prunes exactly the lines the next commit was about to churn.</p>
     */
    public void advanceTo(long now)
    {
        if (now <= analysedAt && now > latestPlausible)
        {
            latestPlausible = now;
        }
        if (latestPlausible == Long.MIN_VALUE)
        {
            // Nothing believable yet - every commit so far claims to be in the future. Prune
            // nothing rather than guess.
            return;
        }
        long cutoff = latestPlausible - windowMs;
        while (!chronological.isEmpty() && chronological.peekFirst().timestamp < cutoff)
        {
            Pending stale = chronological.pollFirst();
            if (stale.consumed)
            {
                continue;
            }
            Map<String, Deque<Pending>> lines = byPath.get(stale.path);
            if (lines == null)
            {
                continue;
            }
            Deque<Pending> slot = lines.get(stale.line);
            if (slot != null)
            {
                slot.removeLastOccurrence(stale);
                if (slot.isEmpty())
                {
                    lines.remove(stale.line);
                }
            }
            if (lines.isEmpty())
            {
                byPath.remove(stale.path);
            }
        }
    }

    public void recordAdded(int commitIndex, long timestamp, String path, List<String> lines,
                            int from, int to)
    {
        Map<String, Deque<Pending>> slots = byPath.get(path);
        if (slots == null)
        {
            slots = new HashMap<String, Deque<Pending>>();
            byPath.put(path, slots);
        }
        for (int i = from; i < to; i++)
        {
            String line = lines.get(i);
            Pending pending = new Pending(commitIndex, timestamp, path, line);
            Deque<Pending> slot = slots.get(line);
            if (slot == null)
            {
                slot = new ArrayDeque<Pending>();
                slots.put(line, slot);
            }
            slot.addLast(pending);
            chronological.addLast(pending);
        }
    }

    /**
     * Attributes deleted lines back to the commits that added them.
     *
     * @param churnByCommit accumulator indexed by the commit index passed to
     *                      {@link #recordAdded}
     */
    public void recordDeleted(long timestamp, String path, List<String> lines, int from, int to,
                              int[] churnByCommit)
    {
        Map<String, Deque<Pending>> slots = byPath.get(path);
        if (slots == null)
        {
            return;
        }
        for (int i = from; i < to; i++)
        {
            String line = lines.get(i);
            Deque<Pending> slot = slots.get(line);
            if (slot == null || slot.isEmpty())
            {
                continue;
            }
            // Most recent first: rewriting a line usually rewrites the newest version of it.
            Pending pending = slot.pollLast();
            // Left in the chronological queue and skipped when pruning: removing it there
            // would be a linear scan of two weeks of history on every deleted line.
            pending.consumed = true;
            if (slot.isEmpty())
            {
                slots.remove(line);
            }
            if (timestamp - pending.timestamp <= windowMs
                    && pending.commitIndex < churnByCommit.length)
            {
                churnByCommit[pending.commitIndex]++;
            }
        }
        if (slots.isEmpty())
        {
            byPath.remove(path);
        }
    }

    /** Keeps attribution intact across a rename, which git reports as one path becoming another. */
    public void renamePath(String oldPath, String newPath)
    {
        Map<String, Deque<Pending>> slots = byPath.remove(oldPath);
        if (slots == null)
        {
            return;
        }
        for (Deque<Pending> slot : slots.values())
        {
            for (Pending pending : slot)
            {
                pending.path = newPath;
            }
        }
        Map<String, Deque<Pending>> target = byPath.get(newPath);
        if (target == null)
        {
            byPath.put(newPath, slots);
        }
        else
        {
            for (Map.Entry<String, Deque<Pending>> entry : slots.entrySet())
            {
                Deque<Pending> existing = target.get(entry.getKey());
                if (existing == null)
                {
                    target.put(entry.getKey(), entry.getValue());
                }
                else
                {
                    existing.addAll(entry.getValue());
                }
            }
        }
    }
}
