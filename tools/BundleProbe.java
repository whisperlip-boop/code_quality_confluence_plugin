import co.bskim.confluence.codequality.analysis.AnalysisConfig;
import co.bskim.confluence.codequality.analysis.DuplicateDetector;
import co.bskim.confluence.codequality.analysis.FileLines;
import co.bskim.confluence.codequality.analysis.Language;
import co.bskim.confluence.codequality.analysis.LineNormalizer;
import co.bskim.confluence.codequality.analysis.PathMatcher;
import co.bskim.confluence.codequality.analysis.TreeState;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Looks for whether a checked-in bundle is separable from an ordinary duplicated file.
 *
 * <p>A bundle - {@code moment.js}, {@code min/locales.js} - holds the content of many other
 * files, so most of it is duplicated and its clone partners are numerous. A copied module also
 * has most of itself duplicated, but against one partner, and that one must keep being
 * reported: it is the finding the plugin exists for.</p>
 *
 * <p>This prints both numbers for the worst files in each repository so a threshold can be read
 * off real data. If the two shapes do not separate, the answer is to add no feature.</p>
 *
 *   javac -cp target/code-quality-1.0.0.jar -d /tmp/cq-probe tools/BundleProbe.java
 *   java -cp target/code-quality-1.0.0.jar:$SLF4J:/tmp/cq-probe BundleProbe /tmp/cohort-js js
 */
public final class BundleProbe
{
    /** Only files with at least this share of themselves duplicated are worth printing. */
    private static final double MIN_SHARE = 0.5;
    private static final int MIN_LINES = 100;
    private static final int PER_REPO = 4;

    private static final class Row
    {
        String repo;
        String path;
        int fileLines;
        int dupLines;
        int partners;
        double share;
    }

    public static void main(String[] args) throws Exception
    {
        File root = new File(args[0]);
        Language want = "py".equals(args[1]) ? Language.PYTHON
                : "java".equals(args[1]) ? Language.JAVA : Language.JAVASCRIPT;
        boolean ignoreExcludes = args.length > 2 && "raw".equals(args[2]);

        File[] clones = root.listFiles();
        if (clones == null)
        {
            System.err.println("no clones in " + root);
            return;
        }
        java.util.Arrays.sort(clones);

        List<Row> rows = new ArrayList<Row>();
        for (File clone : clones)
        {
            File gitDir = new File(clone, ".git");
            if (!gitDir.isDirectory())
            {
                continue;
            }
            try
            {
                collect(clone.getName(), gitDir, want, ignoreExcludes, rows);
            }
            catch (Exception e)
            {
                System.out.println("# " + clone.getName() + " failed: "
                        + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }

        Collections.sort(rows, new Comparator<Row>()
        {
            @Override
            public int compare(Row a, Row b)
            {
                return b.partners - a.partners;
            }
        });
        System.out.println("repo\tfile\tfileLines\tdupLines\tselfShare\tpartners");
        for (Row row : rows)
        {
            System.out.printf("%s\t%s\t%d\t%d\t%.2f\t%d%n", row.repo, row.path, row.fileLines,
                    row.dupLines, row.share, row.partners);
        }
    }

    private static void collect(String repoName, File gitDir, Language want,
                                boolean ignoreExcludes, List<Row> rows) throws Exception
    {
        Repository repo = new FileRepositoryBuilder().setGitDir(gitDir).build();
        try
        {
            PathMatcher excludes = ignoreExcludes
                    ? new PathMatcher(new String[0], "")
                    : new PathMatcher(AnalysisConfig.DEFAULT_EXCLUDES, "");
            TreeState state = new TreeState();

            RevWalk walk = new RevWalk(repo);
            TreeWalk tree = new TreeWalk(repo);
            try
            {
                tree.addTree(walk.parseCommit(repo.resolve(Constants.HEAD)).getTree());
                tree.setRecursive(true);
                while (tree.next())
                {
                    String path = tree.getPathString();
                    if (Language.of(path) != want || excludes.excludes(path))
                    {
                        continue;
                    }
                    ObjectLoader loader = repo.open(tree.getObjectId(0), Constants.OBJ_BLOB);
                    if (loader.getSize() > AnalysisConfig.MAX_FILE_BYTES)
                    {
                        continue;
                    }
                    byte[] bytes = loader.getCachedBytes(AnalysisConfig.MAX_FILE_BYTES);
                    if (RawText.isBinary(bytes))
                    {
                        continue;
                    }
                    FileLines lines = LineNormalizer.parse(
                            new String(bytes, StandardCharsets.UTF_8), want);
                    if (!lines.code.isEmpty() && !lines.dataTable)
                    {
                        state.put(path, lines);
                    }
                }
            }
            finally
            {
                tree.close();
                walk.close();
            }

            DuplicateDetector.Result dup = DuplicateDetector.detect(state);
            List<Row> mine = new ArrayList<Row>();
            // The product's own verdict, not a reimplementation of it: what this prints is
            // exactly what the report would say.
            for (DuplicateDetector.BundleSuspect suspect
                    : DuplicateDetector.bundleSuspects(state, dup))
            {
                Row row = new Row();
                row.repo = repoName;
                row.path = suspect.path;
                row.fileLines = suspect.fileLines;
                row.dupLines = suspect.dupLines;
                row.partners = suspect.partners;
                row.share = (double) suspect.dupLines / suspect.fileLines;
                mine.add(row);
            }
            Collections.sort(mine, new Comparator<Row>()
            {
                @Override
                public int compare(Row a, Row b)
                {
                    return b.partners - a.partners;
                }
            });
            rows.addAll(mine.subList(0, Math.min(PER_REPO, mine.size())));
        }
        finally
        {
            repo.close();
        }
    }
}
