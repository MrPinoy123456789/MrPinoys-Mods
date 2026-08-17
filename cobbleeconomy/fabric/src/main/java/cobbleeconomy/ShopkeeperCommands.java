package cobbleeconomy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

/**
 * {@code /shopnpc spawn}: summon a shopkeeper villager.
 *
 * <p>Creates a non-trading, persistent villager entity at the command-issuing player's
 * location, tagged so a right-click listener can recognize it and open the shop UI.
 */
public final class ShopkeeperCommands {

    public static final String SHOPKEEPER_TAG = "cobbleeconomy_shopkeeper";

    public ShopkeeperCommands() {
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shopnpc")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("spawn")
                        .executes(this::spawn)));
    }

    private int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.level();

        // Create the villager entity
        Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);

        if (villager == null) {
            source.sendFailure(Component.literal("Failed to create villager."));
            return 0;
        }

        // Position the villager at the player's location
        villager.setPos(player.getX(), player.getY(), player.getZ());
        villager.setYRot(player.getYRot());
        villager.setXRot(0.0F);
        villager.setYHeadRot(player.getYRot());
        villager.setYBodyRot(player.getYRot());

        // Set profession to NONE so it won't offer vanilla trades
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(level.registryAccess(), VillagerProfession.NONE));

        // Tag as a shopkeeper for the right-click listener to recognize
        villager.addTag(SHOPKEEPER_TAG);

        // Remember where it's meant to stand and face, so ShopkeeperAnchor can pull it
        // back after a shove or a restart.
        villager.addTag(ShopkeeperAnchor.encodeHome(
                player.getX(), player.getY(), player.getZ(), player.getYRot()));

        // Make it persistent (never vanilla-despawn)
        villager.setPersistenceRequired();

        // Disable AI so it stays put instead of wandering off
        villager.setNoAi(true);

        // Set a custom name
        villager.setCustomName(Component.literal("Shopkeeper"));
        villager.setCustomNameVisible(true);

        // Add to the world
        level.addFreshEntity(villager);

        // Send feedback to the player
        source.sendSuccess(() -> Component.literal("Shopkeeper spawned."), true);

        return 1;
    }
}
