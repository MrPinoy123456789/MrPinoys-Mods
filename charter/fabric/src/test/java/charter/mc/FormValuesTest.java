package charter.mc;

import charter.mc.book.FormValues;

import java.util.List;

/** The format a dialog form sends its inputs back in. */
final class FormValuesTest {

    private FormValuesTest() {
    }

    static void run() {
        FabricSuite.check("a template lists the inputs in order after the token",
                FormValues.template("abc", List.of("name", "x", "y")).equals("/charter form abc =$(name) | $(x) | $(y)"));
        FabricSuite.check("one input has no separator", FormValues.template("abc", List.of("n")).equals("/charter form abc =$(n)"));

        FabricSuite.check("values come back trimmed and in order",
                FormValues.split("=Azrak | 12 |  -4 ", 3).orElseThrow().equals(List.of("Azrak", "12", "-4")));
        FabricSuite.check("an empty value is kept, so the count still matches",
                FormValues.split("= | 5", 2).orElseThrow().equals(List.of("", "5")));
        FabricSuite.check("a single empty input still parses", FormValues.split("=", 1).orElseThrow().equals(List.of("")));
        FabricSuite.check("the wrong count is refused", FormValues.split("=a | b", 3).isEmpty());
        FabricSuite.check("text without the prefix is refused", FormValues.split("a | b", 2).isEmpty());
        FabricSuite.check("a name with a bar changes the count and is refused", FormValues.split("=Old | Mossbeard | 5", 2).isEmpty());

        FabricSuite.check("a plain name is clean", FormValues.clean("Old Mossbeard's Muddle"));
        FabricSuite.check("a bar is not clean", !FormValues.clean("a|b"));
        FabricSuite.check("a control character is not clean", !FormValues.clean("a\nb"));

        FabricSuite.check("an integer is read", FormValues.integer(" -12 ").getAsInt() == -12);
        FabricSuite.check("text is not an integer", FormValues.integer("12x").isEmpty());
        FabricSuite.check("nothing is not an integer", FormValues.integer("").isEmpty());

        FabricSuite.check("a name is quoted as one argument", FormValues.quote("Azrak").equals("\"Azrak\""));
        FabricSuite.check("a quote and a backslash inside a name are escaped",
                FormValues.quote("a\"b\\c").equals("\"a\\\"b\\\\c\""));
    }
}
