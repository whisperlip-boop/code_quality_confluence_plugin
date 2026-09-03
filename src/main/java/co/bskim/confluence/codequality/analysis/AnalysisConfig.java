package co.bskim.confluence.codequality.analysis;

/** Tuning constants. Changing any of them must come with an {@link #ALGO_VERSION} bump. */
public final class AnalysisConfig
{
    /**
     * Stored on every cached row. Trend lines are only comparable within one version, so a
     * bump forces every commit to be recomputed instead of silently mixing two algorithms.
     */
    public static final int ALGO_VERSION = 3;

    /** Minimum matching run for a block to count as copied or moved. Three killed the noise. */
    public static final int RUN = 3;

    /** Lines shorter than this are dropped before any matching. */
    public static final int MIN_LINE_LENGTH = 12;

    /** Duplicate detection: a clone must be at least this many lines and this many tokens. */
    public static final int DUP_MIN_LINES = 5;
    public static final int DUP_MIN_TOKENS = 50;

    /** Churn observation window. */
    public static final long CHURN_WINDOW_MS = 14L * 24 * 60 * 60 * 1000;

    /** Commits inside this trailing window are re-derived on every run, because new commits
     *  can still change their churn and their censoring flag. */
    public static final long INCREMENTAL_REPLAY_MS = 2 * CHURN_WINDOW_MS;

    /** A commit adding more than this share of its parent's code is treated as a bulk import. */
    public static final double IMPORT_RATIO = 0.5;
    public static final int IMPORT_MIN_LINES = 200;

    /**
     * Most sampled points in a static-metric series.
     *
     * <p>Used directly. The old code multiplied this by five at the call site, so the constant
     * said 40 and the behaviour was 200 - a name that lies is worse than no name.</p>
     */
    public static final int STATIC_SAMPLE_TARGET = 200;

    /** Safety rails so one huge repository cannot take the instance down. */
    public static final int MAX_COMMITS = 20000;
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    /**
     * Ceiling on the n-gram index, counted in window locations - roughly one per normalised
     * line of the tree.
     *
     * <p>This replaced a per-bucket cap that silently discarded locations once a window had
     * been seen 64 times. That cap made the result depend on the order files entered the index,
     * and a full run inserts files as commits touch them while an incremental run materialises
     * a tree in path order - so the same commit could be classified as a copy by one and a move
     * by the other. No bounded-bucket scheme fixes it either: whether a window counts as
     * boilerplate then depends on the history the index went through rather than on the tree it
     * currently holds.</p>
     *
     * <p>So the index keeps every location, and a tree too large to index fails the analysis
     * with a message saying so. A wrong number that looks right is worse than a run that stops
     * and tells you to narrow the exclusions.</p>
     */
    public static int maxIndexEntries()
    {
        long budget = (long) (Runtime.getRuntime().maxMemory() * INDEX_HEAP_SHARE);
        long allowed = budget / INDEX_BYTES_PER_ENTRY;
        return (int) Math.max(MIN_INDEX_ENTRIES, Math.min(MAX_INDEX_ENTRIES_CEILING, allowed));
    }

    /**
     * Measured cost of one index entry, in bytes.
     *
     * <p>Not an estimate: {@code HashMap<Long, long[]>} with one entry per window comes to
     * 92-95 bytes across 200k, 500k and 1M entries of all-distinct windows, and less when
     * windows repeat because they share a bucket. Rounded up, and it is the all-distinct case
     * that has to fit.</p>
     */
    private static final int INDEX_BYTES_PER_ENTRY = 96;

    /**
     * How much of the heap the index may take.
     *
     * <p>The cap used to be a flat 3,000,000 entries, which is about 285MB - on the 1GB heap a
     * Confluence container ships with, that is over a quarter of the instance's memory spent on
     * one reporting job, and unlike {@code IndexTooLargeException} an OutOfMemoryError does not
     * fail politely: it takes the node with it. The reason the cap exists at all is that
     * stopping beats reporting a wrong number, and an OOM is not stopping.</p>
     *
     * <p>Derived from the running heap rather than fixed, so a 1GB instance refuses what a 4GB
     * one accepts instead of both guessing. One index entry is roughly one line of analysable
     * code, so 10% of a 1GB heap is around a million lines.</p>
     */
    private static final double INDEX_HEAP_SHARE = 0.10;

