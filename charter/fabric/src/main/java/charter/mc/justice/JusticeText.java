package charter.mc.justice;

import charter.contract.Term;
import charter.mc.Text;
import charter.mc.cmd.Chat;

import java.util.ArrayList;
import java.util.List;

/** Terms of a sentence in plain words, for pages and notices. */
public final class JusticeText {

    private JusticeText() {
    }

    public static String term(Term term) {
        return switch (term) {
            case Term.Confinement c -> Text.get("term.confinement", "time", Chat.duration(c.durationMillis()), "cell", c.cell());
            case Term.Tether t -> Text.get("term.tether", "time", Chat.duration(t.durationMillis()));
            case Term.Banishment b -> Text.get("term.banishment", "time", Chat.duration(b.durationMillis()));
            case Term.RespawnDelay r -> Text.get("term.respawn", "time", Chat.duration(r.durationMillis()));
            case Term.Fine f -> Text.get("term.fine", "count", f.diamonds());
            default -> Text.get("term.other");
        };
    }

    public static String terms(List<Term> terms) {
        List<String> parts = new ArrayList<>();
        for (Term t : terms) parts.add(term(t));
        return String.join(", ", parts);
    }
}
