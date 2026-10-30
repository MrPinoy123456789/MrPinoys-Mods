package foundry.core;

import java.util.regex.Pattern;

/** A namespaced identifier such as {@code myserver:crystal_sword}. Pure JDK, no Minecraft types. */
public record Ident(String namespace, String path) {
    private static final Pattern NS = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    /** Returns null when the text is not a valid identifier. */
    public static Ident parse(String text) {
        if (text == null) return null;
        int colon = text.indexOf(':');
        if (colon <= 0 || colon == text.length() - 1) return null;
        String ns = text.substring(0, colon);
        String path = text.substring(colon + 1);
        if (!NS.matcher(ns).matches() || !PATH.matcher(path).matches()) return null;
        return new Ident(ns, path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
