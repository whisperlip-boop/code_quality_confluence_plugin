package co.bskim.confluence.codequality.analysis;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * When one file is a bundle of other files rather than a file.
 *
 * <p>Nothing is excluded on this verdict - it becomes a finding and the reader decides - so the
 * cost of a false positive is a wrong sentence rather than a hidden number. The cost of a false
 * negative is the same as today. That is why the rule is allowed to be this simple, and why
 * these fixtures still spend most of their effort on the shapes that must <em>not</em> be
 * called bundles: a module somebody copied wholesale is the finding the plugin exists for.</p>
 *
 * <p>Thresholds come from measurement - see {@link AnalysisConfig#BUNDLE_MIN_PARTNERS}.</p>
 */
public class BundleFileTest
{
    /** A concatenation of a whole directory, which is what checked-in build output looks like. */
    @Test
    public void aFileHoldingManyOtherFilesIsASuspect()
    {
        TreeState state = new TreeState();
        StringBuilder bundle = new StringBuilder();
        for (int i = 0; i < 30; i++)
        {
            String module = module(i, 20);
            put(state, String.format("src/mod%02d.js", i), module);
            bundle.append(module);
        }
        put(state, "dist-bundle.js", bundle.toString());

        List<DuplicateDetector.BundleSuspect> suspects = suspects(state);

        assertEquals("only the concatenation is a bundle", 1, suspects.size());
        DuplicateDetector.BundleSuspect worst = suspects.get(0);
        assertEquals("dist-bundle.js", worst.path);
        assertTrue("it must be recognised as spanning many files: " + worst.partners,
                worst.partners >= AnalysisConfig.BUNDLE_MIN_PARTNERS);
        assertTrue("most of it must be duplicated",
                worst.dupLines >= worst.fileLines / 2);
        assertTrue("and it names a few partners so the claim can be checked",
                !worst.examples.isEmpty());
    }

    /**
     * The shape that must never be swallowed: one module copied wholesale.
     *
     * <p>Most of the copy is duplicated, exactly as with a bundle - the difference is that it
     * mirrors one file rather than dozens.</p>
     */
    @Test
    public void aModuleCopiedWholesaleIsNotABundle()
    {
        TreeState state = new TreeState();
        String original = module(1, 300);
        put(state, "src/payment_service.js", original);
        put(state, "src/payment_service_v2.js", original);
        for (int i = 0; i < 20; i++)
        {
            put(state, String.format("src/other%02d.js", i), module(100 + i, 20));
        }

        assertTrue("a wholesale copy of one file is duplication, and must keep being reported",
                suspects(state).isEmpty());
    }

    /**
     * Nor may a family of structurally similar files be called a bundle.
     *
     * <p>typeorm's per-database query runners share blocks with eight siblings and netty's
     * channel classes with five; both are real duplication and both are well under the
     * threshold. This fixture sits in the same region.</p>
     */
    @Test
    public void aFamilyOfSimilarFilesIsNotABundle()
    {
        TreeState state = new TreeState();
        String shared = module(7, 40);
        for (int i = 0; i < 8; i++)
        {
            // Eight drivers, each mostly the same shape plus a little of its own.
            put(state, String.format("src/driver/driver%d.js", i), shared + module(200 + i, 10));
        }

        List<DuplicateDetector.BundleSuspect> suspects = suspects(state);
        assertTrue("eight siblings is duplication to report, not layout to discount: "
                + describe(suspects), suspects.isEmpty());
    }

    /** A file that merely shares a block with many others is not mostly a copy of them. */
    @Test
    public void sharingOneBlockWidelyIsNotABundle()
    {
        TreeState state = new TreeState();
        String common = module(9, 8);
        for (int i = 0; i < 30; i++)
        {
            put(state, String.format("src/mod%02d.js", i), common + module(300 + i, 30));
        }
        // Large, and its own: the shared preamble is a small part of it.
        put(state, "src/big_helper.js", common + module(999, 400));

        List<DuplicateDetector.BundleSuspect> suspects = suspects(state);
        assertTrue("a shared preamble does not make a file a bundle: " + describe(suspects),
                suspects.isEmpty());
    }

    /** Too small for "most of it lives elsewhere" to be worth saying. */
    @Test
    public void aSmallFileIsNotASuspect()
    {
        TreeState state = new TreeState();
        StringBuilder bundle = new StringBuilder();
        for (int i = 0; i < 30; i++)
        {
            String module = module(i, 2);
            put(state, String.format("src/mod%02d.js", i), module);
            bundle.append(module);
        }
        put(state, "tiny-bundle.js", bundle.toString());

        assertTrue("under the line floor there is nothing worth reporting",
                suspects(state).isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static List<DuplicateDetector.BundleSuspect> suspects(TreeState state)
    {
        return DuplicateDetector.bundleSuspects(state, DuplicateDetector.detect(state));
    }

    private static void put(TreeState state, String path, String content)
    {
        state.put(path, LineNormalizer.parse(content, Language.JAVASCRIPT));
    }

    /** Distinct, long-enough JavaScript lines so normalisation keeps every one. */
    private static String module(int seed, int lines)
    {
        StringBuilder out = new StringBuilder();
        out.append("export function handler_").append(seed).append("(request, context) {\n");
        for (int i = 0; i < lines; i++)
        {
            out.append("  const value_").append(seed).append('_').append(i)
                    .append(" = computeStep(request, context, ").append(i).append(");\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private static String describe(List<DuplicateDetector.BundleSuspect> suspects)
    {
        List<String> names = new ArrayList<String>();
        for (DuplicateDetector.BundleSuspect suspect : suspects)
        {
            names.add(suspect.path + " (" + suspect.partners + " partners)");
        }
        return names.toString();
    }
}
