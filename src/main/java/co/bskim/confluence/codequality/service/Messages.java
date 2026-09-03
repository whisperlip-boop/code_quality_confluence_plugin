package co.bskim.confluence.codequality.service;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the plugin's own message bundles straight off the classpath.
 *
 * <p>The report page ships every language at once so the reader can switch without a round
 * trip, which means resolving several locales during one request. SAL's resolver answers for
 * the caller's locale, so this reads the bundles directly instead - they are our own files,
 * shipped in our own jar, and {@link Properties#load(InputStream)} already decodes the
 * {@code \\uXXXX} escapes that Confluence's ISO-8859-1 reading forces on us.</p>
 *
 * <p>The repository table still goes through SAL, because there a single language - the
 * viewer's Confluence preference - is the right answer.</p>
 */
public final class Messages
{
    /** Report languages, in the order the switcher shows them. The first one is the default. */
    public static final String[] LANGUAGES = { "ko", "en" };

    private static final String FALLBACK = "en";
    private static final Map<String, Properties> CACHE = new ConcurrentHashMap<String, Properties>();

    private Messages()
    {
    }

    public static String get(String language, String key)
    {
        String value = bundle(language).getProperty(key);
        if (value == null && !FALLBACK.equals(language))
        {
            value = bundle(FALLBACK).getProperty(key);
        }
        return value == null ? key : value;
    }

    public static String format(String language, String key, Object... arguments)
    {
        String pattern = get(language, key);
        if (arguments == null || arguments.length == 0)
        {
            return pattern;
        }
        return new MessageFormat(pattern, localeOf(language)).format(arguments);
    }

    static Locale localeOf(String language)
    {
        return "ko".equals(language) ? Locale.KOREA : Locale.ENGLISH;
    }

    private static Properties bundle(String language)
    {
        Properties cached = CACHE.get(language);
        if (cached != null)
        {
            return cached;
        }
        Properties loaded = load("ko".equals(language)
                ? "/code-quality_ko_KR.properties" : "/code-quality.properties");
        CACHE.put(language, loaded);
        return loaded;
    }

    private static Properties load(String resource)
    {
        Properties properties = new Properties();
        InputStream in = Messages.class.getResourceAsStream(resource);
        if (in == null)
        {
            return properties;
        }
        try
        {
            properties.load(in);
        }
        catch (IOException e)
        {
            // An unreadable bundle degrades to raw keys on screen, which is survivable; a
            // failed report is not.
            return properties;
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
        return properties;
    }
}
