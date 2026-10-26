package charter.gametest;

import charter.contract.Basis;
import charter.contract.Contract;
import charter.contract.ContractEvents;
import charter.contract.ContractState;
import charter.contract.Justice;
import charter.contract.Party;
import charter.contract.Term;
import charter.contract.actions.Pardon;
import charter.contract.actions.Sentence;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.mc.cmd.CharterCommands;
import charter.mc.justice.Banishments;
import charter.mc.justice.CellBook;
import charter.mc.justice.Cells;
import charter.mc.justice.Executions;
import charter.mc.justice.Jail;
import charter.mc.justice.JusticeCommands;
import charter.mc.land.Protection;
import charter.org.actions.AcceptInvite;
import charter.org.actions.InviteMember;
import charter.time.Durations;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * M12: justice in the world (PLAN.md §7.4, §10.2 tests T, U and V's world halves,
 * and the cell flood fill refusals). Real players, real blocks and real leads: a
 * lead used on a player is an arrest; the sentencing page is clicked the way a
 * judge would; a prisoner is held by walls, refused pearls, commands and building,
 * and returned when they jump; getting out of the cell is an escape and leaves no
 * breach mark; an execution keeps the inventory and starts a respawn wait; a
 * banished player is pushed off the land; a fine comes from the prisoner's own
 * safe and nowhere else; and the bond works only with its knob on.
 *
 * <p>None of these advance the siege clock. The tests that need a keep to fall are
 * in {@link JusticeSiegeGameTest}.
 */
public final class JusticeGameTest {

    /** Counts command messages (/me) that got past the gate. */
    private static final java.util.concurrent.atomic.AtomicInteger MESSAGES = new java.util.concurrent.atomic.AtomicInteger();

