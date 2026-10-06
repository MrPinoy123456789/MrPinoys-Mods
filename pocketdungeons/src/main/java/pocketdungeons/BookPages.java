package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * PD-157: fits authored prose onto written book pages. A written book page does
 * not scroll and does not flow onto the next page; it shows about 14 lines of
 * 114 pixels and clips the rest. Each authored diary page used to become one
 * book page verbatim, so a long one lost its last lines.
 *
 * <p>{@link #paginate} splits one authored page into as many book pages as it
 * needs: at a paragraph break first, then at a sentence end, then at a word.
 * A new page never starts with a blank line, and the split only ever adds pages
 * (it never joins two authored pages). The width is measured with the default
 * font's advances, which the server cannot read from the client, so the budget
 * is {@link #LINES} lines, one short of what the page shows.
 *
 * <p>No Minecraft imports, so {@code BookPagesTest} runs with plain {@code javac}.
 */
final class BookPages {

    private BookPages() {}

    /** The text width of a written book page, in font pixels. */
    static final int PAGE_WIDTH = 114;

    /** Lines a page may hold: the page shows 14, one is kept spare against measuring wrong. */
    static final int LINES = 13;

    /** Splits one authored page into book pages, each {@link #LINES} lines or fewer where words allow. */
    static List<String> paginate(String text) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        String body = text == null ? "" : text.strip();
        if (!body.isEmpty()) {
            for (String paragraph : body.split("\n\\s*\n")) {
                String piece = paragraph.strip();
                if (!piece.isEmpty()) {
                    page = add(pages, page, piece, "\n\n", 0);
                }
            }
        }
        if (page.length() > 0 || pages.isEmpty()) {
            pages.add(page.toString());
        }
        return pages;
    }

    /**
     * Adds {@code piece} to the open page, joined by {@code sep}, or starts a new
     * page with it, or splits it one level finer (paragraph, sentence, word) when
     * it cannot fit on a page of its own. Returns the page left open.
     */
    private static StringBuilder add(List<String> pages, StringBuilder page, String piece, String sep, int level) {
        String joined = page.length() == 0 ? piece : page + sep + piece;
        if (fits(joined)) {
            return new StringBuilder(joined);
        }
        if (fits(piece)) {
            pages.add(page.toString());
            return new StringBuilder(piece);
        }
        String[] parts = level == 0 ? piece.split("(?<=[.!?])[ ]+") : level == 1 ? piece.split("[ ]+") : null;
        if (parts == null) {
            // One word wider than a whole page: nothing finer to split at, the client wraps it.
            if (page.length() > 0) {
                pages.add(page.toString());
            }
            return new StringBuilder(piece);
        }
        if (parts.length == 1) {
            return add(pages, page, piece, sep, level + 1);
        }
        page = add(pages, page, parts[0], sep, level + 1);
        for (int i = 1; i < parts.length; i++) {
            page = add(pages, page, parts[i], " ", level + 1);
        }
        return page;
    }

    /** Whether {@code text} fits one page. */
    static boolean fits(String text) {
        return lineCount(text) <= LINES;
    }

    /** The lines {@code text} takes on a book page: each newline breaks, long lines wrap at spaces. */
    static int lineCount(String text) {
        int lines = 0;
        for (String line : text.split("\n", -1)) {
            lines += wrapped(line);
        }
        return lines;
    }

    private static int wrapped(String line) {
        if (line.isEmpty()) {
            return 1;
        }
        int lines = 1;
        int used = 0;
        for (String word : line.split(" ", -1)) {
            int w = width(word);
            int add = used == 0 ? w : width(" ") + w;
            if (used > 0 && used + add > PAGE_WIDTH) {
                lines++;
                used = w;
            } else {
                used += add;
            }
            while (used > PAGE_WIDTH) {
                // A word wider than the page breaks mid word, as the client does.
                lines++;
                used -= PAGE_WIDTH;
            }
        }
        return lines;
    }

    /** The width of {@code s} in the default font: each glyph's advance, one pixel of spacing included. */
    static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += advance(s.charAt(i));
        }
        return w;
    }

    private static int advance(char c) {
        return switch (c) {
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
            case 'l', '`' -> 3;
            case 't', 'I', ' ', '[', ']', '"', '(', ')', '*', '{', '}', '<', '>' -> 4;
            case 'f', 'k' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }
}
