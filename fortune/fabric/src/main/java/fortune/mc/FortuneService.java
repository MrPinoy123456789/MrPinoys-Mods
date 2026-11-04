package fortune.mc;

import fortune.core.Annals;
import fortune.core.Calendar;
import fortune.core.Claims;
import fortune.core.Line;
import fortune.core.Machine;
import fortune.core.PlayerRecord;
import fortune.core.Settings;
import fortune.core.Stake;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * The running machine for one server. A pull goes: charge the stake, roll, write the claim, the result and the
 * reward (marked pending) to players.json, then hand the spin to the window to animate. The reward is handed over at
 * the reveal ({@link #deliver}), so neither Charter Currency's "+N Valor" line nor items in the inventory give the
 * result away while the reels turn (spec section 9). The reward is fixed and on disk before the first frame, so closing
 * the window reveals and pays at once, logging out or a crash mid spin leaves it pending and it is paid on the next
 * join, and no path can give a second roll that day.
 */
public final class FortuneService {

    /** What a lever pull came to. */
    public sealed interface Pull permits Pull.Spun, Pull.Refused {
        record Spun(Machine.Spin spin, int milestone, boolean stakeFailed) implements Pull {
        }

        record Refused(String message) implements Pull {
        }
    }

    private final MinecraftServer server;
    private final Path configDir;
    private final FortuneStore store;
    private final Machines machines;
    private final JoinPrompt prompt;
    private volatile Machine machine;
    private volatile StakeBank bank;
    private volatile LongSupplier clock = System::currentTimeMillis;
    private final RandomGenerator random = new SecureRandom();
    private final Map<UUID, MachineGui> open = new HashMap<>();

    FortuneService(MinecraftServer server, Path configDir, Path worldDir, Machine machine, StakeBank bank) {
        this.server = server;
        this.configDir = configDir;
        this.machine = machine;
        this.bank = bank;
        this.store = new FortuneStore(worldDir);
        this.machines = new Machines(this, store);
        this.prompt = new JoinPrompt(this);
    }

    // ---- accessors and test seams ----

    public MinecraftServer server() {
        return server;
    }

    public Machine machine() {
        return machine;
    }

    public Settings settings() {
        return machine.settings();
    }

    public StakeBank bank() {
        return bank;
    }

    /** For gametests: a bank that is not Charter Currency. */
    public void useBank(StakeBank replacement) {
        this.bank = replacement;
    }

    /** For gametests: settings other than fortune.json. */
    public void useMachine(Machine replacement) {
        this.machine = replacement;
    }

    /** For gametests: a fixed clock. */
    public void useClock(LongSupplier replacement) {
        this.clock = replacement;
    }

    public long now() {
        return clock.getAsLong();
    }

    public long today() {
        return Calendar.dayOf(now(), settings().rolloverHourUtc);
    }

    public PlayerRecord record(UUID player) {
        return store.player(player);
    }

    public boolean canSpinToday(UUID player) {
        return !store.player(player).claimedOn(today());
    }

    public String nextSpinIn() {
        return Calendar.countdown(Calendar.millisToNextDay(now(), settings().rolloverHourUtc));
    }

    FortuneStore store() {
        return store;
    }

    Machines machines() {
        return machines;
    }

    /** Makes a block a Fortune Machine (the admin command's path). False when it already is one. */
    public boolean addMachine(net.minecraft.world.level.Level level, BlockPos pos) {
        return machines.add(level, pos);
    }

    public boolean removeMachine(net.minecraft.world.level.Level level, BlockPos pos) {
        return machines.remove(level, pos);
    }

    JoinPrompt prompt() {
        return prompt;
    }

    Path configDir() {
        return configDir;
    }

    void reload(Machine fresh) {
        this.machine = fresh;
    }

    // ---- the window ----

    /** Opens the machine for a player, at a Fortune Machine block or anywhere (null). */
    public MachineGui open(ServerPlayer player, BlockPos at) {
        MachineGui old = open.remove(player.getUUID());
        if (old != null && old.isOpen()) old.close();
        MachineGui gui = new MachineGui(this, player, at);
        open.put(player.getUUID(), gui);
        gui.open();
        return gui;
    }

    /**
     * What /spin, the join prompt and a machine block call: the chest, or for a Bedrock player with
     * {@code bedrockMode: "form"} a form that pulls and states the result in words.
     */
    public void openFor(ServerPlayer player, BlockPos at) {
        if ("form".equalsIgnoreCase(settings().bedrockMode) && Bedrock.isBedrock(player.getUUID())) {
            UUID id = player.getUUID();
            String content = canSpinToday(id) ? "Pull the lever?" : "Today's spin is used. Next spin in " + nextSpinIn() + ".";
            boolean sent = Bedrock.modal(server, id, "Fantasy's Fortune", content, "Pull", "Later", button -> {
                ServerPlayer again = server.getPlayerList().getPlayer(id);
                if (button != 0 || again == null || !canSpinToday(id)) return;
                switch (pull(again, at)) {
                    case Pull.Refused refused -> again.sendSystemMessage(Messages.bad(refused.message()));
                    case Pull.Spun spun -> {
                        int[] payline = spun.spin().outcome().payline();
                        StringBuilder reels = new StringBuilder();
                        for (int s : payline) reels.append(reels.isEmpty() ? "" : ", ").append(settings().symbols.get(s).name);
                        Bedrock.modal(server, id, "Fantasy's Fortune", reels + "\n\n" + resultName(spun.spin()) + "\n"
                                + Reveal.payoutText(this, spun.spin().reward()), "OK", "Close", b -> { });
                        Reveal.show(this, again, spun, at);
                    }
                }
            });
            if (sent) return;
        }
        open(player, at);
    }

    void closed(ServerPlayer player, MachineGui gui) {
        open.remove(player.getUUID(), gui);
    }

    // ---- the pull ----

    /**
     * Charges, rolls and saves the spin with its reward pending. Never pays, animates or tells anyone the result: the
     * window or form does that through {@link Reveal}, which pays first. Every refusal is a plain line for the player.
     */
    public Pull pull(ServerPlayer player, BlockPos at) {
        if (store.broken() != null) return new Pull.Refused("The machine is being repaired. Try again later.");
        Settings settings = settings();
        UUID id = player.getUUID();
        PlayerRecord record = store.player(id);
        record.name = player.getPlainTextName();
        long today = today();
        Optional<Claims.Plan> plan = machine.plan(record, today);
        if (plan.isEmpty()) return new Pull.Refused("Today's spin is used. Next spin in " + nextSpinIn() + ".");
        // An earlier reward still pending (an operator reset mid spin) is paid before this spin replaces it.
        deliver(player);
        if (record.totalClaims == 0 && settings.firstSpinMinutes > 0) {
            long played = player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME);
            long needed = settings.firstSpinMinutes * 1200L;
            if (played < needed) {
                long minutes = Math.max(1, (needed - played + 1199) / 1200);
                return new Pull.Refused("Your reels are still warming up. Play " + minutes + " more min, then pull.");
            }
        }

        Settings.StakeTier chosen = stakeOffered() ? Stake.tier(settings, record.stakeTier) : null;
        boolean charged = false;
        if (chosen != null && Stake.affordable(chosen, bank.balance(id))) {
            charged = bank.charge(id, record.name, Stake.costMarks(chosen), Stake.reason(chosen));
        }
        Settings.StakeTier stake = Stake.applied(chosen, charged);

        Machine.Spin spin = machine.decide(plan.get(), stake, random);
        int[] payline = spin.outcome().payline();
        String headline = settings.symbols.get(spin.outcome().headline(settings.symbols)).id;
        int milestone = Claims.apply(record, plan.get(), headline, settings);

        record.lastLine = spin.outcome().line().name();
        record.lastPayline = new ArrayList<>();
        for (int s : payline) record.lastPayline.add(settings.symbols.get(s).id);
        record.lastLevel = spin.reward().level();
        record.lastGolden = plan.get().kind() == Claims.Kind.GOLDEN;
        record.lastWelcomeBack = plan.get().kind() == Claims.Kind.WELCOME_BACK;
        record.payPending = true;
        record.pendingExtras = new ArrayList<>();
        for (Settings.Extra e : spin.reward().extras()) record.pendingExtras.add(new Settings.Extra(e.item, e.count));
        record.pendingKeepsake = spin.outcome().row().jackpot();
        record.pendingMilestone = milestone;
        if (spin.outcome().line().isTriple()) {
            store.setAnnals(Annals.add(store.annals(),
                    new Annals.Win(record.name, machine.resultName(spin.outcome()), spin.reward().level(), today), today));
        }
        if (!store.savePlayers()) {
            FortuneMod.LOG.error("A spin for {} could not be saved; it is still paid at the reveal", record.name);
        }
        store.appendSpin(logLine(player, record, spin, stake, at));
        return new Pull.Spun(spin, milestone, chosen != null && stake == null);
    }

    private final java.util.concurrent.atomic.AtomicInteger zeroPayWarnings = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Called with the result of the payout command. A result of 0 means the Valor did not arrive (Charter Currency
     * has no {@code fortune} earn source, or refused the player), while the reveal still says "+N Valor". Warns
     * once per start so the operator finds out without a log flood. A result below 0 means the command did not run.
     */
    public void noteEarnResult(int result, String command) {
        if (result != 0) return;
        if (zeroPayWarnings.incrementAndGet() == 1) {
            FortuneMod.LOG.warn("The payout command paid nothing: {} . Valor shown at the reveal is not arriving. "
                    + "Check that Charter Currency's earn.json has a \"fortune\" source (newer versions add it by themselves).",
                    command);
        }
    }

    /** How many payouts paid nothing since start; the warning prints on the first. */
    public int zeroPayCount() {
        return zeroPayWarnings.get();
    }

    /** True when the stake buttons are shown: the knob is on and a bank (Charter Currency) is there. */
    public boolean stakeOffered() {
        return settings().stakeEnabled && bank.available() && !settings().stakeTiers.isEmpty();
    }

    /** Remembers a stake choice. Never charged until the lever is pulled. */
    public void chooseStake(UUID player, String tierId) {
        store.player(player).stakeTier = tierId;
        store.savePlayers();
    }

    /** Gives today's spin back. False when the player has not spun today. */
    public boolean reset(UUID player) {
        PlayerRecord record = store.existing(player);
        if (record == null || !Claims.undoToday(record, today())) return false;
        store.savePlayers();
        return true;
    }

    /**
     * Hands over a saved, pending reward: the payout command, items and keepsakes. Called by {@link Reveal} as the
     * result is shown. Does nothing for a player who has left; their reward stays pending for the next join.
     */
    public void deliver(ServerPlayer player) {
        if (player.hasDisconnected()) return;
        PlayerRecord record = store.existing(player.getUUID());
        if (record == null || !record.payPending) return;
        Payout.pay(this, player, record);
        store.savePlayers();
    }

    /** A reward left pending by a logout or crash mid spin is paid on the next join. */
    public void finishPending(ServerPlayer player) {
        PlayerRecord record = store.existing(player.getUUID());
        if (record == null || !record.payPending) return;
        FortuneMod.LOG.info("Finishing an unpaid Fortune spin for {}", player.getPlainTextName());
        Payout.pay(this, player, record);
        store.savePlayers();
        player.sendSystemMessage(Messages.info("Fantasy's Fortune: your last spin was paid out."));
    }

    static boolean currencyLoaded() {
        return FabricLoader.getInstance().isModLoaded("chartercurrency");
    }

    // ---- ticking ----

    void onJoin(ServerPlayer player) {
        finishPending(player);
        PlayerRecord record = store.existing(player.getUUID());
        if (record != null) record.name = player.getPlainTextName();
        if (settings().joinPrompt) prompt.queue(player, server.getTickCount() + settings().joinDelayTicks);
    }

    void tick() {
        prompt.tick(server);
        machines.tick(server);
        if (!open.isEmpty()) for (MachineGui gui : new ArrayList<>(open.values())) gui.serverTick();
    }

    void stop() {
        for (MachineGui gui : new ArrayList<>(open.values())) {
            if (gui.isOpen()) gui.close();
        }
        open.clear();
        store.savePlayers();
    }

    private Map<String, Object> logLine(ServerPlayer player, PlayerRecord record, Machine.Spin spin,
                                        Settings.StakeTier stake, BlockPos at) {
        Map<String, Object> line = new java.util.LinkedHashMap<>();
        line.put("time", Calendar.utc(now()));
        line.put("uuid", player.getUUID().toString());
        line.put("name", record.name);
        line.put("day", spin.plan().day());
        line.put("date", Calendar.iso(spin.plan().day()));
        line.put("kind", spin.plan().kind().name());
        line.put("weekDay", spin.plan().weekDay());
        line.put("result", spin.outcome().line().name());
        line.put("payline", List.copyOf(record.lastPayline));
        line.put("level", spin.reward().level());
        line.put("extras", record.pendingExtras);
        line.put("streak", record.streak);
        line.put("welcomeBack", spin.plan().kind() == Claims.Kind.WELCOME_BACK);
        line.put("stake", stake == null ? "none" : stake.id);
        line.put("stakeSovereigns", stake == null ? 0 : stake.sovereigns);
        line.put("stakeReason", stake == null ? "" : "fortune_stake");
        if (at != null) line.put("machine", at.getX() + "," + at.getY() + "," + at.getZ());
        return line;
    }

    /** The line a result is called in chat and lore, for example "Three Diamonds". */
    String resultName(Machine.Spin spin) {
        return machine.resultName(spin.outcome());
    }

    boolean isTriple(Machine.Spin spin) {
        return spin.outcome().line().atLeast(Line.FIRST_TRIPLE);
    }
}