    private static final int MIN_INDEX_ENTRIES = 250_000;
    private static final int MAX_INDEX_ENTRIES_CEILING = 8_000_000;
    public static final int MAX_CLONE_PAIRS = 400;

    /** Paths that are checked in but not written by the team. */
    public static final String[] DEFAULT_EXCLUDES = {
            "**/node_modules/**", "**/vendor/**", "**/third_party/**", "**/thirdparty/**",
            "**/dist/**", "**/build/**", "**/target/**", "**/out/**", "**/.venv/**",
            "**/venv/**", "**/site-packages/**", "**/__pycache__/**", "**/generated/**",
            "**/*.min.js", "**/*.bundle.js", "**/*_pb2.py", "**/*.pb.go", "**/migrations/**",
            // Checked-in build output. moment ships min/locales.js - every locale file
            // concatenated into one - which on its own put the repository at 50% duplicated:
            // the ratio was measuring its release process. dist/ and build/ were already here;
            // min/ was the gap, and mirror detection cannot see this one because the copy is a
            // single file rather than a subtree.
            "**/min/**", "**/*-min.js", "**/*.min.mjs", "**/coverage/**", "**/lib-cov/**",
            "**/.next/**", "**/es/**", "**/umd/**", "**/amd/**",
            // Fixtures and snapshots are duplicated on purpose; counting them makes the
            // duplication ratio a measure of the repository's shape, not its quality.
            "**/fixtures/**", "**/__fixtures__/**", "**/testdata/**", "**/test-data/**",
            "**/__snapshots__/**", "**/*.snap", "**/*.generated.*", "**/*_generated.*",
            "**/*.g.dart", "**/*.pb.py", "**/*_pb.js", "**/*.designer.cs",
            // Tests, docs and examples are out of scope, and not as a convenience: they are
            // repetitive by design, so leaving them in makes the duplication ratio a measure
            // of how many tutorial files a project ships. Including them put fastapi at 27%
            // (docs_src/tutorial00N.py) and black at 21% (tests/data).
            //
            // It does NOT bring the cohort into single digits, which an earlier version of
            // this comment claimed. Across 38 Python repositories the median is 2.6% and the
            // 90th percentile 11.7%, and fastapi is still 25.1% - which is real: its eight
            // HTTP-verb methods repeat the same 380-line Annotated/Doc parameter block by
            // hand. Excluding tests removes an artefact; it does not remove duplication.
            // Test quality is a separate question with its own metrics.
            "**/test/**", "**/tests/**", "**/testing/**", "**/spec/**", "**/specs/**",
            "**/test_*.py", "**/*_test.py", "**/*_tests.py", "**/conftest.py",
            "**/*Test.java", "**/*Tests.java", "**/*IT.java", "**/*ITCase.java",
            "**/*.test.js", "**/*.test.ts", "**/*.test.jsx", "**/*.test.tsx",
            "**/*.spec.js", "**/*.spec.ts", "**/*.spec.jsx", "**/*.spec.tsx",
            "**/examples/**", "**/example/**", "**/samples/**", "**/sample/**",
            "**/docs/**", "**/doc/**", "**/docs_src/**", "**/benchmarks/**", "**/bench/**",
            // benchmark singular was missing while its two plurals were here, which is how
            // Guava's guava-tests/benchmark tree stayed in the measurement.
            "**/benchmark/**", "**/profiling/**", "**/profile/**",
            "**/demo/**", "**/demos/**", "**/playground/**", "**/playgrounds/**"
    };

    private AnalysisConfig()
    {
    }
}
