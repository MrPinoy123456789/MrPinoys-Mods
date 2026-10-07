package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pure-JDK regression for PD-157's book pagination ({@link BookPages}): a short
 * page stays one page, a long one splits at a paragraph break before a sentence
 * and at a sentence before a word, no page starts blank, no words are lost, and
 * every shipped diary page fits after pagination.
 */
public class BookPagesTest {

    private static final Path DIARY = Path.of("src/main/resources/data/pocketdungeons/diary");

    public static void main(String[] args) throws IOException {
        testShortPageStaysWhole();
        testSplitsAtAParagraph();
        testSplitsAtASentenceWhenAParagraphIsTooLong();
        testEmpty();
        testShippedDiariesFit();
        System.out.println("BookPagesTest passed");
    }

    private static void testShortPageStaysWhole() {
        List<String> pages = BookPages.paginate("I took what the walls gave me.");
        check(pages.size() == 1, "a short page is one page");
        check(pages.get(0).equals("I took what the walls gave me."), "unchanged");
    }

    private static void testSplitsAtAParagraph() {
        // Entry 8, "The First Pick": about 15 lines, the page that clipped live.
        String text = "The first cave I found had timbers in it.\n\nOld planks. Fence posts. A rail running off"
                + " into the dark.\n\nHe had built this. Years ago, when he still knew what a mine was for.\n\n"
                + "I took what the walls gave me. It felt like borrowing.";
        check(BookPages.lineCount(text) > BookPages.LINES, "entry 8 is over a page: " + BookPages.lineCount(text));
        List<String> pages = BookPages.paginate(text);
        check(pages.size() == 2, "entry 8 becomes two pages: " + pages);
        check(pages.get(1).equals("I took what the walls gave me. It felt like borrowing."),
                "the break falls between paragraphs: " + pages.get(1));
        checkWhole(text, pages);
    }

    private static void testSplitsAtASentenceWhenAParagraphIsTooLong() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            text.append("Sentence number ").append(i).append(" goes on a little while. ");
        }
        List<String> pages = BookPages.paginate(text.toString().strip());
        check(pages.size() >= 2, "one long paragraph still splits");
        for (String page : pages) {
            check(page.endsWith("."), "it splits at a sentence end: " + page);
        }
        checkWhole(text.toString(), pages);
    }

    private static void testEmpty() {
        check(BookPages.paginate("").equals(List.of("")), "an empty page stays one empty page");
    }

    private static void testShippedDiariesFit() throws IOException {
        int split = 0;
        try (Stream<Path> files = Files.list(DIARY)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonObject entry = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (JsonElement authored : entry.getAsJsonArray("pages")) {
                    List<String> pages = BookPages.paginate(authored.getAsString());
                    split += pages.size() - 1;
                    for (String page : pages) {
                        check(BookPages.fits(page), file.getFileName() + " has a page over budget: " + page);
                        check(!page.startsWith("\n"), file.getFileName() + " has a page starting blank");
                    }
                    checkWhole(authored.getAsString(), pages);
                }
            }
        }
        check(split > 0, "some shipped pages are long enough to split");
    }

    /** No word is lost or reordered by the split. */
    private static void checkWhole(String text, List<String> pages) {
        String before = String.join(" ", text.strip().split("\\s+"));
        String after = String.join(" ", String.join(" ", pages).strip().split("\\s+"));
        check(before.equals(after), "pagination keeps every word in order");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
