package charter.org;

import static charter.Check.eq;

/** The plural of a race's name in "land of the ...". */
public final class RaceNamesTest {

    private RaceNamesTest() {
    }

    public static void run() {
        eq("Dwarf", "Dwarves", RaceNames.plural("Dwarf"));
        eq("Human", "Humans", RaceNames.plural("Human"));
        eq("Fairy", "Fairies", RaceNames.plural("Fairy"));
        eq("Fae stays", "Fae", RaceNames.plural("Fae"));
        eq("Merfolk stays", "Merfolk", RaceNames.plural("Merfolk"));
        eq("Elderfolk stays", "Elderfolk", RaceNames.plural("Elderfolk"));
        eq("Elf", "Elves", RaceNames.plural("Elf"));
        eq("Bogle", "Bogles", RaceNames.plural("Bogle"));
        eq("Hobs already plural", "Hobs", RaceNames.plural("Hobs"));
        eq("blank", "", RaceNames.plural(""));
    }
}
