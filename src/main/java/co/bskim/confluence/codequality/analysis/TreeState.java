package co.bskim.confluence.codequality.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The analysable part of one tree, plus its n-gram index, carried forward from commit to
 * commit and mutated in place.
 */
public final class TreeState
{
    private final Map<String, FileLines> files = new HashMap<String, FileLines>();
    private final Map<String, Integer> pathIds = new HashMap<String, Integer>();
    private final List<String> paths = new ArrayList<String>();
    private final NgramIndex index = new NgramIndex();

    public int pathId(String path)
    {
        Integer id = pathIds.get(path);
        if (id == null)
        {
            id = paths.size();
            paths.add(path);
            pathIds.put(path, id);
        }
        return id;
    }

    public String path(int id)
    {
        return paths.get(id);
    }

    public FileLines get(String path)
    {
        return files.get(path);
    }

    public Set<String> paths()
    {
        return Collections.unmodifiableSet(files.keySet());
    }

    public void put(String path, FileLines lines)
    {
        int id = pathId(path);
        FileLines previous = files.put(path, lines);
        if (previous != null)
        {
            index.removeFile(id, previous.norm);
        }
        index.addFile(id, lines.norm);
    }

    public void remove(String path)
    {
        FileLines previous = files.remove(path);
        if (previous != null)
        {
            index.removeFile(pathId(path), previous.norm);
        }
    }

    NgramIndex index()
    {
        return index;
    }

    public int fileCount()
    {
        return files.size();
    }

    public int normLineCount()
    {
        int total = 0;
        for (FileLines f : files.values())
        {
            total += f.norm.size();
        }
        return total;
    }

    public int codeLineCount()
    {
        int total = 0;
        for (FileLines f : files.values())
        {
            total += f.code.size();
        }
        return total;
    }

    public int commentLineCount()
    {
        int total = 0;
        for (FileLines f : files.values())
        {
            total += f.commentLines;
        }
        return total;
    }

    public int totalLineCount()
    {
        int total = 0;
        for (FileLines f : files.values())
        {
            total += f.totalLines;
        }
        return total;
    }

    /** Deterministic iteration order, so that two runs over the same commit agree exactly. */
    public List<String> sortedPaths()
    {
        List<String> sorted = new ArrayList<String>(files.keySet());
        Collections.sort(sorted);
        return sorted;
    }
}
