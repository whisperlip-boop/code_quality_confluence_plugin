package co.bskim.confluence.codequality.analysis;

import co.bskim.confluence.codequality.model.Author;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.NullOutputStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a repository's first-parent history and produces per-commit metrics.
 *
 * <p><b>History shape.</b> The walk follows first parents only, so a merge commit is analysed
 * as one change against the branch point - which for a pull-request workflow attributes a
 * whole PR to its merge. That keeps the tree state linear, which is what makes the incremental
 * n-gram index possible at all.</p>
 *
 * <p><b>Incremental replay.</b> Cached commits are not recomputed, except for a trailing window
 * wide enough that every commit whose churn could still change gets re-derived. The floor is
 * {@code min(oldest uncached commit, previous run - 14d) - 14d}: the first term picks up new
 * work, the second re-opens commits that were still censored last time, and the extra 14 days
 * gives the churn tracker the added lines it needs to attribute deletions against.</p>
 *
 * <p><b>Determinism.</b> Nothing here consults a model or a network service; the same commit
 * always yields the same numbers, which is the only reason the trend lines mean anything. Every
 * iteration order that could affect a result is sorted.</p>
 */
public final class AnalysisEngine
{
    /** Reports progress and lets a caller abort a long run. */
    public interface Progress
    {
        void report(String phase, int current, int total);

        boolean cancelled();
    }

    public static final class Outcome
    {
        /** Chronological, oldest first. Cached commits are carried through unchanged. */
        public List<CommitStats> commits = new ArrayList<CommitStats>();
        public String headSha = "";
        public long headCommittedAt;
        public Map<String, Author> authors = new HashMap<String, Author>();
        public int rawIdentityCount;
        /** Pairs of author names that look like the same person; see IdentityResolver. */
        public List<String[]> identitySuspects = new ArrayList<String[]>();
        public String branch = "";

        public int headLoc;
        public int headFiles;
        public int headDupLines;
        public int headDupClones;
        /** Lines the duplication ratio was measured over: HEAD less any mirror subtree. */
        public int headDupMeasuredLines;
        /**
         * True when the history is longer than {@link AnalysisConfig#MAX_COMMITS}, so every
         * fact on the report describes the newest slice of it rather than the whole.
         */
        public boolean historyTruncated;
        /** Subtrees left out of the duplication measurement as copies of other subtrees. */
        public List<MirrorTrees.Mirror> headMirrors = new ArrayList<MirrorTrees.Mirror>();
        public int headBare;
        public int headBroad;
        public int headSwallow;
        public int headSuppress;
        public int headCalls;
        public int headCommentLines;
        public int headTotalLines;
        public int headFunctions;
        public int headDecisions;
        public int headFunctionLines;
        /** Code lines per language at HEAD, so a language-scoped threshold can be applied. */
        public Map<String, Integer> languageLines = new HashMap<String, Integer>();

        /** The language holding the most code at HEAD, or empty when there is none. */
        public String dominantLanguage()
        {
            String best = "";
            int most = 0;
            for (Map.Entry<String, Integer> entry : languageLines.entrySet())
            {
                if (entry.getValue() > most)
                {
                    most = entry.getValue();
                    best = entry.getKey();
                }
            }
            return best;
        }
        public List<DuplicateDetector.CloneHit> headClones =
                new ArrayList<DuplicateDetector.CloneHit>();
        public Map<String, Integer> headDupByFile = new HashMap<String, Integer>();
        /** Index of the first commit that was recomputed; earlier ones came from the cache. */
        public int replayedFrom;
    }

