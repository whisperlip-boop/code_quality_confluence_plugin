package co.bskim.confluence.codequality.analysis;

/** Tuning constants. Changing any of them must come with an {@link #ALGO_VERSION} bump. */
public final class AnalysisConfig
{
    /**
     * Stored on every cached row. Trend lines are only comparable within one version, so a
     * bump forces every commit to be recomputed instead of silently mixing two algorithms.
     */
    public static final int ALGO_VERSION = 2;

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

    /** Static metrics are sampled so that a long history stays affordable. */
    public static final int STATIC_SAMPLE_TARGET = 40;

    /** Safety rails so one huge repository cannot take the instance down. */
    public static final int MAX_COMMITS = 20000;
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_INDEX_BUCKET = 64;
    public static final int MAX_CLONE_PAIRS = 400;

    /** Paths that are checked in but not written by the team. */
    public static final String[] DEFAULT_EXCLUDES = {
            "**/node_modules/**", "**/vendor/**", "**/third_party/**", "**/thirdparty/**",
            "**/dist/**", "**/build/**", "**/target/**", "**/out/**", "**/.venv/**",
            "**/venv/**", "**/site-packages/**", "**/__pycache__/**", "**/generated/**",
            "**/*.min.js", "**/*.bundle.js", "**/*_pb2.py", "**/*.pb.go", "**/migrations/**",
            // Fixtures and snapshots are duplicated on purpose; counting them makes the
            // duplication ratio a measure of the repository's shape, not its quality.
            "**/fixtures/**", "**/__fixtures__/**", "**/testdata/**", "**/test-data/**",
            "**/__snapshots__/**", "**/*.snap", "**/*.generated.*", "**/*_generated.*",
            "**/*.g.dart", "**/*.pb.py", "**/*_pb.js", "**/*.designer.cs",
            // Tests, docs and examples are out of scope, and not as a convenience: they are
            // repetitive by design, so leaving them in makes the duplication ratio a measure
            // of how many tutorial files a project ships. Measured across 20 public Python
            // repositories, including them put fastapi at 27% (docs_src/tutorial00N.py) and
            // black at 21% (tests/data). Without them the same cohort lands in single digits,
            // which is the distribution a threshold can actually be read against. Test quality
            // is a separate question with its own metrics.
            "**/test/**", "**/tests/**", "**/testing/**", "**/spec/**", "**/specs/**",
            "**/test_*.py", "**/*_test.py", "**/*_tests.py", "**/conftest.py",
            "**/*Test.java", "**/*Tests.java", "**/*IT.java", "**/*ITCase.java",
            "**/*.test.js", "**/*.test.ts", "**/*.test.jsx", "**/*.test.tsx",
            "**/*.spec.js", "**/*.spec.ts", "**/*.spec.jsx", "**/*.spec.tsx",
            "**/examples/**", "**/example/**", "**/samples/**", "**/sample/**",
            "**/docs/**", "**/doc/**", "**/docs_src/**", "**/benchmarks/**", "**/bench/**",
            "**/profiling/**", "**/profile/**"
    };

    private AnalysisConfig()
    {
    }
}
