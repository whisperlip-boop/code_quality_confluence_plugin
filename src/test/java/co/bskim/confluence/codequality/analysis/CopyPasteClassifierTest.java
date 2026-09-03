package co.bskim.confluence.codequality.analysis;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static co.bskim.confluence.codequality.analysis.ClassifierFixture.lines;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Fixtures for the copy-paste classifier.
 *
 * <p>These exist because the first version of this classifier judged every added line on its
 * own and reported a 13.7% copy-paste ratio for the reference repository - almost all of it
 * language idioms. The block rule dropped it to 4.2%. Nothing but these tests stops a change
 * to {@link AnalysisConfig#RUN} or to the normaliser from quietly restoring that.</p>
 */
public class CopyPasteClassifierTest
{
    /** The six-line block from the reference repository's two dialog classes. */
    private static final String BLOCK = lines(
            "layout = QVBoxLayout(self)",
            "layout.setContentsMargins(16, 16, 16, 16)",
            "self.spin = QSpinBox(self)",
            "self.spin.setRange(1, 4096)",
            "self.spin.setValue(current_value)",
            "layout.addWidget(self.spin)");

    /**
     * The regression that mattered: scattered one-line idioms must not count as copying.
     *
     * <p>{@code painter.end()}, {@code @staticmethod} and {@code self._push_undo()} all survive
     * normalisation and all appear in the parent, but never as three consecutive lines. A
     * line-level rule called every one of them a copy.</p>
     */
    @Test
    public void scatteredIdiomsAreNotCopies()
    {
        LineMix mix = new ClassifierFixture(Language.PYTHON)
                .before("capture/canvas_view.py", lines(
                        "def snapshot(self, target_rect):",
                        "self._push_undo()",
                        "pixmap = QPixmap(target_rect.size())",
                        "painter = QPainter(pixmap)",
                        "painter.drawPixmap(0, 0, self._backing_store)",
                        "painter.end()",
                        "@staticmethod",
                        "def clamp_to_canvas(point, bounds):",
                        "return max(bounds.left(), min(point.x(), bounds.right()))"))
                .change("capture/region_overlay.py", lines(
                        "def commit_selection(self, chosen_rect):",
                        "self._push_undo()",
                        "self._selection_history.append(chosen_rect)",
                        "painter.end()",
                        "self._emit_selection_changed(chosen_rect)",
                        "@staticmethod",
                        "def describe_region(rect):",
                        "return f\"{rect.width()}x{rect.height()}\""))
                .classify();

        assertEquals("idioms must not be counted as copied", 0, mix.copied);
        assertEquals(0, mix.moved);
        assertTrue("every added line should be novel", mix.novel > 0);
    }

    /** A real six-line block lifted into a new file is a copy, all six lines of it. */
    @Test
    public void blockCopiedIntoAnotherFileCountsEveryLine()
    {
        LineMix mix = new ClassifierFixture(Language.PYTHON)
                .before("capture/canvas_size_dialog.py",
                        lines("class CanvasSizeDialog(QDialog):") + BLOCK)
                .change("capture/rotate_angle_dialog.py",
                        lines("class RotateAngleDialog(QDialog):") + BLOCK)
                .classify();

        assertEquals(6, mix.copied);
        assertEquals(0, mix.moved);
    }

    /**
     * Source gone from where it was: refactoring, not duplication.
     *
     * <p>The surrounding code is deliberately longer than the block. A diff moves whichever
     * side is cheaper to move, so with a two-line neighbour it would report the neighbour as
     * the addition and the block would never be classified at all.</p>
     */
    @Test
    public void blockWhoseSourceThisCommitDeletedIsAMove()
    {
        String tail = lines(
                "def unrelated_tail(self):",
                "return self._backing_store",
                "def another_helper(self, value):",
                "self._cache[value] = compute_something(value)",
                "return self._cache[value]",
                "def third_helper(self):",
                "self._invalidate_cache_entries()",
                "return len(self._cache)",
                "def fourth_helper(self, index):",
                "return self._items[index] if index < len(self._items) else None");

        LineMix mix = new ClassifierFixture(Language.PYTHON)
                .before("capture/canvas_view.py", BLOCK + tail)
                .change("capture/canvas_view.py", tail + BLOCK)
                .classify();

        assertEquals("the block left its old position", 6, mix.moved);
        assertEquals(0, mix.copied);
    }

    /** The same thing across files, which is how refactoring usually looks. */
    @Test
    public void blockLiftedIntoAnotherFileAndDeletedIsAMove()
    {
        LineMix mix = new ClassifierFixture(Language.PYTHON)
                .before("capture/canvas_size_dialog.py",
                        lines("class CanvasSizeDialog(QDialog):") + BLOCK)
                .change("capture/canvas_size_dialog.py",
                        lines("class CanvasSizeDialog(QDialog):",
                              "def __init__(self, parent, current_value):",
                              "super().__init__(parent)",
                              "build_spin_row(self, current_value)"))
                .change("capture/dialog_widgets.py",
                        lines("def build_spin_row(self, current_value):") + BLOCK)
                .classify();

        assertEquals("the block was lifted out, not duplicated", 6, mix.moved);
        assertEquals(0, mix.copied);
    }

    /** Source still there: the code now exists twice, whatever the intent was. */
    @Test
    public void blockWhoseSourceSurvivedIsACopy()
    {
        LineMix mix = new ClassifierFixture(Language.PYTHON)
                .before("capture/canvas_view.py", BLOCK)
                .change("capture/canvas_view.py",
                        BLOCK + lines("def second_setup(self):") + BLOCK)
                .classify();

        assertEquals(6, mix.copied);
        assertEquals(0, mix.moved);
    }

    /**
     * The classification must not depend on the order files entered the index.
     *
     * <p>A full run inserts files as commits touch them; an incremental run materialises a tree
     * in path order. If the index drops locations once a window has been seen too often, the
     * two orders keep different candidates, the classifier picks a different source, and the
     * copy/move verdict flips - on a repository where nothing changed but when the analysis
     * ran. This fixture makes that concrete: the same window in many files, and the one file
     * whose copy was deleted sorts first.</p>
     */
    @Test
    public void classificationDoesNotDependOnIndexInsertionOrder()
    {
        // Well past any plausible per-bucket cap, so a capped index would have to drop
        // locations and the two orders would disagree.
        int fileCount = 70;
        Map<String, String> contents = new LinkedHashMap<String, String>();
        List<String> ascending = new ArrayList<String>();
        for (int i = 0; i < fileCount; i++)
        {
            String path = String.format("pkg/mod%02d.py", i);
            contents.put(path, BLOCK);
            ascending.add(path);
        }
        List<String> descending = new ArrayList<String>(ascending);
        Collections.reverse(descending);

        LineMix forward = classifyWithOrder(ascending, contents);
        LineMix reverse = classifyWithOrder(descending, contents);

        assertEquals("copied must not depend on insertion order",
                forward.copied, reverse.copied);
        assertEquals("moved must not depend on insertion order", forward.moved, reverse.moved);
        assertEquals("novel must not depend on insertion order", forward.novel, reverse.novel);
    }

    private static LineMix classifyWithOrder(List<String> order, Map<String, String> contents)
    {
        return new ClassifierFixture(Language.PYTHON)
                .beforeInOrder(order, contents)
                // mod00 sorts first, so it wins the classifier's tie-break whenever it is
                // still in the index - and this commit removes its copy of the block.
                .change("pkg/mod00.py", lines("def placeholder():", "return None"))
                .change("pkg/newcomer.py", lines("class Newcomer:") + BLOCK)
                .classify();
    }
}