    public static final class CancelledException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
    }

    private final Repository repository;
    private final PathMatcher excludes;
    private final Progress progress;

    public AnalysisEngine(Repository repository, PathMatcher excludes, Progress progress)
    {
        this.repository = repository;
        this.excludes = excludes;
        this.progress = progress;
    }

    /**
     * @param branchRef    branch to analyse, or empty for whatever the remote HEAD points at
     * @param cached       previously computed rows by sha; only rows outside the replay window
     *                     are reused
     * @param previousRunAt epoch millis of the last successful run, or 0 if there was none
     * @param analysisTime  wall clock for the run, which is what decides churn censoring:
     *                      the 14-day window closes in real time, not at HEAD's date, so a
     *                      repository that went quiet three months ago is fully observed
     */
    public Outcome analyse(String branchRef, Map<String, CommitStats> cached, long previousRunAt,
                           long analysisTime) throws IOException
    {
        Outcome outcome = new Outcome();
        ObjectId headId = resolveHead(branchRef);
        if (headId == null)
        {
            throw new IOException("Branch not found: "
                    + (branchRef == null || branchRef.isEmpty() ? "HEAD" : branchRef));
        }
        outcome.branch = branchRef == null || branchRef.isEmpty() ? defaultBranchName() : branchRef;

        RevWalk walk = new RevWalk(repository);
        try
        {
            List<RevCommit> chain = firstParentChain(walk, headId, outcome);
            if (chain.isEmpty())
            {
                throw new IOException("Repository has no commits");
            }
            RevCommit head = chain.get(chain.size() - 1);
            outcome.headSha = head.getName();
            outcome.headCommittedAt = commitTime(head);

            IdentityResolver identities = new IdentityResolver(readMailmap(head));
            Set<Integer> sampledIndices = sampleIndices(chain);
            int replayFrom = floorForSampledGaps(chain, cached, sampledIndices,
                    replayFloor(chain, cached, previousRunAt));
            outcome.replayedFrom = replayFrom;

            List<CommitStats> stats = new ArrayList<CommitStats>(chain.size());
            for (int i = 0; i < replayFrom; i++)
            {
                stats.add(cached.get(chain.get(i).getName()));
            }

            TreeState state = new TreeState();
            if (replayFrom > 0)
            {
                report("materialise", 0, 1);
                materialise(state, chain.get(replayFrom - 1));
            }

            int[] churnByIndex = new int[chain.size()];
            ChurnTracker churn =
                    new ChurnTracker(AnalysisConfig.CHURN_WINDOW_MS, analysisTime);
            DiffFormatter differ = newDiffFormatter();

            try
            {
                for (int i = replayFrom; i < chain.size(); i++)
                {
                    checkCancelled();
                    report("commits", i - replayFrom + 1, chain.size() - replayFrom);
                    RevCommit commit = chain.get(i);
                    RevCommit parent = i > 0 ? chain.get(i - 1) : null;
                    CommitStats row = replay(commit, parent, i, state, churn, churnByIndex,
                            differ, identities, sampledIndices.contains(i));
                    stats.add(row);
                }
            }
            finally
            {
                differ.close();
            }

            for (int i = replayFrom; i < stats.size(); i++)
            {
                CommitStats row = stats.get(i);
                row.churn = churnByIndex[i];
                row.churnCensored =
                        analysisTime - row.committedAt < AnalysisConfig.CHURN_WINDOW_MS;
            }

            // HEAD always gets a full static pass, sampled or not: the headline numbers and the
            // clone list on the report are all "as of HEAD".
            report("head", 0, 1);
            scanHead(state, outcome);

            outcome.commits = stats;
            collectAuthors(stats, identities, outcome);
            return outcome;
        }
        finally
        {
            walk.close();
        }
    }

    // ------------------------------------------------------------------ per commit

    private CommitStats replay(RevCommit commit, RevCommit parent, int index, TreeState state,
                               ChurnTracker churn, int[] churnByIndex, DiffFormatter differ,
                               IdentityResolver identities, boolean sampled) throws IOException
    {
        CommitStats row = new CommitStats();
        row.sha = commit.getName();
        row.subject = shorten(commit.getShortMessage(), 400);
        row.authorName = commit.getAuthorIdent() == null ? "" : commit.getAuthorIdent().getName();
        row.authorEmail =
                commit.getAuthorIdent() == null ? "" : commit.getAuthorIdent().getEmailAddress();
        row.authorKey = identities.keyFor(row.authorName, row.authorEmail);
        row.committedAt = commitTime(commit);

        int parentNormLines = state.normLineCount();
        List<FileChange> changes = collectChanges(differ, parent, commit, state);

        // Deleted ranges are what separates a move from a copy, so they are collected before
        // anything is classified.
        Map<String, List<int[]>> deletedRanges = new HashMap<String, List<int[]>>();
        Set<String> goneFromOldPath = new HashSet<String>();
        for (FileChange change : changes)
        {
            if (change.oldPath == null)
            {
                continue;
            }
            if (!change.oldPath.equals(change.newPath))
            {
                goneFromOldPath.add(change.oldPath);
            }
            List<int[]> ranges = new ArrayList<int[]>();
            for (Edit edit : change.edits)
            {
                if (edit.getEndA() > edit.getBeginA())
                {
                    ranges.add(new int[] { edit.getBeginA(), edit.getEndA() });
                }
            }
            deletedRanges.put(change.oldPath, ranges);
        }

        LineMix mix = new LineMix();
        CopyPasteClassifier.classify(changes, state, deletedRanges, goneFromOldPath, mix);
        row.added = mix.added();
        row.novel = mix.novel;
        row.copied = mix.copied;
        row.moved = mix.moved;
        row.deleted = mix.deleted;
        row.importCommit = parent == null
                || (row.added > AnalysisConfig.IMPORT_MIN_LINES
                    && row.added > AnalysisConfig.IMPORT_RATIO * parentNormLines);

        churn.advanceTo(row.committedAt);
        for (FileChange change : changes)
        {
            if (change.oldPath == null)
            {
                continue;
            }
            for (Edit edit : change.edits)
            {
                if (edit.getEndA() > edit.getBeginA())
                {
                    churn.recordDeleted(row.committedAt, change.oldPath, change.oldLines.norm,
                            edit.getBeginA(), edit.getEndA(), churnByIndex);
                }
            }
            if (change.newPath != null && !change.newPath.equals(change.oldPath))
            {
                churn.renamePath(change.oldPath, change.newPath);
            }
        }
        for (FileChange change : changes)
        {
            if (change.newPath == null)
            {
                continue;
            }
            for (Edit edit : change.edits)
            {
                if (edit.getEndB() > edit.getBeginB())
                {
                    churn.recordAdded(index, row.committedAt, change.newPath,
                            change.newLines.norm, edit.getBeginB(), edit.getEndB());
                }
            }
        }

        for (FileChange change : changes)
        {
            if (change.newPath != null)
            {
                state.put(change.newPath, change.newLines);
            }
            if (change.oldPath != null && !change.oldPath.equals(change.newPath))
            {
                state.remove(change.oldPath);
            }
        }

        if (sampled)
        {
            applyStatics(state, row);
        }
        return row;
    }

    private List<FileChange> collectChanges(DiffFormatter differ, RevCommit parent,
                                            RevCommit commit, TreeState state) throws IOException
    {
        RevTree parentTree = parent == null ? null : parent.getTree();
        List<DiffEntry> entries = differ.scan(parentTree, commit.getTree());
        List<FileChange> changes = new ArrayList<FileChange>();
        HistogramDiff diff = new HistogramDiff();

        for (DiffEntry entry : entries)
        {
            String oldPath = DiffEntry.DEV_NULL.equals(entry.getOldPath())
                    ? null : entry.getOldPath();
            String newPath = DiffEntry.DEV_NULL.equals(entry.getNewPath())
                    ? null : entry.getNewPath();
            boolean oldRelevant = oldPath != null && relevant(oldPath);
            boolean newRelevant = newPath != null && relevant(newPath);
            if (!oldRelevant && !newRelevant)
            {
                continue;
            }

            FileLines oldLines = FileLines.EMPTY;
            if (oldRelevant)
            {
                FileLines known = state.get(oldPath);
                oldLines = known != null ? known : FileLines.EMPTY;
            }
            FileLines newLines = newRelevant
                    ? readFileLines(entry.getNewId().toObjectId(), newPath)
                    : FileLines.EMPTY;

            EditList edits = diff.diff(NormSequence.CMP, new NormSequence(oldLines.norm),
                    new NormSequence(newLines.norm));
            changes.add(new FileChange(oldRelevant ? oldPath : null,
                    newRelevant ? newPath : null, oldLines, newLines, edits));
        }
        // Sorted so that the copy/move classification sees files in the same order every run.
        Collections.sort(changes, new java.util.Comparator<FileChange>()
        {
            @Override
            public int compare(FileChange a, FileChange b)
            {
                return key(a).compareTo(key(b));
            }

            private String key(FileChange c)
            {
                return c.newPath != null ? c.newPath : c.oldPath;
            }
        });
        return changes;
    }

    // ------------------------------------------------------------------ static metrics

    private void applyStatics(TreeState state, CommitStats row)
    {
        PatternScanner.Result patterns = scanPatterns(state);
        DuplicateDetector.Result duplicates = DuplicateDetector.detect(state);
        row.loc = state.codeLineCount();
        row.files = state.fileCount();
        row.dupLines = duplicates.duplicatedLines;
        row.dupClones = duplicates.cloneCount;
        row.dupMeasuredLines = duplicates.measuredLines;
        row.errSwallow = patterns.handlers();
        row.calls = patterns.calls;
        row.commentLines = state.commentLineCount();
        row.totalLines = state.totalLineCount();
        row.functions = patterns.functions;
        row.decisions = patterns.decisions;
        row.functionLines = patterns.functionLines;
    }

    private void scanHead(TreeState state, Outcome outcome)
    {
        PatternScanner.Result patterns = scanPatterns(state);
        DuplicateDetector.Result duplicates = DuplicateDetector.detect(state);
        outcome.headLoc = state.codeLineCount();
        outcome.headFiles = state.fileCount();
        outcome.headDupLines = duplicates.duplicatedLines;
        outcome.headDupClones = duplicates.cloneCount;
        outcome.headDupMeasuredLines = duplicates.measuredLines;
        outcome.headMirrors = duplicates.mirrors;
        outcome.headClones = duplicates.hits;
        outcome.headDupByFile = duplicates.byFile;
        outcome.headBare = patterns.bare;
        outcome.headBroad = patterns.broad;
        outcome.headSwallow = patterns.swallow;
        outcome.headSuppress = patterns.suppress;
        outcome.headCalls = patterns.calls;
        outcome.headCommentLines = state.commentLineCount();
        outcome.headTotalLines = state.totalLineCount();
        outcome.headFunctions = patterns.functions;
        outcome.headDecisions = patterns.decisions;
        outcome.headFunctionLines = patterns.functionLines;
        for (String path : state.sortedPaths())
        {
            String language = Language.of(path).name();
            Integer seen = outcome.languageLines.get(language);
            outcome.languageLines.put(language,
                    (seen == null ? 0 : seen) + state.get(path).code.size());
        }

        // Make sure the last plotted point is HEAD itself, so the trend never ends short.
        if (!outcome.commits.isEmpty())
        {
            CommitStats last = outcome.commits.get(outcome.commits.size() - 1);
            if (!last.sampled())
            {
                last.loc = outcome.headLoc;
                last.files = outcome.headFiles;
                last.dupLines = outcome.headDupLines;
                last.dupClones = outcome.headDupClones;
                last.dupMeasuredLines = outcome.headDupMeasuredLines;
                last.errSwallow = patterns.handlers();
                last.calls = outcome.headCalls;
                last.commentLines = outcome.headCommentLines;
                last.totalLines = outcome.headTotalLines;
                last.functions = outcome.headFunctions;
                last.decisions = outcome.headDecisions;
                last.functionLines = outcome.headFunctionLines;
            }
        }
    }

    private PatternScanner.Result scanPatterns(TreeState state)
    {
        PatternScanner.Result result = new PatternScanner.Result();
        for (String path : state.sortedPaths())
        {
            PatternScanner.scan(Language.of(path), state.get(path), result);
        }
        return result;
    }

    // ------------------------------------------------------------------ history plumbing

    private ObjectId resolveHead(String branchRef) throws IOException
    {
        if (branchRef != null && !branchRef.isEmpty())
        {
            ObjectId id = repository.resolve(Constants.R_HEADS + branchRef);
            return id != null ? id : repository.resolve(branchRef);
        }
        return repository.resolve(Constants.HEAD);
    }

    private String defaultBranchName()
    {
        try
        {
            String full = repository.getFullBranch();
            return full != null && full.startsWith(Constants.R_HEADS)
                    ? full.substring(Constants.R_HEADS.length()) : "HEAD";
        }
        catch (IOException e)
        {
            return "HEAD";
        }
    }

    /**
     * The newest {@link AnalysisConfig#MAX_COMMITS} commits of the first-parent history.
     *
     * <p>Sets {@link Outcome#historyTruncated} when the rail was reached, because every fact
     * on the report - the span, the commit count, the author list - is then about the window
     * rather than the repository, and a reader has no way to tell.</p>
     */
    private List<RevCommit> firstParentChain(RevWalk walk, ObjectId headId, Outcome outcome)
            throws IOException
    {
        List<RevCommit> chain = new ArrayList<RevCommit>();
        RevCommit current = walk.parseCommit(headId);
        while (current != null && chain.size() < AnalysisConfig.MAX_COMMITS)
        {
            chain.add(current);
            current = current.getParentCount() > 0
                    ? walk.parseCommit(current.getParent(0)) : null;
        }
        outcome.historyTruncated = current != null;
        Collections.reverse(chain);
        return chain;
    }

    /**
     * Index of the first commit that must be recomputed. See the class comment for why the
     * floor reaches back two churn windows rather than one.
     */
    private int replayFloor(List<RevCommit> chain, Map<String, CommitStats> cached,
                            long previousRunAt)
    {
        if (cached == null || cached.isEmpty())
        {
            return 0;
        }
        long oldestUncached = Long.MAX_VALUE;
        for (int i = 0; i < chain.size(); i++)
        {
            if (!cached.containsKey(chain.get(i).getName()))
            {
                oldestUncached = commitTime(chain.get(i));
                break;
            }
        }
        long censoredFloor = previousRunAt > 0
                ? previousRunAt - AnalysisConfig.CHURN_WINDOW_MS : Long.MAX_VALUE;
        long floor = Math.min(oldestUncached, censoredFloor);
        if (floor == Long.MAX_VALUE)
        {
            // Nothing new and no previous run to second-guess: still redo the last window so a
            // report regenerated later never claims a censored commit was clean.
            floor = commitTime(chain.get(chain.size() - 1));
        }
        floor -= AnalysisConfig.CHURN_WINDOW_MS;

        for (int i = 0; i < chain.size(); i++)
        {
            if (commitTime(chain.get(i)) >= floor)
            {
                return i;
            }
        }
        return chain.size();
    }

    /**
     * Which commits get a full static pass. A pure function of the whole chain.
     *
     * <p>This used to bucket only the replayed range, so an incremental run over 28 days
     * sampled every day while a full run over five years sampled every ninth - and the same
     * commit came out sampled in one and unsampled in the other. That changed which commit
     * {@code ReportBuilder} picked as its 90-day reference, so the trend delta moved with no
     * change to the code. Bucketing the entire chain makes the set depend on the history and
     * not on how much of it this run happened to touch.</p>
     *
     * <p><b>The set does move when the history grows past a multiple of
     * {@link AnalysisConfig#STATIC_SAMPLE_TARGET} distinct days.</b> The step widens, so which
     * commits are sampled is re-laid-out across the whole chain, and
     * {@link #floorForSampledGaps} then pulls the replay floor back far enough to compute
     * statics for the newly sampled ones - in practice a full replay of the history. Two
     * consequences worth knowing rather than being surprised by: that one run is as expensive
     * as a first run, and the trend's reference commit can shift by up to a day, so a delta
     * can move slightly without the code having changed. This is a cost, not an error; the
     * alternative is a sample set that depends on when the analysis happened to run.</p>
     */
    private Set<Integer> sampleIndices(List<RevCommit> chain)
    {
        long day = 24L * 60 * 60 * 1000;
        List<Integer> firstOfDay = new ArrayList<Integer>();
        long previousBucket = Long.MIN_VALUE;
        for (int i = 0; i < chain.size(); i++)
        {
            long bucket = commitTime(chain.get(i)) / day;
            if (bucket != previousBucket)
            {
                previousBucket = bucket;
                firstOfDay.add(i);
            }
        }

        int step = Math.max(1, (firstOfDay.size() + AnalysisConfig.STATIC_SAMPLE_TARGET - 1)
                / AnalysisConfig.STATIC_SAMPLE_TARGET);
        Set<Integer> sampled = new HashSet<Integer>();
        for (int k = 0; k < firstOfDay.size(); k += step)
        {
            sampled.add(firstOfDay.get(k));
        }
        if (!chain.isEmpty())
        {
            // The ends anchor every series: the first point and HEAD.
            sampled.add(0);
            sampled.add(chain.size() - 1);
        }
        return sampled;
    }

    /**
     * Pulls the replay floor back far enough to cover any sampled commit whose cached row has
     * no static metrics.
     *
     * <p>Without this the sampled set could be right while the data behind it was missing: a
     * commit sampled now but not when it was first analysed would sit in the cache with no LOC
     * or duplication figure, and the report would pick a different reference point than a full
     * run would. Rare - it takes the day count crossing a thinning threshold - and cheap to
     * rule out.</p>
     */
    private int floorForSampledGaps(List<RevCommit> chain, Map<String, CommitStats> cached,
                                    Set<Integer> sampled, int replayFrom)
    {
        if (cached == null || cached.isEmpty())
        {
            return replayFrom;
        }
        for (int i = 0; i < replayFrom; i++)
        {
            if (!sampled.contains(i))
            {
                continue;
            }
            CommitStats row = cached.get(chain.get(i).getName());
            if (row == null || !row.sampled())
            {
                return i;
            }
        }
        return replayFrom;
    }

    private void collectAuthors(List<CommitStats> stats, IdentityResolver identities,
                                Outcome outcome)
    {
        Map<String, Author> byKey = new HashMap<String, Author>();
        Set<String> rawIdentities = new HashSet<String>();
        for (CommitStats row : stats)
        {
            if (row == null)
            {
                continue;
            }
            rawIdentities.add(row.authorName + "|" + row.authorEmail);
            Author author = byKey.get(row.authorKey);
            if (author == null)
            {
                author = new Author();
                author.key = row.authorKey;
                author.name = identities.preferredName(row.authorKey, row.authorName);
                author.email = row.authorEmail;
                byKey.put(row.authorKey, author);
            }
            author.commits++;
            author.addedLines += row.added;
            author.identities.add(row.authorName + " <" + row.authorEmail + ">");
        }
        outcome.authors = IdentityResolver.mergeByDisplayName(byKey);
        outcome.rawIdentityCount = rawIdentities.size();
        outcome.identitySuspects = IdentityResolver.suspects(outcome.authors);
    }

    private void materialise(TreeState state, RevCommit commit) throws IOException
    {
        TreeWalk walk = new TreeWalk(repository);
        try
        {
            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            while (walk.next())
            {
                checkCancelled();
                String path = walk.getPathString();
                if (!relevant(path))
                {
                    continue;
                }
                FileLines lines = readFileLines(walk.getObjectId(0), path);
                if (!lines.code.isEmpty())
                {
                    state.put(path, lines);
                }
            }
        }
        finally
        {
            walk.close();
        }
    }

    private String readMailmap(RevCommit head)
    {
        try
        {
            TreeWalk walk = TreeWalk.forPath(repository, ".mailmap", head.getTree());
            if (walk == null)
            {
                return null;
            }
            try
            {
                ObjectLoader loader = repository.open(walk.getObjectId(0));
                if (loader.getSize() > AnalysisConfig.MAX_FILE_BYTES)
                {
                    return null;
                }
                return new String(loader.getCachedBytes(AnalysisConfig.MAX_FILE_BYTES),
                        StandardCharsets.UTF_8);
            }
            finally
            {
                walk.close();
            }
        }
        catch (IOException e)
        {
            return null;
        }
    }

    private FileLines readFileLines(ObjectId id, String path) throws IOException
    {
        if (id == null || ObjectId.zeroId().equals(id))
        {
            return FileLines.EMPTY;
        }
        ObjectLoader loader;
        try
        {
            loader = repository.open(id, Constants.OBJ_BLOB);
        }
        catch (IOException e)
        {
            return FileLines.EMPTY;
        }
        if (loader.getSize() > AnalysisConfig.MAX_FILE_BYTES)
        {
            return FileLines.EMPTY;
        }
        byte[] bytes = loader.getCachedBytes(AnalysisConfig.MAX_FILE_BYTES);
        if (RawText.isBinary(bytes))
        {
            return FileLines.EMPTY;
        }
        FileLines lines = LineNormalizer.parse(new String(bytes, StandardCharsets.UTF_8),
                Language.of(path));
        // A generated lookup table is dropped as if it had been excluded by path: it is not
        // code the team maintains, and near-identical siblings would dominate duplication.
        return lines.dataTable ? FileLines.EMPTY : lines;
    }

    private boolean relevant(String path)
    {
        return Language.of(path).isAnalysable() && !excludes.excludes(path);
    }

    private DiffFormatter newDiffFormatter()
    {
        DiffFormatter differ = new DiffFormatter(NullOutputStream.INSTANCE);
        differ.setRepository(repository);
        differ.setDetectRenames(true);
        differ.setContext(0);
        return differ;
    }

    private static long commitTime(RevCommit commit)
    {
        return commit.getCommitTime() * 1000L;
    }

    private static String shorten(String value, int max)
    {
        if (value == null)
        {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void report(String phase, int current, int total)
    {
        if (progress != null)
        {
            progress.report(phase, current, total);
        }
    }

    private void checkCancelled()
    {
        if (progress != null && progress.cancelled())
        {
            throw new CancelledException();
        }
    }
}
