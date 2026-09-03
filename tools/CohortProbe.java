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
        System.out.println("repo\tfiles\tloc\tdupLines\tratioPct\tclones");

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
        int loc = state.codeLineCount();
        double ratio = loc == 0 ? 0 : 100.0 * dup.duplicatedLines / loc;
        System.out.printf("%s\t%d\t%d\t%d\t%.2f\t%d%n", clone.getName(), state.fileCount(),
                loc, dup.duplicatedLines, ratio, dup.cloneCount);
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
