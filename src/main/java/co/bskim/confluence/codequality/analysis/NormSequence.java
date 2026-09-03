package co.bskim.confluence.codequality.analysis;

import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.diff.SequenceComparator;

import java.util.List;

/**
 * Lets JGit's histogram diff run directly over normalised lines instead of raw bytes.
 *
 * <p>Diffing in normalised space is what makes the rest of the pipeline simple: an added run
 * is already a contiguous range of normalised indices, so reindentation, comment edits and
 * blank-line churn never show up as changes and no raw-to-normalised line mapping is needed.</p>
 */
public final class NormSequence extends Sequence
{
    static final SequenceComparator<NormSequence> CMP = new SequenceComparator<NormSequence>()
    {
        @Override
        public boolean equals(NormSequence a, int ai, NormSequence b, int bi)
        {
            return a.lines.get(ai).equals(b.lines.get(bi));
        }

        @Override
        public int hash(NormSequence seq, int at)
        {
            return seq.lines.get(at).hashCode();
        }
    };

    final List<String> lines;

    public NormSequence(List<String> lines)
    {
        this.lines = lines;
    }

    @Override
    public int size()
    {
        return lines.size();
    }
}
