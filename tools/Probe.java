import co.bskim.confluence.codequality.analysis.AnalysisConfig;
import co.bskim.confluence.codequality.analysis.AnalysisEngine;
import co.bskim.confluence.codequality.analysis.PathMatcher;
import co.bskim.confluence.codequality.analysis.Thresholds;
import co.bskim.confluence.codequality.service.ReportBuilder;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.PrintWriter;
import java.util.Collections;

/**
 * Runs the analysis engine against a bare clone outside Confluence and dumps the report JSON.
 *
 * This exists to check the Java reimplementation against the Python proof of concept: the
 * classifier, the churn tracker and the duplicate detector all had reference numbers from
 * captureV, and a rewrite that quietly disagrees with them is a rewrite that broke something.
 *
 *   javac -cp target/code-quality-1.0.0.jar -d /tmp/cq-probe tools/Probe.java
 *   java -cp target/code-quality-1.0.0.jar:/tmp/cq-probe Probe /tmp/captureV.git out.json
 */
public final class Probe
{
    public static void main(String[] args) throws Exception
    {
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(new File(args[0])).build();
        long started = System.currentTimeMillis();

        AnalysisEngine engine = new AnalysisEngine(repository,
                new PathMatcher(AnalysisConfig.DEFAULT_EXCLUDES, ""),
                new AnalysisEngine.Progress()
                {
                    @Override
                    public void report(String phase, int current, int total)
                    {
                        if (total <= 1 || current == total || current % 50 == 0)
                        {
                            System.err.println("  " + phase + " " + current + "/" + total);
                        }
                    }

                    @Override
                    public boolean cancelled()
                    {
                        return false;
                    }
                });

        AnalysisEngine.Outcome outcome = engine.analyse("",
                Collections.emptyMap(), 0L, System.currentTimeMillis());
        String json = ReportBuilder.build("captureV",
                "https://github.com/whisperlip-boop/captureV.git", outcome,
                new Thresholds(), System.currentTimeMillis());

        PrintWriter out = new PrintWriter(args[1], "UTF-8");
        out.print(json);
        out.close();
        repository.close();

        System.err.println("elapsed " + (System.currentTimeMillis() - started) + " ms");
        System.err.println("wrote " + args[1]);
    }
}
