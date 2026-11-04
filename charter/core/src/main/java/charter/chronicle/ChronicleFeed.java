package charter.chronicle;

import charter.history.History;

import java.util.ArrayList;
import java.util.List;

/**
 * The Chronicle as a machine-readable feed (docs/2026-11-04-chronicle-design.md 5.1):
 * one JSON object per told happening, for whoever drafts the Discord Chronicle. Core
 * has no JSON library, so the objects are written by hand; the shape is small and
 * fixed. Quiet, admin and raider entries are never written, and nothing a player
 * typed reaches the file except names the engine already vets.
 *
 * <pre>
 * {"at":1762290000000,"day":"day 3, the second week of Eclipse","tier":"realm","kind":"OrgEvents.Joined",
 *  "story":"The Betrayal","line":"On day 3, ...","headline":"Wing left ...","orgs":["Everdelle","Aurelia"],
 *  "players":["Wing"],"n":418}
 * </pre>
 *
 * <p>{@code n} is the history index of the last entry the object covers, so a reader
 * keeps its own cursor and never drafts a line twice.
 */
public final class ChronicleFeed {

    private ChronicleFeed() {
    }

    /**
     * The feed lines for the entries from history index {@code from} on. The renderer
     * must hide admin events (the feed is public); a renderer that shows them is
     * refused so a test cannot leak them by accident.
     */
    public static List<String> lines(ChronicleRenderer renderer, Calendar calendar, List<History.Entry> entries, int from) {
        List<String> out = new ArrayList<>();
        for (ChronicleRenderer.Told told : renderer.tell(entries)) {
            if (told.tier() == Tier.QUIET) continue;
            if (entries.get(told.first()).admin()) continue;
            out.add(line(told, calendar, from));
        }
        return out;
    }

    static String line(ChronicleRenderer.Told told, Calendar calendar, int from) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"at\":").append(told.event().at());
        b.append(",\"day\":").append(quote(calendar.dayPhrase(told.event().at())));
        b.append(",\"tier\":").append(quote(told.tier().word()));
        b.append(",\"kind\":").append(quote(TemplateSet.keyOf(told.event().getClass())));
        if (told.story().isPresent()) b.append(",\"story\":").append(quote(told.story().get()));
        b.append(",\"line\":").append(quote(told.line()));
        b.append(",\"headline\":").append(quote(told.headline()));
        b.append(",\"orgs\":").append(array(told.orgs()));
        b.append(",\"players\":").append(array(told.players()));
        b.append(",\"n\":").append(from + told.last());
        return b.append('}').toString();
    }

    /** The history index a feed line covers, read back from its {@code n}; -1 when the line is not a feed line. */
    public static int indexOf(String feedLine) {
        if (feedLine == null) return -1;
        int at = feedLine.lastIndexOf("\"n\":");
        if (at < 0) return -1;
        int i = at + 4;
        int end = i;
        while (end < feedLine.length() && Character.isDigit(feedLine.charAt(end))) end++;
        if (end == i) return -1;
        try {
            return Integer.parseInt(feedLine.substring(i, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String array(List<String> items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(',');
            b.append(quote(items.get(i)));
        }
        return b.append(']').toString();
    }

    /** A JSON string: quotes, backslashes and control characters escaped, everything else as it is. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
