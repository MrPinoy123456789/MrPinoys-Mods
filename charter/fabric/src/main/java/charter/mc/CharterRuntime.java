package charter.mc;

import charter.config.Knobs;
import charter.engine.ActionEngine;
import charter.food.FoodTable;
import charter.mc.land.ClaimIndex;
import charter.mc.store.CharterJson;
import charter.mc.store.CharterStore;
import charter.mc.store.HistoryFile;
import charter.mc.store.Persistence;
import charter.mc.store.Repair;
import charter.mc.store.Repairs;
import charter.mc.store.SiegeLog;
import charter.siege.SiegeSim;
import charter.state.CharterState;
import charter.time.Clock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * One world's Charter, wired: the state loaded from the store, the engine and the
 * sim over it, and the listeners that follow its history (the history file, the
 * siege log, the claim index, notices). No Minecraft types, so the whole thing
 * runs in a plain test exactly as it runs in a server.
 */
public final class CharterRuntime implements AutoCloseable {

    public final Knobs knobs;
    public final Clock clock;
    public final CharterJson json;
    public final CharterStore store;
    public final CharterStore.Loaded loaded;
    public final CharterState state;
    public final ActionEngine engine;
    public final SiegeSim sim;
    public final HistoryFile history;
    public final SiegeLog siegeLog;
    public final ClaimIndex claims;
    /** The public land map other mods read, republished after land changes. */
    public final charter.mc.land.AreaPublisher areas;
    public final Mail mail;
    public final Describe describe;
    public final Notices notices;
    private final Consumer<String> warn;

    private CharterRuntime(Path dir, Knobs knobs, Clock clock, Notices.Messenger messenger,
                           Consumer<String> info, Consumer<String> warn) {
        this.knobs = knobs;
        this.clock = clock;
        this.warn = warn;
        this.mail = new Mail(knobs.offlineMessagesKept);
        this.json = Persistence.json();
        this.store = new CharterStore(dir, Persistence.codec(json, mail.block()), info, warn);
        this.loaded = store.load(knobs, clock.now());
        this.state = loaded.state();

        this.history = new HistoryFile(dir, json, warn);
        int skipped = history.readInto(state.history);
        if (skipped > 0) warn.accept(skipped + " line(s) of " + HistoryFile.NAME + " could not be read back and were skipped.");

        this.engine = new ActionEngine(state, knobs, clock);
        this.sim = new SiegeSim(engine, new FoodTable());
        this.claims = new ClaimIndex(state);
        this.siegeLog = new SiegeLog(dir.resolve("siege-log"), json, state.siegeClock::now, knobs.siegeLog, warn);
        siegeLog.prune(clock.now(), knobs.siegeLogRetentionDays);
        this.describe = new Describe(state, json.actions);
        this.notices = new Notices(state, mail, describe, messenger);

        state.history.listen(history::append);
        state.history.listen(siegeLog::onEntry);
        state.history.listen(claims::onEntry);
        this.areas = new charter.mc.land.AreaPublisher(state, dir, warn);
        state.history.listen(areas::onEntry);
        state.history.listen(notices::onEntry);

        // Repairs found while loading go to the history file now that it is listening.
        loaded.repairs().recordTo(state);
    }

    /** Loads the world's state from {@code dir} (the world's {@code charter/} folder) and wires it up. */
    public static CharterRuntime open(Path dir, Knobs knobs, Clock clock, Notices.Messenger messenger,
                                      Consumer<String> info, Consumer<String> warn) {
        return new CharterRuntime(dir, knobs, clock, messenger, info, warn);
    }

    public void save() {
        try {
            store.save(state, clock.now());
        } catch (IOException e) {
            warn.accept("Could not save Charter state: " + e);
        }
    }

    /** Runs the load repairs over the live state (admin "repair"). */
    public Repairs repairNow() {
        Repairs repairs = new Repairs(clock.now());
        Repair.run(state, knobs, repairs);
        repairs.recordTo(state);
        claims.rebuild();
        return repairs;
    }

    @Override
    public void close() {
        history.close();
        siegeLog.close();
        areas.close();
    }
}
