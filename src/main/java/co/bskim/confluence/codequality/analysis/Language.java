package co.bskim.confluence.codequality.analysis;

import java.util.Locale;

/**
 * Languages this release can read. Everything else is skipped outright rather than analysed
 * badly - a metric computed over files the scanner does not understand is worse than no metric.
 */
public enum Language
{
    PYTHON,
    JAVA,
    JAVASCRIPT,
    UNKNOWN;

    public static Language of(String path)
    {
        int dot = path.lastIndexOf('.');
        if (dot < 0)
        {
            return UNKNOWN;
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext)
        {
            case "py":
            case "pyi":
                return PYTHON;
            case "java":
                return JAVA;
            case "js":
            case "jsx":
            case "mjs":
            case "cjs":
            case "ts":
            case "tsx":
                return JAVASCRIPT;
            default:
                return UNKNOWN;
        }
    }

    public boolean isAnalysable()
    {
        return this != UNKNOWN;
    }
}
