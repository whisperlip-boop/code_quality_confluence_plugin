import co.bskim.confluence.codequality.analysis.AnalysisConfig;
import co.bskim.confluence.codequality.analysis.DuplicateDetector;
import co.bskim.confluence.codequality.analysis.FileLines;
import co.bskim.confluence.codequality.analysis.Language;
import co.bskim.confluence.codequality.analysis.LineNormalizer;
import co.bskim.confluence.codequality.analysis.PathMatcher;
import co.bskim.confluence.codequality.analysis.TreeState;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Duplication ratio at HEAD for a directory of clones, so an absolute threshold can be set
 * from a real distribution instead of a guess.
 *
 * HEAD only, so the clones can be shallow: the ratio this measures does not need history.
 * Uses the same detector and the same exclusion list as the plugin - a threshold derived with
 * different settings would not transfer.
 *
 *   java -cp target/code-quality-1.0.0.jar:<slf4j>:/tmp/cq-probe CohortProbe /tmp/cohort py
 */
public final class CohortProbe
{
    public static void main(String[] args) throws Exception
    {
        File root = new File(args[0]);
        Language want = "py".equals(args[1]) ? Language.PYTHON
                : "java".equals(args[1]) ? Language.JAVA : Language.JAVASCRIPT;

        File[] clones = root.listFiles();
        if (clones == null)
        {
            System.err.println("no clones in " + root);
            return;
        }
        java.util.Arrays.sort(clones);
        // topLang/topLoc are the guard: square/moshi came out at 2 Java lines because it is a
        // Kotlin project, and a plain LOC floor drops that silently. A cohort that quietly
        // shrinks is a cohort whose n is a lie.
        System.out.println(
                "repo\tfiles\tloc\tdupLines\tratioPct\tclones\ttopExt\ttopExtFiles\tmirrors");

        for (File clone : clones)
        {
            File gitDir = new File(clone, ".git");
            if (!gitDir.isDirectory() && !new File(clone, "config").isFile())
            {
                continue;
            }
            try
            {
                measure(clone, gitDir.isDirectory() ? gitDir : clone, want,
                        args.length > 2 && clone.getName().contains(args[2]));
            }
            catch (Exception e)
            {
                System.out.println(clone.getName() + "\tERROR\t" + e.getClass().getSimpleName()
                        + "\t" + String.valueOf(e.getMessage()).replace('\t', ' '));
            }
        }
    }

    /** Extensions that say what a repository is written in. */
    private static final java.util.Set<String> SOURCE_EXTENSIONS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "java", "kt", "kts", "scala", "groovy", "py", "pyi", "js", "jsx", "mjs",
                    "cjs", "ts", "tsx", "go", "rs", "rb", "php", "c", "h", "cc", "cpp", "hpp",
                    "cs", "swift", "m", "mm", "dart", "ex", "exs", "erl", "clj", "lua", "pl",
                    "sh", "vue", "svelte"));

    /**
     * The most common source extension at HEAD, by file count.
     *
     * <p>Counted over extensions rather than over {@code Language}, because the point is to
     * notice a repository written in something the plugin cannot read - and to that check,
     * every such language is invisible.</p>
     */
    private static String[] dominantExtension(Repository repo) throws Exception
    {
        java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
        RevWalk walk = new RevWalk(repo);
        TreeWalk tree = new TreeWalk(repo);
        try
        {
            tree.addTree(walk.parseCommit(repo.resolve(Constants.HEAD)).getTree());
            tree.setRecursive(true);
            while (tree.next())
            {
                String path = tree.getPathString();
                int dot = path.lastIndexOf('.');
                if (dot < 0)
                {
                    continue;
                }
                String ext = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                if (!SOURCE_EXTENSIONS.contains(ext))
                {
                    continue;
                }
                Integer n = counts.get(ext);
                counts.put(ext, n == null ? 1 : n + 1);
            }
        }
        finally
        {
            tree.close();
            walk.close();
        }
        String best = "-";
        int bestCount = 0;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet())
        {
            if (e.getValue() > bestCount
                    || (e.getValue() == bestCount && e.getKey().compareTo(best) < 0))
            {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return new String[] { best, String.valueOf(bestCount) };
    }

    private static void measure(File clone, File gitDir, Language want, boolean detail)
            throws Exception
    {
        Repository repo = new FileRepositoryBuilder().setGitDir(gitDir).build();
        PathMatcher excludes = new PathMatcher(AnalysisConfig.DEFAULT_EXCLUDES, "");
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
        int loc = dup.measuredLines;
        double ratio = loc == 0 ? 0 : 100.0 * dup.duplicatedLines / loc;
        StringBuilder mirrored = new StringBuilder();
        for (co.bskim.confluence.codequality.analysis.MirrorTrees.Mirror m : dup.mirrors)
        {
            mirrored.append(mirrored.length() > 0 ? "; " : "")
                    .append(m.mirror).append("~=").append(m.original)
                    .append('(').append(m.droppedLines).append(" lines)");
        }

        // The dominant source extension, counted over the whole tree - not the dominant
        // Language, which cannot see a language the plugin does not parse. square/moshi came
        // out at 2 Java lines because it is Kotlin, and every unparsed language looks like
        // "mostly whatever else is in the repo". A cohort that silently shrinks lies about n.
        String[] top = dominantExtension(repo);

        System.out.printf("%s\t%d\t%d\t%d\t%.2f\t%d\t%s\t%d\t%s%n", clone.getName(),
                state.fileCount(), loc, dup.duplicatedLines, ratio, dup.cloneCount,
                top[0], Integer.parseInt(top[1]),
                mirrored.length() == 0 ? "-" : mirrored.toString());
        if (detail)
        {
            int shown = 0;
            for (DuplicateDetector.CloneHit hit : dup.hits)
            {
                if (shown++ >= 10)
                {
                    break;
                }
                System.out.printf("    %4d lines  %s:%d <-> %s:%d%n", hit.lines,
                        hit.fileA, hit.lineA, hit.fileB, hit.lineB);
            }
            int biggest = 0;
            String biggestFile = "";
            for (java.util.Map.Entry<String, Integer> e : dup.byFile.entrySet())
            {
                if (e.getValue() > biggest)
                {
                    biggest = e.getValue();
                    biggestFile = e.getKey();
                }
            }
            System.out.printf("    worst file: %s (%d dup lines)%n", biggestFile, biggest);
        }
        repo.close();
    }
}
