package charter.mc;

import charter.mc.book.BookResult;

/** The green or red mark on the line above a rebuilt page. */
final class BookResultTest {

    private BookResultTest() {
    }

    static void run() {
        String ok = BookResult.ok("Admitted Bob.");
        FabricSuite.check("a success is marked", BookResult.isOk(ok));
        FabricSuite.check("the mark never reaches the player", BookResult.text(ok).equals("Admitted Bob."));
        FabricSuite.check("a refusal has no mark", !BookResult.isOk("You may not."));
        FabricSuite.check("a refusal's text is untouched", BookResult.text("You may not.").equals("You may not."));
        FabricSuite.check("no line stays no line", BookResult.ok(null) == null && !BookResult.isOk(null));
        FabricSuite.check("of() prefers the refusal", BookResult.of("No.", "Done.").equals("No."));
        FabricSuite.check("of() marks the success when nothing refused", BookResult.isOk(BookResult.of(null, "Done.")));
    }
}
