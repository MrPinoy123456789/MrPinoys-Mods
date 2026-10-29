package foundry.core;

/** One validation finding. Written for a person to read, so every field is plain language. */
public record Problem(Severity severity, String content, String where, String message, String fix) {
    public enum Severity { ERROR, WARNING }

    public static Problem error(String content, String where, String message, String fix) {
        return new Problem(Severity.ERROR, content, where, message, fix);
    }

    public static Problem warning(String content, String where, String message, String fix) {
        return new Problem(Severity.WARNING, content, where, message, fix);
    }

    public boolean blocking() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return severity + " " + content + " [" + where + "]: " + message + " Fix: " + fix;
    }
}
