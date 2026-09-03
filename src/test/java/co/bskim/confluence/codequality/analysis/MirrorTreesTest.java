package co.bskim.confluence.codequality.analysis;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Both directions matter here, and the second one more.
 *
 * <p>Firing wrongly is the dangerous failure: this feature removes lines from the duplication
 * measurement, so a false positive hides exactly what the plugin exists to find. The tests
 * below therefore spend more effort on trees that must <em>not</em> be called mirrors than on
 * the one that must.</p>
 */
public class MirrorTreesTest
{
    /** Guava's shape: two flavours of one library at the same relative paths. */
    @Test
    public void twoFlavoursOfTheSameLibraryAreAMirror()
    {
        TreeState state = new TreeState();
        for (int i = 0; i < 40; i++)
        {
            String body = classBody("Widget" + i, 30);
            put(state, "guava/src/com/acme/Widget" + i + ".java", body);
            put(state, "android/guava/src/com/acme/Widget" + i + ".java", body);
        }

        MirrorTrees.Result result = MirrorTrees.detect(state);

        assertEquals(1, result.mirrors.size());
        MirrorTrees.Mirror mirror = result.mirrors.get(0);
        // The roots are the prefixes the shared relative paths hang off, which is where the
        // two trees actually diverge: guava and android/guava, not their src directories.
        assertEquals("guava", mirror.original);
        assertEquals("android/guava", mirror.mirror);
        assertEquals(40, mirror.sharedPaths);
        assertTrue("the dropped side must be named with its line count",
                mirror.droppedLines > 0);
        assertTrue(result.excluded.contains("android/guava/src/com/acme/Widget0.java"));
        assertFalse("the kept side stays in the measurement",
                result.excluded.contains("guava/src/com/acme/Widget0.java"));
    }

    /**
     * The dropped side is decided by the tree, not by the order anything was inserted.
     *
     * <p>If it were not, the duplication ratio would depend on how the analysis got here -
     * which is the same class of bug as the index cap.</p>
     */
    @Test
    public void theKeptSideIsTheFullerTree()
    {
        TreeState state = new TreeState();
        for (int i = 0; i < 40; i++)
        {
            String body = classBody("Widget" + i, 30);
            put(state, "zzz-primary/src/com/acme/Widget" + i + ".java", body);
            put(state, "aaa-copy/src/com/acme/Widget" + i + ".java", body);
        }
        // One extra file makes zzz-primary the fuller tree despite sorting last.
        put(state, "zzz-primary/src/com/acme/Extra.java", classBody("Extra", 30));

        MirrorTrees.Result result = MirrorTrees.detect(state);

        assertEquals(1, result.mirrors.size());
        assertEquals("zzz-primary", result.mirrors.get(0).original);
        assertEquals("aaa-copy", result.mirrors.get(0).mirror);
    }

    /** Same layout, different code: two modules of one project, not a copy of one. */
    @Test
    public void matchingPathsWithDifferentContentAreNotAMirror()
    {
        TreeState state = new TreeState();
        for (int i = 0; i < 40; i++)
        {
            put(state, "service-a/src/com/acme/Handler" + i + ".java",
                    classBody("HandlerA" + i, 30));
            put(state, "service-b/src/com/acme/Handler" + i + ".java",
                    classBody("HandlerB" + i, 30));
        }

        MirrorTrees.Result result = MirrorTrees.detect(state);

        assertTrue("same file names are not the same files: " + describe(result),
                result.isEmpty());
        assertTrue(result.excluded.isEmpty());
    }

    /** A handful of shared names between two big trees is coincidence, not a mirror. */
    @Test
    public void aPartialPathOverlapIsNotAMirror()
    {
        TreeState state = new TreeState();
        for (int i = 0; i < 60; i++)
        {
            String body = classBody("Shared" + i, 30);
            put(state, "frontend/src/com/acme/Page" + i + ".java", body);
            put(state, "backend/src/com/acme/Service" + i + ".java", body);
        }
        // Thirty names in common, but each tree has ninety files, so the overlap is a third.
        for (int i = 0; i < 30; i++)
        {
            String body = classBody("Common" + i, 30);
            put(state, "frontend/src/com/acme/Dto" + i + ".java", body);
            put(state, "backend/src/com/acme/Dto" + i + ".java", body);
        }

        MirrorTrees.Result result = MirrorTrees.detect(state);

        assertTrue("a third of the files in common is not a mirror: " + describe(result),
                result.isEmpty());
    }

    /** Two directories of near-identical boilerplate, but too few files to call it. */
    @Test
    public void aSmallDirectoryPairIsBelowTheFloor()
    {
        TreeState state = new TreeState();
        for (int i = 0; i < 8; i++)
        {
            String body = classBody("Widget" + i, 30);
            put(state, "main/src/com/acme/Widget" + i + ".java", body);
            put(state, "copy/src/com/acme/Widget" + i + ".java", body);
        }

        assertTrue("eight files is not enough evidence to drop a subtree",
                MirrorTrees.detect(state).isEmpty());
    }

    /** A single flat tree cannot mirror itself, whatever its internal duplication. */
    @Test
    public void oneTreeOfRepetitiveFilesIsNotAMirror()
    {
        TreeState state = new TreeState();
        String body = classBody("Repeated", 30);
        for (int i = 0; i < 60; i++)
        {
            put(state, "src/com/acme/Copy" + i + ".java", body);
        }

        MirrorTrees.Result result = MirrorTrees.detect(state);

        assertTrue("sixty copies of one file is duplication, not layout: " + describe(result),
                result.isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static void put(TreeState state, String path, String content)
    {
        state.put(path, LineNormalizer.parse(content, Language.JAVA));
    }

    /** Distinct, long-enough Java lines so normalisation keeps them. */
    private static String classBody(String name, int lines)
    {
        StringBuilder out = new StringBuilder();
        out.append("public final class ").append(name).append(" {\n");
        for (int i = 0; i < lines; i++)
        {
            out.append("    private final long field_").append(name).append('_').append(i)
                    .append(" = computeStep(request, ").append(i).append(");\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private static String describe(MirrorTrees.Result result)
    {
        List<String> names = new ArrayList<String>();
        for (MirrorTrees.Mirror mirror : result.mirrors)
        {
            names.add(mirror.mirror + " = " + mirror.original);
        }
        return names.toString();
    }
}
