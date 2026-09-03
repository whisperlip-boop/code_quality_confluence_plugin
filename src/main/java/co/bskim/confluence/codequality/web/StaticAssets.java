package co.bskim.confluence.codequality.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the plugin's own CSS and JavaScript off the classpath so the standalone pages can
 * inline them.
 *
 * <p>Inlining rather than linking is deliberate: the report is a single self-contained page,
 * a Confluence instance behind a firewall cannot reach a CDN, and one document means one
 * request and no flash of unstyled dashboard.</p>
 */
public final class StaticAssets
{
    private static final Map<String, String> CACHE = new ConcurrentHashMap<String, String>();

    private StaticAssets()
    {
    }

    public static String read(String resource)
    {
        String cached = CACHE.get(resource);
        if (cached != null)
        {
            return cached;
        }
        String content = load(resource);
        CACHE.put(resource, content);
        return content;
    }

    private static String load(String resource)
    {
        InputStream in = StaticAssets.class.getResourceAsStream(resource);
        if (in == null)
        {
            return "";
        }
        try
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0)
            {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            return "";
        }
        finally
        {
            try
            {
                in.close();
            }
            catch (IOException ignored)
            {
                // Nothing useful to do while closing a classpath stream.
            }
        }
    }

    /** Minimal HTML text escaping for the few values these pages interpolate. */
    public static String escape(String value)
    {
        if (value == null)
        {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Escapes a JSON payload for safe embedding inside a script element. */
    public static String forScript(String json)
    {
        if (json == null)
        {
            return "null";
        }
        return json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
    }
}