    static {
        net.fabricmc.fabric.api.message.v1.ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) -> MESSAGES.incrementAndGet());
    }

    private static final class Rig implements AutoCloseable {
        final GameTestHelper helper;
        final LandKit kit;
        final CharterRuntime rt = CharterServer.runtime();
        final MinecraftServer server;
        final ServerPlayer lord;
        final ServerPlayer prisoner;
        final PlayerId lordId;
        final PlayerId prisonerId;
        final OrgId org;
        final boolean bondBefore;

        Rig(GameTestHelper helper) {
            this.helper = helper;
            this.kit = new LandKit(helper);
            this.server = kit.server;
            this.bondBefore = rt.knobs.labourTerms;
            this.lord = kit.faction("Judge");
            kit.keep(lord);
            this.org = kit.orgOf(lord);
            this.prisoner = kit.outsider();
            this.lordId = new PlayerId(lord.getUUID());
            this.prisonerId = new PlayerId(prisoner.getUUID());
            rt.state.touch(prisonerId, "Prisoner" + Integer.toHexString(prisoner.getUUID().hashCode()), rt.clock.now());
            Jail.forgetAll();
        }

        void check(boolean ok, String why) {
            helper.assertTrue(ok, why);
        }

        void stand(ServerPlayer player, int dx, int dz) {
            BlockPos at = kit.pos(dx, dz);
            player.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        }

        void standAt(ServerPlayer player, BlockPos at) {
            player.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        }

        /** A stone box with 3 by 2 by 3 of air inside, its south west inside corner at (dx, dz). Returns the middle of the floor. */
        BlockPos room(int dx, int dz) {
            return box(dx, dz, 3, 2, 3);
        }

        BlockPos box(int dx, int dz, int w, int h, int d) {
            BlockPos at = kit.pos(dx, dz);
            for (int x = -1; x <= w; x++) {
                for (int y = -1; y <= h; y++) {
                    for (int z = -1; z <= d; z++) {
                        boolean inside = x >= 0 && x < w && y >= 0 && y < h && z >= 0 && z < d;
                        kit.level.setBlockAndUpdate(at.offset(x, y, z), inside ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
                    }
                }
            }
            return at.offset(w / 2, 0, d / 2);
        }

        CellBook.Cell markCell(String name, BlockPos stand) {
            standAt(lord, stand);
            Cells.Done done = Cells.mark(lord, name);
            check(done.ok(), "The cell was not marked: " + done.text());
            return Cells.book().all().stream().filter(c -> c.name.equals(name) && c.keep.equals(keepId())).findFirst().orElseThrow();
        }

        charter.id.CoreId keepId() {
            return rt.state.coresOf(org).getFirst().id;
        }

        /** The officer uses a lead on a player, as the event the server fires for a right click on an entity. */
        InteractionResult lead(ServerPlayer officer, ServerPlayer target) {
            officer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEAD));
            return UseEntityCallback.EVENT.invoker().interact(officer, kit.level, InteractionHand.MAIN_HAND, target, null);
        }

        /** The lord arrests the prisoner at (dx, dz) in the keep. */
        void arrest(ServerPlayer who, int dx, int dz) {
            stand(lord, dx, dz - 1);
            stand(who, dx, dz);
            lead(lord, who);
            check(rt.state.contracts.custodyOf(new PlayerId(who.getUUID())).isPresent(), "The lead did not take " + who.getName().getString() + " into custody");
        }

        /** Clicks the newest link with this label shown to the player, by running its token the way the client would. */
        void click(ServerPlayer player, String label) {
            String token = CharterCommands.tokens().newest(player.getUUID(), label).orElse(null);
            check(token != null, "No [" + label + "] link was shown");
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "charter act " + token);
        }

        boolean mailed(PlayerId player, String fragment) {
            return rt.mail.peek(player).stream().anyMatch(m -> m.text().contains(fragment));
        }

        Contract latest(PlayerId player) {
            Contract found = null;
            for (Contract c : rt.state.contracts.all()) {
                if (c.basis == Basis.CUSTODY && c.subject.equals(Party.of(player))) found = c;
            }
            check(found != null, "No sentence was imposed");
            return found;
        }

        ServerPlayer recruit() {
            ServerPlayer other = kit.outsider();
            PlayerId id = new PlayerId(other.getUUID());
            rt.state.touch(id, "Recruit" + Integer.toHexString(other.getUUID().hashCode()), rt.clock.now());
            check(rt.engine.run(new Actor.Player(lordId), new InviteMember(org, id)).done(), "Invite refused");
            check(rt.engine.run(new Actor.Player(id), new AcceptInvite(id, org)).done(), "Accept refused");
            return other;
        }

        int diamonds(ServerPlayer player) {
            int n = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(Items.DIAMOND)) n += stack.getCount();
            }
            return n;
        }

        @Override
        public void close() {
            rt.knobs.labourTerms = bondBefore;
            for (CellBook.Cell cell : Cells.book().of(keepId())) Cells.book().remove(cell.keep, cell.name);
            for (ServerPlayer p : List.of(lord, prisoner)) {
                Jail.forget(p.getUUID());
                Banishments.forget(p.getUUID());
                Executions.forget(p.getUUID());
            }
            kit.close();
        }
    }

    // ---- arrest ----

    @GameTest(maxTicks = 100)
    public void aLeadArrestsOnTheFactionsLandAndLeashesThePrisoner(GameTestHelper helper) {
        try (Rig r = new Rig(helper)) {
            // On neutral ground the lead does nothing.
            r.stand(r.lord, -8, -1);
            r.stand(r.prisoner, -8, 0);
            r.lead(r.lord, r.prisoner);
            r.check(r.rt.state.contracts.custodyOf(r.prisonerId).isEmpty(), "A lead arrested someone on neutral ground");

            // A stranger with a lead has no authority, even on the keep.
            ServerPlayer stranger = r.kit.outsider();
            r.stand(r.prisoner, 5, 5);
            r.lead(stranger, r.prisoner);
            r.check(r.rt.state.contracts.custodyOf(r.prisonerId).isEmpty(), "A stranger arrested someone on a keep");

            // The Lord arrests on their own land.
            r.stand(r.lord, 5, 4);
            r.lead(r.lord, r.prisoner);
            r.check(r.rt.state.contracts.custodyOf(r.prisonerId).isPresent(), "The Lord's lead did not take a player into custody");
            r.check(r.rt.state.history.has(ContractEvents.Arrested.class), "The arrest was not recorded");
            r.check(CharterCommands.tokens().newest(r.lord.getUUID(), Text.get("justice.link_execute")).isPresent(),
                    "The officer was not shown the sentencing page after the arrest");

            // Held a second time is refused.
            r.check(Jail.held(r.prisonerId), "A prisoner in custody should count as held");
            r.lead(r.lord, r.prisoner);
            r.check(r.rt.state.contracts.custodies().size() >= 1, "Custody vanished on a second lead");

            // The leash: past 6 blocks the prisoner is pulled back toward the officer.
            r.stand(r.prisoner, 5, 12);
            r.stand(r.lord, 5, 0);
            double before = r.lord.position().distanceTo(r.prisoner.position());
            Jail.enforce(r.prisoner);
            double after = r.lord.position().distanceTo(r.prisoner.position());
            r.check(before > Jail.LEASH, "The test did not put the prisoner out of reach");
            r.check(after <= Jail.LEASH, "The leash did not pull the prisoner back: " + after + " blocks from the officer");
            r.check(after > 0.5, "The leash put the prisoner inside the officer: " + after);
            helper.succeed();
        }
    }

    // ---- cells ----

    @GameTest(maxTicks = 100)
    public void onlyAJudgeMarksACellAndOnlyAClosedRoomIsOne(GameTestHelper helper) {
        try (Rig r = new Rig(helper)) {
            // Neutral ground is nobody's keep.
            r.stand(r.lord, -8, 0);
            Cells.Done neutral = Cells.mark(r.lord, "Nowhere");
            r.check(!neutral.ok() && neutral.text().equals(Text.get("cell.not_your_keep")), "A cell was marked outside a keep: " + neutral.text());

            // An open field is not enclosed.
            r.stand(r.lord, 5, 5);
            Cells.Done open = Cells.mark(r.lord, "Field");
            r.check(!open.ok(), "An open field was marked as a cell");
            r.check(open.text().equals(Text.get("cell.leaks")) || open.text().equals(Text.get("cell.too_big", "max", Cells.MAX_BLOCKS)),
                    "The open field was refused for the wrong reason: " + open.text());

            // A room with a hole in the ceiling is not enclosed either.
            BlockPos stand = r.room(2, 2);
            r.kit.level.setBlockAndUpdate(stand.offset(0, 2, 0), Blocks.AIR.defaultBlockState());
            r.standAt(r.lord, stand);
            Cells.Done holed = Cells.mark(r.lord, "Holed");
            r.check(!holed.ok(), "A room open to the sky was marked as a cell");
            r.kit.level.setBlockAndUpdate(stand.offset(0, 2, 0), Blocks.STONE.defaultBlockState());

            // A room with an open door is not closed.
            r.kit.level.setBlockAndUpdate(stand.offset(-2, 0, 0), Blocks.AIR.defaultBlockState());
            r.kit.level.setBlockAndUpdate(stand.offset(-2, 1, 0), Blocks.AIR.defaultBlockState());
            Cells.Done doored = Cells.mark(r.lord, "Doored");
            r.check(!doored.ok(), "A room with an open doorway was marked as a cell");
            r.kit.level.setBlockAndUpdate(stand.offset(-2, 0, 0), Blocks.STONE.defaultBlockState());
            r.kit.level.setBlockAndUpdate(stand.offset(-2, 1, 0), Blocks.STONE.defaultBlockState());

            // A hall too big to be a cell, though walled all round.
            BlockPos hall = r.box(18 + 0, -6, 12, 4, 12);
            r.standAt(r.lord, hall);
            Cells.Done big = Cells.mark(r.lord, "Hall");
            r.check(!big.ok() && big.text().equals(Text.get("cell.too_big", "max", Cells.MAX_BLOCKS)), "A walled hall of 576 blocks was marked: " + big.text());
            r.check(Cells.book().of(r.keepId()).isEmpty(), "A refused cell was still recorded");

            // Only a judge marks one.
            ServerPlayer recruit = r.recruit();
            r.standAt(recruit, stand);
            Cells.Done noPower = Cells.mark(recruit, "Mine");
            r.check(!noPower.ok() && noPower.text().equals(Text.get("cell.no_power")), "A member without the judge power marked a cell: " + noPower.text());

            // The closed room, marked by the Lord.
            r.standAt(r.lord, stand);
            Cells.Done good = Cells.mark(r.lord, "North");
            r.check(good.ok(), "A closed room was refused: " + good.text());
            CellBook.Cell cell = Cells.book().get(r.keepId(), "North");
            r.check(cell != null && cell.blocks.size() == 18, "The cell should be the 18 blocks of air, was " + (cell == null ? -1 : cell.blocks.size()));
            r.check(!Cells.mark(r.lord, "north").ok(), "A second cell took the name of the first");
            r.check(!Cells.remove(recruit, "North").ok(), "A member without the judge power removed a cell");

            // A cell with a prisoner in it stays.
            r.arrest(r.prisoner, 5, 6);
            JusticeCommands.sentencePage(r.lord, r.rt, r.prisonerId);
            r.click(r.lord, Chat.duration(Durations.hours(1)));
            r.check(Justice.holding(r.rt.state, r.rt.knobs, r.prisonerId, r.rt.clock.now()).isPresent(), "The sentence did not hold the prisoner");
            Cells.Done occupied = Cells.remove(r.lord, "North");
            r.check(!occupied.ok() && occupied.text().equals(Text.get("cell.occupied", "name", "North")), "A cell holding a prisoner was removed: " + occupied.text());
            r.rt.engine.run(new Actor.Player(r.lordId), new charter.contract.actions.Release(r.org, r.prisonerId));
            r.check(Cells.remove(r.lord, "North").ok(), "An empty cell could not be removed");
            helper.succeed();
        }
    }

    // ---- confinement and escape (test T, gametest half) ----

    @GameTest(maxTicks = 200)
    public void aConfinedPrisonerIsHeldAndGettingOutIsAnEscapeNotABreach(GameTestHelper helper) {
        try (Rig r = new Rig(helper)) {
            BlockPos stand = r.room(2, 2);
            CellBook.Cell cell = r.markCell("Hole", stand);
            r.arrest(r.prisoner, 8, 0);
            JusticeCommands.sentencePage(r.lord, r.rt, r.prisonerId);
            r.click(r.lord, Chat.duration(Durations.hours(1)));

            Contract sentence = r.latest(r.prisonerId);
            r.check(sentence.term(Term.Confinement.class) != null && sentence.term(Term.Confinement.class).cell().equals("Hole"), "The sentence is not a confinement in Hole");
            r.check(Jail.inside(cell, r.prisoner), "The prisoner was not moved into the cell");
            r.check(r.rt.state.contracts.custodyOf(r.prisonerId).isEmpty(), "Custody lingers after the sentence");

            // The walls: a prisoner may not break them, or use anything.
            BlockPos wall = stand.offset(2, 0, 0);
            r.check(!Protection.mayAct(r.prisoner, r.kit.level, wall), "A prisoner may act on the cell wall");
            r.prisoner.gameMode.destroyBlock(wall);
            r.check(r.kit.level.getBlockState(wall).is(Blocks.STONE), "A prisoner broke the cell wall");

            // Pearls are refused to the held, and not to the free.
            r.prisoner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_PEARL));
            r.check(UseItemCallback.EVENT.invoker().interact(r.prisoner, r.kit.level, InteractionHand.MAIN_HAND) == InteractionResult.FAIL,
                    "A prisoner threw an ender pearl");
            ServerPlayer free = r.kit.outsider();
            free.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_PEARL));
            r.check(UseItemCallback.EVENT.invoker().interact(free, r.kit.level, InteractionHand.MAIN_HAND) != InteractionResult.FAIL,
                    "A free player was refused an ender pearl");
            r.prisoner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

            // Commands: /me is open to everyone, so a held player running it shows the gate works; a free player's goes through.
            ServerPlayer talker = r.kit.outsider();
            MESSAGES.set(0);
            r.server.getCommands().performPrefixedCommand(r.prisoner.createCommandSourceStack(), "me rattles the bars");
            r.check(MESSAGES.get() == 0, "A held player ran /me");
            r.server.getCommands().performPrefixedCommand(talker.createCommandSourceStack(), "me whistles");
            r.check(MESSAGES.get() == 1, "The control player's /me did not reach the event, so the test proves nothing: " + MESSAGES.get());
            r.server.getCommands().performPrefixedCommand(r.prisoner.createCommandSourceStack(), "charter safe");

            // A jump (a teleport or a command from another mod) is put back; so is a jump out of the keep.
            r.stand(r.prisoner, 40, 3);
            Jail.enforce(r.prisoner);
            r.check(Jail.inside(cell, r.prisoner), "A teleport within the keep was not put back");
            r.stand(r.prisoner, -20, 3);
            Jail.enforce(r.prisoner);
            r.check(Jail.inside(cell, r.prisoner), "A teleport out of the keep was not put back");
            r.check(Justice.holding(r.rt.state, r.rt.knobs, r.prisonerId, r.rt.clock.now()).isPresent(), "A jump counted as an escape");

            // Walls come down (the faction's own judge opens the cell) and the prisoner walks out: an escape.
            r.check(r.lord.gameMode.destroyBlock(wall), "The Lord could not open the cell");
            r.standAt(r.prisoner, wall);
            Jail.enforce(r.prisoner);
            r.check(sentence.state() == ContractState.RESISTED, "Walking out of the cell should resist the sentence, it is " + sentence.state());
            r.check(Justice.holding(r.rt.state, r.rt.knobs, r.prisonerId, r.rt.clock.now()).isEmpty(), "An escaped prisoner is still held");
            r.check(r.rt.state.history.has(ContractEvents.Escaped.class), "The escape was not recorded");
            r.check(r.rt.state.contracts.breachMarks(Party.of(r.prisonerId)) == 0, "Escaping marked the prisoner as a breaker");
            r.check(r.rt.state.contracts.breachMarks(Party.of(r.org)) == 0, "Escaping marked the jailers");
            r.check(r.rt.state.contracts.punishments.counted(r.prisonerId, r.rt.clock.now()) < Durations.minutes(1),
                    "An escaped sentence should count only the time served toward the week");

            // Free now: a jump is no longer put back.
            r.stand(r.prisoner, -20, 3);
            Jail.enforce(r.prisoner);
            r.check(!Jail.inside(cell, r.prisoner), "An escaped prisoner was put back in the cell");
            helper.succeed();
        }
    }

    // ---- notices to a prisoner who is away ----

    @GameTest(maxTicks = 100)
    public void anAbsentPrisonerHearsByMailOnJoin(GameTestHelper helper) {
        try (Rig r = new Rig(helper)) {
            BlockPos stand = r.room(2, 2);
            r.markCell("Hole", stand);
            PlayerId away = new PlayerId(java.util.UUID.randomUUID());
            r.rt.state.touch(away, "Away" + Integer.toHexString(away.value().hashCode()), r.rt.clock.now());
            ChunkKey where = new ChunkKey("minecraft:overworld", r.kit.pos(5, 5).getX() >> 4, r.kit.pos(5, 5).getZ() >> 4);
            r.check(r.rt.engine.run(new Actor.Player(r.lordId), new charter.contract.actions.Arrest(r.org, away, where)).done(), "Arrest refused");
            r.check(r.mailed(away, "custody"), "The absent prisoner was not told of the custody");
            r.check(r.rt.engine.run(new Actor.Player(r.lordId), new charter.contract.actions.Confine(r.org, away, Durations.hours(2), r.keepId(), "Hole")).done(),
                    "Confinement refused");
            r.check(r.mailed(away, "sentenced"), "The absent prisoner was not told of the sentence");
            r.check(r.mailed(away, "Hole"), "The sentence did not name the cell");
            r.check(r.rt.engine.run(new Actor.Player(r.lordId), new Pardon(r.org, away)).done(), "Pardon refused");
            r.check(r.mailed(away, "pardoned"), "The absent prisoner was not told of the pardon");
            helper.succeed();
        }
    }

    // ---- release, pardon ----

    @GameTest(maxTicks = 200)
    public void aReleaseAndAPardonLetThePrisonerOutOfTheCell(GameTestHelper helper) {
        try (Rig r = new Rig(helper)) {
            BlockPos stand = r.room(2, 2);
            CellBook.Cell cell = r.markCell("Hole", stand);

            r.arrest(r.prisoner, 8, 0);
            JusticeCommands.sentencePage(r.lord, r.rt, r.prisonerId);
            r.click(r.lord, Chat.duration(Durations.hours(6)));
            r.check(Jail.inside(cell, r.prisoner), "The prisoner was not put in the cell");
            JusticeCommands.justicePage(r.lord, r.rt);
            r.click(r.lord, Text.get("justice.link_release"));
            r.check(Justice.holding(r.rt.state, r.rt.knobs, r.prisonerId, r.rt.clock.now()).isEmpty(), "A release left the prisoner held");
            Jail.enforce(r.prisoner);
            r.check(!Jail.inside(cell, r.prisoner), "A released prisoner was left in the cell");
            r.check(r.latest(r.prisonerId).state() == ContractState.ENDED, "A release ends the sentence, it is " + r.latest(r.prisonerId).state());
            r.check(r.rt.state.contracts.breachMarks(Party.of(r.prisonerId)) == 0, "A release marked the prisoner");

            ServerPlayer second = r.kit.outsider();
            PlayerId secondId = new PlayerId(second.getUUID());
            r.rt.state.touch(secondId, "Second" + Integer.toHexString(second.getUUID().hashCode()), r.rt.clock.now());
            r.arrest(second, 9, 0);
            r.rt.engine.run(new Actor.Player(r.lordId), new Sentence(r.org, secondId,
                    List.of(new Term.Confinement(Durations.hours(2), r.keepId(), "Hole"))));
            Jail.enforce(second);
            r.check(Jail.inside(cell, second), "The second prisoner was not put in the cell");
            r.check(r.rt.engine.run(new Actor.Player(r.lordId), new Pardon(r.org, secondId)).done(), "The Lord could not pardon");
            Jail.enforce(second);
            r.check(!Jail.inside(cell, second), "A pardoned prisoner was left in the cell");
            helper.succeed();
        }
    }

    // ---- execution and banishment ----

    @GameTest(maxTicks = 200)
    public void anExecutionKeepsTheInventoryWaitsOutTheRespawnAndBanishes(GameTestHelper helper) {
        try (Rig r = new Rig(helper)) {
            r.prisoner.getInventory().add(new ItemStack(Items.DIAMOND, 3));
            r.prisoner.getInventory().add(new ItemStack(Items.STICK, 5));
            r.prisoner.experienceLevel = 5;
            r.arrest(r.prisoner, 5, 6);
            JusticeCommands.sentencePage(r.lord, r.rt, r.prisonerId);
            r.click(r.lord, Text.get("justice.link_execute"));

            r.check(r.latest(r.prisonerId).term(Term.RespawnDelay.class) != null, "The execution was not imposed");
            if (r.prisoner.isAlive()) Executions.carryOut(r.prisonerId); // surfaces an exception the history listener would swallow
            r.check(!r.prisoner.isAlive(), "The executed player is still alive");
            AABB around = new AABB(r.prisoner.blockPosition()).inflate(8);
            r.check(r.kit.level.getEntitiesOfClass(ItemEntity.class, around).isEmpty(), "The executed player dropped their items");

            ServerPlayer fresh = r.server.getPlayerList().respawn(r.prisoner, false, Entity.RemovalReason.KILLED);
            GameType modeBefore = fresh.gameMode();
            r.check(r.diamonds(fresh) == 3, "The executed player lost their diamonds: " + r.diamonds(fresh));
            r.check(fresh.getInventory().contains(s -> s.is(Items.STICK) && s.getCount() == 5), "The executed player lost their sticks");
            r.check(fresh.experienceLevel == 5, "The executed player lost their experience: " + fresh.experienceLevel);

            long left = Justice.respawnDelayLeft(r.rt.state, r.rt.knobs, r.prisonerId, r.rt.clock.now());
            r.check(left > Durations.minutes(4) && left <= Durations.minutes(5), "The respawn wait should be about 5 minutes, it is " + left);
            Executions.enforce(fresh);
            r.check(Executions.waiting(fresh.getUUID()), "The executed player is not waiting out the delay");
            r.check(fresh.gameMode() == GameType.ADVENTURE, "The wait should be in adventure mode, not " + fresh.gameMode());
            r.check(fresh.hasEffect(MobEffects.SLOWNESS) && fresh.hasEffect(MobEffects.BLINDNESS), "The waiting player is not slowed and blinded");
            r.check(Executions.bar(fresh.getUUID()) != null && Executions.bar(fresh.getUUID()).getPlayers().contains(fresh), "The wait shows no boss bar");
            r.check(Executions.bar(fresh.getUUID()).getProgress() > 0.9F, "A new wait should show a nearly full bar");

            // Banishment is the other half: the execution imposed it with the wait.
            Term.Banishment banishment = r.latest(r.prisonerId).term(Term.Banishment.class);
            r.check(banishment != null && banishment.durationMillis() == Durations.DAY, "The execution should banish for a day");
            r.check(Justice.banished(r.rt.state, r.rt.knobs, r.prisonerId, r.org, r.rt.clock.now()), "The executed player is not banished");

            // The wait ends with the contract: a pardon ends it and gives the player back their mode.
            r.check(r.rt.engine.run(new Actor.Player(r.lordId), new Pardon(r.org, r.prisonerId)).done(), "The Lord could not pardon the executed player");
            Executions.enforce(fresh);
            r.check(!Executions.waiting(fresh.getUUID()), "The wait went on after the pardon");
            r.check(fresh.gameMode() == modeBefore, "The mode was not given back: " + fresh.gameMode());
            r.check(!fresh.hasEffect(MobEffects.SLOWNESS), "The slowness stayed after the wait");
            r.check(!Justice.banished(r.rt.state, r.rt.knobs, r.prisonerId, r.org, r.rt.clock.now()), "A pardoned player was still banished");
            Executions.forget(fresh.getUUID());
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void banishmentPushesAPlayerOffTheLandItNamesAndNoOther(GameTestHelper helper) {
        try (Rig r = new Rig(helper)) {
            r.arrest(r.prisoner, 5, 6);
            Outcome sentenced = r.rt.engine.run(new Actor.Player(r.lordId), new Sentence(r.org, r.prisonerId,
                    List.of(new Term.Banishment(r.org, Durations.hours(30)))));
            r.check(sentenced.done(), "The banishment was refused");
            Term.Banishment term = r.latest(r.prisonerId).term(Term.Banishment.class);
            r.check(term.durationMillis() == Durations.DAY, "A banishment over a day should be cut to a day, it is " + term.durationMillis());

            // Walking in from neutral ground: pushed back to where they stood.
            Banishments.forget(r.prisoner.getUUID());
            r.stand(r.prisoner, -6, 0);
            Banishments.enforce(r.prisoner, 0);
            r.check(!Jail.onKeepLand(r.prisoner, r.keepId()), "The banished player was not on neutral ground to begin with");
            r.stand(r.prisoner, 3, 0);
            Banishments.enforce(r.prisoner, 1);
            r.check(!Jail.onKeepLand(r.prisoner, r.keepId()), "A banished player walked into the keep");
            r.check(r.prisoner.getX() < r.kit.border, "The banished player was not sent back the way they came: x=" + r.prisoner.getX());

            // Arriving with nowhere to go back to: the nearest ground that is not the land.
            Banishments.forget(r.prisoner.getUUID());
            r.stand(r.prisoner, 24, 0);
            Banishments.enforce(r.prisoner, 2);
            r.check(!Jail.onKeepLand(r.prisoner, r.keepId()), "A banished player who logged in inside the keep stayed there");

            // A banishment is no confinement: the player is free to do everything else, and members are not pushed.
            r.check(!Jail.held(r.prisonerId), "A banished player counts as held");
            r.stand(r.lord, 24, 0);
            Banishments.enforce(r.lord, 3);
            r.check(Jail.onKeepLand(r.lord, r.keepId()), "The jailer was pushed off their own land");
            helper.succeed();
        }
    }

    // ---- fines ----

    @GameTest(maxTicks = 100)
    public void aFineComesFromThePrisonersOwnSafeAndNowhereElse(GameTestHelper helper) {
        try (Rig r = new Rig(helper)) {
            r.prisoner.getInventory().add(new ItemStack(Items.DIAMOND, 30));
            r.check(JusticeCommands.deposit(r.prisoner, 20), "The deposit into the safe failed");
            r.check(r.diamonds(r.prisoner) == 10, "Diamonds were not taken for the deposit: " + r.diamonds(r.prisoner));
            r.check(r.rt.state.contracts.vault(r.prisonerId).balance(charter.treasury.Currency.DIAMOND) == 20, "The safe does not hold the deposit");
            long treasury = r.rt.state.org(r.org).orElseThrow().ledger.balance(charter.treasury.Currency.DIAMOND);

            r.arrest(r.prisoner, 5, 6);
            JusticeCommands.sentencePage(r.lord, r.rt, r.prisonerId);
            r.click(r.lord, Text.get("justice.link_fine", "count", 16));
            r.check(r.rt.state.contracts.vault(r.prisonerId).balance(charter.treasury.Currency.DIAMOND) == 4, "The fine should take 16 of 20 from the safe");
            r.check(r.rt.state.org(r.org).orElseThrow().ledger.balance(charter.treasury.Currency.DIAMOND) == treasury + 16, "The fine did not reach the jailers' treasury");
            r.check(r.diamonds(r.prisoner) == 10, "A fine took diamonds the prisoner carried");
            r.check(r.rt.state.contracts.custodyOf(r.prisonerId).isEmpty(), "The prisoner is still in custody after the fine");

            // Nothing in the safe, nothing taken, whatever they carry.
            ServerPlayer broke = r.kit.outsider();
            PlayerId brokeId = new PlayerId(broke.getUUID());
            r.rt.state.touch(brokeId, "Broke" + Integer.toHexString(broke.getUUID().hashCode()), r.rt.clock.now());
            broke.getInventory().add(new ItemStack(Items.DIAMOND, 40));
            r.arrest(broke, 6, 6);
            JusticeCommands.sentencePage(r.lord, r.rt, brokeId);
            r.click(r.lord, Text.get("justice.link_fine", "count", 4));
            r.check(r.diamonds(broke) == 40, "A player with an empty safe lost carried diamonds to a fine");
            r.check(r.rt.state.org(r.org).orElseThrow().ledger.balance(charter.treasury.Currency.DIAMOND) == treasury + 16, "A fine on an empty safe still paid the treasury");
            helper.succeed();
        }
    }

    // ---- weekly cap (test U, world half) ----

    @GameTest(maxTicks = 100)
    public void theWeeklyCapCutsASecondFactionsSentence(GameTestHelper helper) {
        try (Rig r = new Rig(helper)) {
            BlockPos stand = r.room(2, 2);
            r.markCell("Hole", stand);
            // Another faction already had them 16 hours this week.
            long now = r.rt.clock.now();
            OrgId other = r.rt.state.ids.org();
            r.rt.state.contracts.punishments.record(r.rt.state.ids.contract(), r.prisonerId, other, now - Durations.hours(1), Durations.hours(16));
            long left = r.rt.state.contracts.punishments.remaining(r.prisonerId, now, r.rt.knobs);
            r.check(left == Durations.hours(8), "The week should have 8 hours left, it has " + left);

            r.arrest(r.prisoner, 8, 0);
            JusticeCommands.sentencePage(r.lord, r.rt, r.prisonerId);
            r.click(r.lord, Chat.duration(Durations.hours(24)));
            Term.Confinement held = r.latest(r.prisonerId).term(Term.Confinement.class);
            r.check(held != null && held.durationMillis() == Durations.hours(8), "A 24 hour sentence should be cut to the 8 hours left, it is "
                    + (held == null ? "missing" : held.durationMillis()));
            r.check(r.rt.state.contracts.punishments.remaining(r.prisonerId, r.rt.clock.now(), r.rt.knobs) <= 0, "The week should be used up");

            // Letting them go early gives back the time not served.
            r.rt.engine.run(new Actor.Player(r.lordId), new charter.contract.actions.Release(r.org, r.prisonerId));
            r.check(r.rt.state.contracts.punishments.remaining(r.prisonerId, r.rt.clock.now(), r.rt.knobs) > 0,
                    "A release should give back the time not served");
            helper.succeed();
        }
    }

    // ---- the bond (D12) ----

    @GameTest(maxTicks = 200)
    public void theBondIsOffByDefaultAndTethersThePrisonerWithTheKnobOn(GameTestHelper helper) {
        try (Rig r = new Rig(helper)) {
            r.check(!r.rt.knobs.labourTerms, "The bond must be off by default");
            r.arrest(r.prisoner, 5, 6);
            Outcome off = r.rt.engine.run(new Actor.Player(r.lordId), new Sentence(r.org, r.prisonerId,
                    List.of(new Term.Tether(Durations.hours(2), r.keepId()))));
            r.check(!off.done() && off.toString().contains("bond_off"), "A bond was accepted with the knob off: " + off);
            JusticeCommands.sentencePage(r.lord, r.rt, r.prisonerId);
            r.check(CharterCommands.tokens().newest(r.lord.getUUID(), Chat.duration(Durations.hours(1))).isEmpty(),
                    "The page offered a bond or a cell link that cannot be used with the knob off");

            r.rt.knobs.labourTerms = true;
            JusticeCommands.sentencePage(r.lord, r.rt, r.prisonerId);
            r.click(r.lord, Chat.duration(Durations.hours(1)));
            Contract bond = r.latest(r.prisonerId);
            r.check(bond.term(Term.Tether.class) != null, "The page's bond link did not impose a bond");
            r.check(Justice.prisoners(r.rt.state, r.rt.knobs, r.keepId(), r.rt.clock.now()) == 1, "A bonded player should be a mouth on the keep");

            // On the keep's land the bonded player is free to move and to work it.
            r.stand(r.prisoner, 5, 6);
            Jail.enforce(r.prisoner);
            BlockPos work = r.kit.pos(7, 6);
            r.kit.level.setBlockAndUpdate(work, Blocks.DIRT.defaultBlockState());
            r.check(Protection.mayAct(r.prisoner, r.kit.level, work), "A bonded player may not work the land they are bonded to");
            r.check(r.prisoner.gameMode.destroyBlock(work), "A bonded player could not break a block on the jailer's land");
            ServerPlayer plain = r.kit.outsider();
            r.check(!Protection.mayAct(plain, r.kit.level, work), "An ordinary outsider may act on the keep");

            // Stepping off the land is pushed back; a jump away is put at the keep's gate.
            r.stand(r.prisoner, -3, 6);
            Jail.enforce(r.prisoner);
            r.check(Jail.onKeepLand(r.prisoner, r.keepId()), "A bonded player left the keep's land");
            r.stand(r.prisoner, -80, 6);
            Jail.enforce(r.prisoner);
            r.check(Jail.onKeepLand(r.prisoner, r.keepId()), "A teleported bonded player was not brought back to the keep");
            r.check(Justice.holding(r.rt.state, r.rt.knobs, r.prisonerId, r.rt.clock.now()).isPresent(), "The bond ended by itself");
            r.check(r.rt.state.contracts.breachMarks(Party.of(r.prisonerId)) == 0, "A bond left a breach mark");
            helper.succeed();
        }
    }
}
