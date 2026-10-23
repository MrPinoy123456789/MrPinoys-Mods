package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.PlayerTeam;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Crew model (decision 2026-10-21, spec 6): act completion rewards for the
 * whole party. When {@link DungeonProgress#evaluate} opens act n + 1 on the
 * leader's save (or marks the campaign complete for act 5),
 * {@link #actCompleted} pays act n's rewards to every member of
 * {@code record.members} who is online, once per player per act (the
 * {@code act_rewards_claimed} sidecar). {@link #backPay} catches a joining
 * player up on acts their own save already shows complete.
 *
 * <p>Delivered per act (spec 6.2): the music disc, a bundle of the window's
 * stained glass (one stack per colour, 48 pixels total), the decor bundle,
 * the title ({@code pd_title_<n>} scoreboard team, spec 6.3), and for act 1
 * the {@code old_mine} shell unlock. Items go through {@link Payout#deliver}
 * so the caps and the drop at your feet when full apply.
 */
final class ActRewards {

    /** Players warned once about a foreign team owning their nameplate (spec 6.3). */
    private static final Set<UUID> warnedForeignTeam = new HashSet<>();

    private ActRewards() {}

    /**
     * Act {@code act} just completed on the leader's save: pay every online
     * member of the run (leader included), plus {@code member} when no record
     * carries a member list. Idempotent per player.
     */
    static void actCompleted(MinecraftServer server, InstanceRecord record, ServerPlayer member,
                             int act) {
        Set<UUID> paid = new HashSet<>();
        if (record != null) {
            for (UUID id : record.members.keySet()) {
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                if (online != null && paid.add(id)) {
                    pay(server, online, act);
                }
            }
        }
        if (member != null && paid.add(member.getUUID())) {
            pay(server, member, act);
        }
    }

    /**
     * Spec 6.1 back pay: for every act the player's own save shows complete
     * (the prologue flag for act 0, act n + 1 in {@code actsUnlocked}, the
     * campaign flag for act 5) and not yet claimed, pay it now.
     */
    static void backPay(MinecraftServer server, ServerPlayer player) {
        DungeonLog log = DungeonLog.forServer(server);
        DungeonLog.Entry entry = log.get(player.getUUID());
        Set<Integer> claimed = log.actRewardsClaimedOf(player.getUUID());
        for (int act = DungeonDef.PROLOGUE_ACT; act <= DungeonDef.MAX_ACT; act++) {
            if (claimed.contains(act)) {
                continue;
            }
            boolean complete = act == DungeonDef.PROLOGUE_ACT
                    ? DungeonProgress.prologueDone(log, player.getUUID())
                    : act == DungeonDef.MAX_ACT
                    ? entry.campaign().campaignComplete()
                    : entry.campaign().actsUnlocked().contains(act + 1);
            if (complete) {
                pay(server, player, act);
            }
        }
    }

    /**
     * Pays one player act {@code act}'s rewards once: claim first, then the
     * items, the title, the shell, the chat lines and the fanfare. Act 0 has
     * no rewards; the claim still records so back pay never revisits it.
     */
    static void pay(MinecraftServer server, ServerPlayer player, int act) {
        DungeonLog log = DungeonLog.forServer(server);
        if (!log.claimActRewards(player.getUUID(), act)) {
            return;
        }
        ActReward.Act data = ActRewardManifest.byAct(act);
        if (data == null) {
            return;
        }
        ActReward.Rewards rewards = data.rewards();
        ChatFormatting colour = ActReward.colourOf(data);
        String actName = ActProgress.name(act);
        int delivered = 0;

        if (rewards.disc() != null) {
            ItemStack disc = item(rewards.disc());
            name(disc, actName, colour);
            lore(disc, List.of("Act " + act));
            Payout.deliver(player, disc);
            delivered++;
        }
        ItemStack window = windowBundle(data, actName, colour, act);
        if (window != null) {
            Payout.deliver(player, window);
            delivered++;
        }
        if (!rewards.decor().isEmpty()) {
            ItemStack decor = decorBundle(rewards, actName, colour, act);
            if (decor != null) {
                Payout.deliver(player, decor);
                delivered++;
            }
        }
        if (rewards.title() != null) {
            log.earnTitle(player.getUUID(), act);
            applyTitleTeam(server, player);
            delivered++;
        }
        if (rewards.shell() != null) {
            log.unlockShell(player.getUUID(), rewards.shell());
            delivered++;
        }

        // Spec 6.4: one chat line per reward, gray.
        List<String> lines = new ArrayList<>();
        if (rewards.shell() != null) {
            lines.add("+ " + RoomBuilder.palette(rewards.shell()).displayName() + " shell");
        }
        if (rewards.title() != null) {
            lines.add("+ Title: " + rewards.title());
        }
        if (rewards.disc() != null) {
            lines.add("+ " + actName + " (music disc)");
        }
        if (window != null) {
            lines.add("+ " + actName + " Window");
        }
        if (!rewards.decor().isEmpty()) {
            lines.add("+ " + actName + " Decor");
        }
        for (String line : lines) {
            player.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        if (delivered > 0) {
            StaggeredTitle.show(server, player.getUUID(),
                    Component.literal(actName + " complete").withStyle(colour),
                    List.of(delivered + " reward" + (delivered == 1 ? "" : "s")
                            + " in your inventory"),
                    ChatFormatting.GRAY);
            player.connection.send(new ClientboundSoundPacket(
                    Holder.direct(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), SoundSource.RECORDS,
                    player.getX(), player.getY(), player.getZ(),
                    0.8f, 1.0f, player.getRandom().nextLong()));
        }
    }

    /**
     * Spec 6.3: put the player on the team of the highest act whose title
     * they hold, prefix {@code ◆ <Title> } in the act colour followed by a
     * reset so the name keeps its colour. A player already on a team not
     * starting with {@code pd_title_} is left alone and logged once.
     */
    static void applyTitleTeam(MinecraftServer server, ServerPlayer player) {
        DungeonLog log = DungeonLog.forServer(server);
        Set<Integer> titles = log.titlesEarnedOf(player.getUUID());
        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerTeam current = scoreboard.getPlayersTeam(player.getScoreboardName());
        if (current != null && !current.getName().startsWith("pd_title_")) {
            if (warnedForeignTeam.add(player.getUUID())) {
                PocketDungeonsMod.LOG.info("{} is on team {}; leaving their nameplate alone",
                        player.getName().getString(), current.getName());
            }
            return;
        }
        int highest = titles.stream().mapToInt(Integer::intValue).max().orElse(-1);
        String target = highest < 0 ? null : "pd_title_" + highest;
        if (current != null && current.getName().equals(target)) {
            return;
        }
        if (current != null) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), current);
        }
        if (target == null) {
            return;
        }
        PlayerTeam team = scoreboard.getPlayerTeam(target);
        if (team == null) {
            team = scoreboard.addPlayerTeam(target);
        }
        ActReward.Act data = ActRewardManifest.byAct(highest);
        ChatFormatting colour = data == null ? ChatFormatting.WHITE : ActReward.colourOf(data);
        String title = data == null || data.rewards().title() == null
                ? "Act " + highest : data.rewards().title();
        team.setPlayerPrefix(Component.literal("◆ " + title + " ").withStyle(colour)
                .append(Component.literal("").withStyle(ChatFormatting.RESET)));
        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
    }

    // ---- the items ----------------------------------------------------------------------

    /** The window art bundled: one stack of stained glass per colour used, 48 pixels. */
    private static ItemStack windowBundle(ActReward.Act data, String actName,
                                          ChatFormatting colour, int act) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String row : data.window()) {
            for (char c : row.toCharArray()) {
                counts.merge(ActReward.glassId(c), 1, Integer::sum);
            }
        }
        List<ItemStackTemplate> stacks = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(e.getKey())).orElse(null);
            if (item == null) {
                return null;
            }
            stacks.add(new ItemStackTemplate(item, e.getValue()));
        }
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(stacks));
        name(bundle, actName + " Window", colour);
        List<Component> lore = new ArrayList<>();
        for (String row : data.window()) {
            lore.add(Component.literal(row).withStyle(ChatFormatting.GRAY));
        }
        lore.add(Component.literal("Build it on a wall of your room.").withStyle(ChatFormatting.GRAY));
        bundle.set(DataComponents.LORE, new ItemLore(lore));
        return bundle;
    }

    /** The decor bundle: the act's decoration set, one stack per line. */
    private static ItemStack decorBundle(ActReward.Rewards rewards, String actName,
                                         ChatFormatting colour, int act) {
        List<ItemStackTemplate> stacks = new ArrayList<>();
        for (ActReward.DecorItem d : rewards.decor()) {
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(d.item())).orElse(null);
            if (item == null) {
                return null;
            }
            stacks.add(new ItemStackTemplate(item, d.count()));
        }
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(stacks));
        name(bundle, actName + " Decor", colour);
        lore(bundle, List.of("Act " + act));
        return bundle;
    }

    private static ItemStack item(String itemId) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId))
                .orElse(Items.AIR);
        return new ItemStack(item);
    }

    private static void name(ItemStack stack, String text, ChatFormatting colour) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(text).withStyle(colour)
                .withStyle(s -> s.withItalic(false)));
    }

    private static void lore(ItemStack stack, List<String> lines) {
        stack.set(DataComponents.LORE, new ItemLore(lines.stream()
                .map(l -> (Component) Component.literal(l).withStyle(ChatFormatting.GRAY))
                .toList()));
    }
}
