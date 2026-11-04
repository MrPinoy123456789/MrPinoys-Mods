package charter.mc;

import charter.engine.Action;
import charter.grant.Holder;
import charter.grant.Power;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.store.TypeRegistry;
import charter.state.CharterState;
import charter.treasury.Currency;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Plain words for ids and actions, for pages and notices. An action is described
 * by the text key {@code motion.<its registered name>}, with each record field as
 * a named placeholder already turned into a name: so a later milestone describes
 * its actions by adding text keys, not code.
 */
public final class Describe {

    private final CharterState state;
    private final TypeRegistry<Action> actions;

    public Describe(CharterState state, TypeRegistry<Action> actions) {
        this.state = state;
        this.actions = actions;
    }

    public String org(OrgId id) {
        return id == null ? Text.get("name.nobody") : state.orgIncludingDissolved(id).map(o -> o.name()).orElse(Text.get("name.unknown_org"));
    }

    public String player(PlayerId id) {
        return id == null ? Text.get("name.nobody")
                : state.knownPlayer(id).map(p -> p.name).filter(n -> !n.isEmpty()).orElse(Text.get("name.someone"));
    }

    public String office(OfficeId id) {
        return state.allOffices().stream().filter(o -> o.id.equals(id)).findFirst().map(o -> o.name).orElse(Text.get("name.unknown_office"));
    }

    public static String power(Power power) {
        return Text.get("power." + power.name().toLowerCase(Locale.ROOT));
    }

    public static String currency(Currency currency, long amount) {
        return Text.get("currency." + currency.name().toLowerCase(Locale.ROOT) + (amount == 1 ? "" : ".many"));
    }

    public String holder(Holder holder) {
        return switch (holder) {
            case Holder.OfPlayer p -> player(p.player());
            case Holder.OfOffice o -> Text.get("name.office_holder", "office", office(o.office()));
            case Holder.OfOrg o -> org(o.org());
            case Holder.OfRank r -> Text.get("name.rank_holder", "rank",
                    state.ranks.rank(r.kingdom(), r.rank()).map(charter.org.Ranks.Rank::name).orElse(r.rank()), "org", org(r.kingdom()));
        };
    }

    public String core(CoreId id) {
        return state.core(id).map(c -> Text.get(c.isKeep() ? "name.keep_at" : "name.camp_at",
                "x", c.center.x() * 16 + 8, "z", c.center.z() * 16 + 8)).orElse(Text.get("name.gone_site"));
    }

    /** One value of a record field, as a reader would want to see it. */
    public String value(Object value) {
        return switch (value) {
            case null -> Text.get("name.nobody");
            case OrgId id -> org(id);
            case PlayerId id -> player(id);
            case OfficeId id -> office(id);
            case CoreId id -> core(id);
            case Holder h -> holder(h);
            case Power p -> power(p);
            case Currency c -> currency(c, 2);
            case ChunkKey c -> Text.get("name.chunk", "x", c.x(), "z", c.z());
            case Enum<?> e -> e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            default -> String.valueOf(value);
        };
    }

    /**
     * A rank action names its rank by id ("r3"); the motion text wants the name players see, so {@code {rank_name}} is
     * the rank's name now, or the id when the rank is gone.
     */
    private void rankName(Action action, Map<String, Object> fields) {
        OrgId kingdom = switch (action) {
            case charter.org.actions.RenameRank a -> a.kingdom();
            case charter.org.actions.RemoveRank a -> a.kingdom();
            case charter.org.actions.MoveRank a -> a.kingdom();
            case charter.org.actions.GiveRank a -> a.kingdom();
            case charter.org.actions.SetRankPower a -> a.kingdom();
            default -> null;
        };
        if (kingdom == null) return;
        Object rank = fields.get("rank");
        if (rank == null) return;
        fields.put("rank_name", state.ranks.rank(kingdom, String.valueOf(rank)).map(charter.org.Ranks.Rank::name).orElse(String.valueOf(rank)));
    }

    /** "pay 10 diamonds to Bob": the action's text key filled from its fields. */
    public String action(Action action) {
        if (action == null) return Text.get("motion.unknown");
        String tag = actions.tag(action.getClass()).orElse(action.getClass().getSimpleName());
        Map<String, Object> fields = new LinkedHashMap<>();
        if (action.getClass().isRecord()) {
            for (RecordComponent c : action.getClass().getRecordComponents()) {
                try {
                    Object raw = c.getAccessor().invoke(action);
                    // An action's coin is Marks in code; a motion reads it in Sovereigns.
                    fields.put(c.getName(), c.getName().equals("marks") && raw instanceof Number n
                            ? charter.api.Coin.words(n.longValue()) : value(raw));
                } catch (ReflectiveOperationException e) {
                    fields.put(c.getName(), "?");
                }
            }
        }
        rankName(action, fields);
        String text = Text.fill("motion." + tag, fields);
        return text != null ? text : Text.get("motion.unknown_kind", "kind", tag);
    }
}
