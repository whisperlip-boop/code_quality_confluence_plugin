import co.bskim.confluence.codequality.analysis.AnalysisConfig;
import co.bskim.confluence.codequality.analysis.AnalysisEngine;
import co.bskim.confluence.codequality.analysis.CommitStats;
import co.bskim.confluence.codequality.analysis.PathMatcher;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * How often bulk-import detection fires, and on what.
 *
 * <p>The rule is "added more than 200 lines and more than half of what the parent held", and
 * the worry is that a young repository trips it on ordinary work: when the parent holds 300
 * lines, half of it is 150, so any 250-line commit qualifies. An import is excluded from every
 * ratio the report shows, so a false positive removes real work from the denominator - and on a
 * five-commit repository that is most of the evidence.</p>
 *
 * <p>Needs full history, so the clones cannot be shallow.</p>
 *
 *   javac -cp target/code-quality-1.0.0.jar -d /tmp/cq-probe tools/ImportProbe.java
 *   java -cp target/code-quality-1.0.0.jar:$SLF4J:/tmp/cq-probe ImportProbe /tmp/hist
 */
public final class ImportProbe
{
    private static final SimpleDateFormat WHEN = new SimpleDateFormat("yyyy-MM-dd");

    public static void main(String[] args) throws Exception
    {
        File root = new File(args[0]);
        File[] clones = root.listFiles();
        if (clones == null)
        {
            System.err.println("no clones in " + root);
            return;
        }
        java.util.Arrays.sort(clones);

        System.out.println("repo\tcommits\timports\trootOnly\tnonRoot\tmostlyMoved"
                + "\tinFirst20");
        List<String> detail = new ArrayList<String>();

        for (File clone : clones)
        {
            File gitDir = new File(clone, ".git");
            if (!gitDir.isDirectory())
            {
                continue;
            }
            try
            {
                measure(clone.getName(), gitDir, detail);
            }
            catch (Exception e)
            {
                System.out.println(clone.getName() + "\tERROR\t"
                        + e.getClass().getSimpleName() + "\t" + e.getMessage());
            }
        }

        System.out.println();
        System.out.println("-- every non-root import, oldest first --");
        for (String line : detail)
        {
            System.out.println(line);
        }
    }

    private static void measure(String name, File gitDir, List<String> detail) throws Exception
    {
        Repository repo = new FileRepositoryBuilder().setGitDir(gitDir).build();
        try
        {
            AnalysisEngine engine = new AnalysisEngine(repo,
                    new PathMatcher(AnalysisConfig.DEFAULT_EXCLUDES, ""), null);
            AnalysisEngine.Outcome outcome = engine.analyse("",
                    Collections.<String, CommitStats>emptyMap(), 0, System.currentTimeMillis());

            List<CommitStats> commits = outcome.commits;
            int imports = 0;
            int nonRoot = 0;
            int mostlyMoved = 0;
            int inFirst20 = 0;

            for (int i = 0; i < commits.size(); i++)
            {
                CommitStats row = commits.get(i);
                if (row == null || !row.importCommit)
                {
                    continue;
                }
                imports++;
                if (i == 0)
                {
                    continue;
                }
                nonRoot++;
                if (i < 20)
                {
                    inFirst20++;
                }
                // The question the data has to answer: was this code new to the repository, or
                // did it already exist somewhere else in it? The classifier already knows -
                // the import rule just does not ask.
                double novelShare = row.added == 0 ? 0 : (double) row.novel / row.added;
                boolean moved = novelShare < 0.5;
                if (moved)
                {
                    mostlyMoved++;
                }
                detail.add(String.format(
                        "%-28s #%-5d %s  parent %7d  added %6d  novel %6d  moved %6d (%3.0f%%)"
                        + "  copied %5d  %-9s %s",
                        name, i, WHEN.format(new Date(row.committedAt)), row.parentLines,
                        row.added, row.novel, row.moved,
                        row.added == 0 ? 0.0 : 100.0 * row.moved / row.added, row.copied,
                        moved ? "RELOCATED" : "new", shorten(row.subject)));
            }

            System.out.printf("%s\t%d\t%d\t%d\t%d\t%d\t%d%n", name, commits.size(), imports,
                    imports - nonRoot, nonRoot, mostlyMoved, inFirst20);
        }
        finally
        {
            repo.close();
        }
    }

    private static String shorten(String subject)
    {
        if (subject == null)
        {
            return "";
        }
        String one = subject.replace('\t', ' ').replace('\n', ' ');
        return one.length() > 60 ? one.substring(0, 57) + "..." : one;
    }
}
