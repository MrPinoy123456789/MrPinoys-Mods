package charter.mc.store;

import charter.engine.Action;
import charter.history.HistoryEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Where features register what the store must know about them, from their own
 * {@code register} call in {@code CharterMod}:
 *
 * <ul>
 * <li>{@link #ACTIONS}: every action type a vote may hold as its motion</li>
 * <li>{@link #EVENTS}: every history event type</li>
 * <li>{@link #block}: a {@link StateBlock} for records the feature adds to CharterState</li>
 * </ul>
 *
 * <p>M6 (contracts and justice) is expected to call {@code ACTIONS.register(...)} for
 * each of its actions, {@code EVENTS.registerNested(ContractEvents.class, HistoryEvent.class)}
 * for its events, and {@code block(...)} once for its records, all from one
 * {@code ContractTypes.register()} of its own.
 */
public final class Persistence {

    public static final TypeRegistry<Action> ACTIONS = new TypeRegistry<>("action");
    public static final TypeRegistry<HistoryEvent> EVENTS = new TypeRegistry<>("event");
    private static final List<StateBlock> BLOCKS = new ArrayList<>();

    private Persistence() {
    }

    /**
     * The one register call for every feature's store names, adapters and blocks:
     * M1 to M5 and the store's own events, then M6's contracts. Tests and the
     * server both call this, so a feature registered here is never missed.
     */
    public static void register() {
        if (!ACTIONS.has(charter.org.actions.Found.class)) CoreTypes.register(ACTIONS, EVENTS);
        if (!ACTIONS.has(charter.contract.actions.Arrest.class)) ContractTypes.register();
        charter.mc.granary.GranaryBook.register();
        charter.mc.justice.CellBook.register();
    }

    /** Adds a JSON adapter for a feature's own types, before any CharterJson is made. */
    public static void adapter(com.google.gson.TypeAdapterFactory factory) {
        CharterJson.EXTRA.add(factory);
    }

    /** Adds a feature's block after the core blocks, in call order. */
    public static void block(StateBlock block) {
        if (BLOCKS.stream().noneMatch(b -> b.key().equals(block.key()))) BLOCKS.add(block);
    }

    public static CharterJson json() {
        return new CharterJson(ACTIONS, EVENTS);
    }

    /** A codec with the core blocks, every registered feature block, then these (blocks bound to one server). */
    public static StateCodec codec(CharterJson json, StateBlock... extra) {
        StateCodec codec = StateCodec.standard(json);
        for (StateBlock block : BLOCKS) codec.register(block);
        for (StateBlock block : extra) codec.register(block);
        return codec;
    }
}
