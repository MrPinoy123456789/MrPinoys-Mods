package charter.mc.justice;

import charter.contract.Contract;
import charter.contract.ContractEvents;
import charter.contract.Term;
import charter.contract.actions.Arrest;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.history.History;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Claim;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.mc.land.Protection;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;

/**
 * M12: justice in the world (PLAN.md §7.4). Wires the pieces to the server: a lead
 * used on a player is an arrest; every tick {@link Jail}, {@link Executions} and
 * {@link Banishments} look at each player; pearls and chorus fruit are refused to
 * the held; a prisoner may not build or break, and a bonded one may work the
 * jailer's land; and the history events of a sentence put it into effect (the
 * prisoner into the cell, the executed player to the executioner's wait).
 *
 * <p>Core decides every rule. A handler here asks core and applies the answer.
 */
public final class JusticeMod {

    private static long tick;

    private JusticeMod() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!CharterServer.running()) return;
            CharterServer.runtime().state.history.listen(JusticeMod::onHistory);
            Jail.forgetAll();
        });
        ServerTickEvents.END_SERVER_TICK.register(JusticeMod::onTick);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!(player instanceof ServerPlayer officer) || !(entity instanceof ServerPlayer target)) return InteractionResult.PASS;
            if (!officer.getItemInHand(hand).is(Items.LEAD) || !CharterServer.running()) return InteractionResult.PASS;
            arrest(officer, target);
            return InteractionResult.SUCCESS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (player instanceof ServerPlayer sp && (stack.is(Items.ENDER_PEARL) || stack.is(Items.CHORUS_FRUIT))
                    && Jail.held(new PlayerId(sp.getUUID()))) {
                sp.sendSystemMessage(Component.literal(Text.get("jail.no_teleport")).withStyle(ChatFormatting.RED), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        ServerPlayerEvents.COPY_FROM.register((old, fresh, alive) -> Executions.copyFrom(old, fresh));
        ServerPlayerEvents.AFTER_RESPAWN.register((old, fresh, alive) -> Executions.respawned(old));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Executions.left(handler.player);
            Jail.forget(handler.player.getUUID());
            Banishments.forget(handler.player.getUUID());
        });
        Protection.addActorRule(JusticeMod::actorRule);
        JusticeCommands.register();
    }

    private static void onTick(MinecraftServer server) {
        tick++;
        if (!CharterServer.running()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Jail.enforce(player);
            Executions.enforce(player);
            Banishments.enforce(player, tick);
        }
    }

    /** A prisoner (in a cell or in custody) may not build or break; a bonded player may work the keep they are bonded to. */
    private static Boolean actorRule(Player player, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, Claim claim) {
        if (!CharterServer.running()) return null;
        CharterRuntime rt = CharterServer.runtime();
        PlayerId id = new PlayerId(player.getUUID());
        Optional<Contract> holding = Jail.holding(rt, id);
        if (holding.isPresent()) {
            if (holding.get().term(Term.Confinement.class) != null) return Boolean.FALSE;
            return claim != null && claim.core() != null && claim.core().equals(holding.get().keep) ? Boolean.TRUE : null;
        }
        return Jail.custodyOf(rt, id).isPresent() ? Boolean.FALSE : null;
    }

    // ---- arrest ----

    /** A lead used on a player: an arrest, if the officer's faction holds the ground the target stands on. */
    public static void arrest(ServerPlayer officer, ServerPlayer target) {
        CharterRuntime rt = CharterServer.runtime();
        PlayerId me = new PlayerId(officer.getUUID());
        PlayerId them = new PlayerId(target.getUUID());
        rt.state.touch(them, target.getPlainTextName(), rt.clock.now());
        var membership = rt.state.membership(me);
        if (membership.isEmpty()) {
            officer.sendSystemMessage(Chat.bad(Text.get("page.no_org")));
            return;
        }
        OrgId org = membership.get().org();
        ChunkKey where = new ChunkKey(Protection.dimension(target.level()), target.blockPosition().getX() >> 4, target.blockPosition().getZ() >> 4);
        Outcome outcome = rt.engine.run(new Actor.Player(me), new Arrest(org, them, where));
        if (outcome instanceof Outcome.Refused(Reason reason)) {
            officer.sendSystemMessage(Chat.bad(JusticeCommands.reasonText(reason)));
            return;
        }
        officer.sendSystemMessage(Chat.good(Text.get("arrest.done", "player", rt.describe.player(them))));
        JusticeCommands.sentencePage(officer, rt, them);
    }

    // ---- sentences take effect ----

    static void onHistory(History.Entry entry) {
        if (!CharterServer.running()) return;
        MinecraftServer server = CharterServer.server();
        if (server == null) return;
        switch (entry.event()) {
            case ContractEvents.Imposed e -> {
                if (!e.subject().isPlayer()) return;
                ServerPlayer prisoner = server.getPlayerList().getPlayer(e.subject().player().value());
                if (prisoner != null) Jail.enforce(prisoner);
            }
            case ContractEvents.Executed e -> Executions.carryOut(e.prisoner());
            // A keep that is gone takes its cells with it, as repair would on the next load;
            // its prisoners are already freed by the sim (Justice.freeOrphans).
            case charter.land.LandEvents.CoreLost e -> {
                CellBook book = Cells.book();
                for (CellBook.Cell cell : book.of(e.core())) book.remove(cell.keep, cell.name);
            }
            default -> {
            }
        }
    }
}
