# Build notes — consolidated

This is the full assembled mod: skeleton, boots, six stations, two area tools,
commands, and the API module. Every file in it has been through at least one build
pass earlier in development except the three that were reconstructed for this
package (`ItemRegistry.java`, `Gate.java`, `Stations.java` — see below).

## Fixed during earlier builds, already applied here

1. **`property()` in a `plugins {}` block doesn't work.** The block evaluates
   before the project exists. Both build files use a literal Loom version.
2. **Brigadier paren miscount.** `WondrousCommands` builds its tree as named
   locals, not one chained expression.
3. **`CommandSourceStack.sendSystemMessage` doesn't exist.** Use
   `sendSuccess(() -> component, false)`.
4. **`ServerEntityWorldChangeEvents` doesn't exist** in this Fabric API build.
   `FlyingBoots` relies on the 10-tick poll for dimension changes instead of a
   dedicated hook.
5. **`Abilities.mayfly`** — lowercase f.
6. **`InteractionResultHolder` is gone.** Callbacks return `InteractionResult`
   directly; `SUCCESS_SERVER` is what consumes a station's block interaction.
7. **`DyedItemColor(int)`** — one constructor arg.

## Reconstructed for this package, not yet build-tested in this exact form

`ItemRegistry.java`, `Gate.java`, and `Stations.java` were rewritten from scratch
to match the current 7-field `Definitions.Def` (with the `area` component added
for the pick/shovel). They're straightforward — `ItemRegistry` and `Gate` are
unchanged in substance from earlier verified versions, and `Stations` only gained
a comment update — but this exact combination of all 8 files hasn't been compiled
together as one build. Worth a `./gradlew build` before assuming it's clean.

## Signatures not in the original recon — watch on first build of AreaBreak.java

1. `AttackBlockCallback` parameter order — expected `(player, level, hand, pos, direction)`
2. `PlayerBlockBreakEvents.AFTER` parameters — expected `(level, player, pos, state, blockEntity)`
3. `ItemStack.isCorrectToolForDrops(BlockState)` — may want `Level`/`BlockPos` too

`BlockState.getDestroySpeed`, `Level.getBlockEntity`, `BlockPos.offset(int,int,int)`,
and `ServerPlayerGameMode.destroyBlock(BlockPos)` are all confirmed or long-stable.

## Not available in 26.2

`MinecraftServer.isFlightAllowed()` doesn't exist. `WondrousMod` logs an
unconditional reminder about `allow-flight=true` instead of a conditional warning.

## Still open

The op check. `Gate.mayAdminister` is console-only (`!source.isPlayer()`). This is
**the most likely reason `/wondrous give` "does nothing"** — running it as a player
in chat fails Brigadier's `requires()` silently rather than with an error message.
Run it from the server console.

## Test gate — everything

**Boots:** give via console → wear → fly → remove mid-air, land unhurt → creative
round-trip → die/respawn wearing them, flight back fast.

**Six stations:** each opens its menu; **none place as a block** when right-clicked
on the ground (this is the one that fails quietly if `SUCCESS_SERVER` isn't
consuming correctly); anvil renames/repairs/never breaks; ender chest matches a
real one; an ordinary crafting table still places normally.

**Two area tools:** 3×3 on a wall carves into the wall; 3×3 on a floor carves into
the floor; sneak = one block; adjacent dirt/gravel survives a stone break; adjacent
chest survives; Fortune applies across the whole break; bedrock survives; tool
breaking mid-swing doesn't crash.

**Give:** from console, `/wondrous give <you> pocket_workbench`, confirm it lands
in inventory (or drops at your feet if full) and opens correctly.
## 26.2 signatures - verified 2026-08-13
Compiled from "SavedData.java"
public abstract class net.minecraft.world.level.saveddata.SavedData {
  public net.minecraft.world.level.saveddata.SavedData();
  public void setDirty();
  public void setDirty(boolean);
  public boolean isDirty();
}
  public net.minecraft.world.level.storage.SavedDataStorage getDataStorage();
Compiled from "ItemContainerContents.java"
public final class net.minecraft.world.item.component.ItemContainerContents implements net.minecraft.world.item.component.TooltipProvider {
  public static final net.minecraft.world.item.component.ItemContainerContents EMPTY;
  public static final com.mojang.serialization.Codec<net.minecraft.world.item.component.ItemContainerContents> CODEC;
  public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, net.minecraft.world.item.component.ItemContainerContents> STREAM_CODEC;
  public static net.minecraft.world.item.component.ItemContainerContents fromItems(java.util.List<net.minecraft.world.item.ItemStack>);
  public void copyInto(net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack>);
  public net.minecraft.world.item.ItemStack copyOne();
  public java.util.stream.Stream<net.minecraft.world.item.ItemStack> allItemsCopyStream();
  public java.util.stream.Stream<net.minecraft.world.item.ItemStack> nonEmptyItemCopyStream();
  public java.lang.Iterable<net.minecraft.world.item.ItemStackTemplate> nonEmptyItems();
  public boolean equals(java.lang.Object);
  public int hashCode();
  public void addToTooltip(net.minecraft.world.item.Item$TooltipContext, java.util.function.Consumer<net.minecraft.network.chat.Component>, net.minecraft.world.item.TooltipFlag, net.minecraft.core.component.DataComponentGetter);
  static {};
}
Compiled from "BonemealableBlock.java"
public interface net.minecraft.world.level.block.BonemealableBlock {
  public abstract boolean isValidBonemealTarget(net.minecraft.world.level.LevelReader, net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);
  public abstract boolean isBonemealSuccess(net.minecraft.world.level.Level, net.minecraft.util.RandomSource, net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);
  public abstract void performBonemeal(net.minecraft.server.level.ServerLevel, net.minecraft.util.RandomSource, net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);
  public static boolean hasSpreadableNeighbourPos(net.minecraft.world.level.LevelReader, net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);
  public static java.util.Optional<net.minecraft.core.BlockPos> findSpreadableNeighbourPos(net.minecraft.world.level.Level, net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);
  public default net.minecraft.core.BlockPos getParticlePos(net.minecraft.core.BlockPos);
  public default net.minecraft.world.level.block.BonemealableBlock$Type getType();
}
Compiled from "CropBlock.java"
public class net.minecraft.world.level.block.CropBlock extends net.minecraft.world.level.block.VegetationBlock implements net.minecraft.world.level.block.BonemealableBlock {
  public static final com.mojang.serialization.MapCodec<net.minecraft.world.level.block.CropBlock> CODEC;
  public static final int MAX_AGE;
  public static final net.minecraft.world.level.block.state.properties.IntegerProperty AGE;
  public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.CropBlock> codec();
  public net.minecraft.world.level.block.CropBlock(net.minecraft.world.level.block.state.BlockBehaviour$Properties);
  protected net.minecraft.world.phys.shapes.VoxelShape getShape(net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.BlockGetter, net.minecraft.core.BlockPos, net.minecraft.world.phys.shapes.CollisionContext);
  protected boolean mayPlaceOn(net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.BlockGetter, net.minecraft.core.BlockPos);
  protected net.minecraft.world.level.block.state.properties.IntegerProperty getAgeProperty();
  public int getMaxAge();
  public int getAge(net.minecraft.world.level.block.state.BlockState);
  public net.minecraft.world.level.block.state.BlockState getStateForAge(int);
  public final boolean isMaxAge(net.minecraft.world.level.block.state.BlockState);
  protected boolean isRandomlyTicking(net.minecraft.world.level.block.state.BlockState);
  protected void randomTick(net.minecraft.world.level.block.state.BlockState, net.minecraft.server.level.ServerLevel, net.minecraft.core.BlockPos, net.minecraft.util.RandomSource);
  public void growCrops(net.minecraft.world.level.Level, net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);
  protected int getBonemealAgeIncrease(net.minecraft.world.level.Level);
  protected static float getGrowthSpeed(net.minecraft.world.level.block.Block, net.minecraft.world.level.BlockGetter, net.minecraft.core.BlockPos);
  protected boolean canSurvive(net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.LevelReader, net.minecraft.core.BlockPos);
  protected static boolean hasSufficientLight(net.minecraft.world.level.LevelReader, net.minecraft.core.BlockPos);
  protected void entityInside(net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.Level, net.minecraft.core.BlockPos, net.minecraft.world.entity.Entity, net.minecraft.world.entity.InsideBlockEffectApplier, boolean);
  protected net.minecraft.world.level.ItemLike getBaseSeedId();
  protected net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader, net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState, boolean);
  public boolean isValidBonemealTarget(net.minecraft.world.level.LevelReader, net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);
  public boolean isBonemealSuccess(net.minecraft.world.level.Level, net.minecraft.util.RandomSource, net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);
  public void performBonemeal(net.minecraft.server.level.ServerLevel, net.minecraft.util.RandomSource, net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState);
  protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition$Builder<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.state.BlockState>);
  static {};
}
  public static void dropResources(net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.Level, net.minecraft.core.BlockPos);
  public static void dropResources(net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.LevelAccessor, net.minecraft.core.BlockPos, net.minecraft.world.level.block.entity.BlockEntity);
  public static void dropResources(net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.Level, net.minecraft.core.BlockPos, net.minecraft.world.level.block.entity.BlockEntity, net.minecraft.world.entity.Entity, net.minecraft.world.item.ItemStack);
Compiled from "WorldlyContainer.java"
public interface net.minecraft.world.WorldlyContainer extends net.minecraft.world.Container {
  public abstract int[] getSlotsForFace(net.minecraft.core.Direction);
  public abstract boolean canPlaceItemThroughFace(int, net.minecraft.world.item.ItemStack, net.minecraft.core.Direction);
  public abstract boolean canTakeItemThroughFace(int, net.minecraft.world.item.ItemStack, net.minecraft.core.Direction);
}
Compiled from "Container.java"
public interface net.minecraft.world.Container extends net.minecraft.world.Clearable, java.lang.Iterable<net.minecraft.world.item.ItemStack>, net.minecraft.world.entity.SlotProvider {
  public static final float DEFAULT_DISTANCE_BUFFER;
  public abstract int getContainerSize();
  public abstract boolean isEmpty();
  public abstract net.minecraft.world.item.ItemStack getItem(int);
  public abstract net.minecraft.world.item.ItemStack removeItem(int, int);
  public abstract net.minecraft.world.item.ItemStack removeItemNoUpdate(int);
  public abstract void setItem(int, net.minecraft.world.item.ItemStack);
  public default int getMaxStackSize();
  public default int getMaxStackSize(net.minecraft.world.item.ItemStack);
  public abstract void setChanged();
  public abstract boolean stillValid(net.minecraft.world.entity.player.Player);
  public default void startOpen(net.minecraft.world.entity.ContainerUser);
  public default void stopOpen(net.minecraft.world.entity.ContainerUser);
  public default java.util.List<net.minecraft.world.entity.ContainerUser> getEntitiesWithContainerOpen();
  public default boolean canPlaceItem(int, net.minecraft.world.item.ItemStack);
  public default boolean canTakeItem(net.minecraft.world.Container, int, net.minecraft.world.item.ItemStack);
  public default int countItem(net.minecraft.world.item.Item);
  public default boolean hasAnyOf(java.util.Set<net.minecraft.world.item.Item>);
  public default boolean hasAnyMatching(java.util.function.Predicate<net.minecraft.world.item.ItemStack>);
  public static boolean stillValidBlockEntity(net.minecraft.world.level.block.entity.BlockEntity, net.minecraft.world.entity.player.Player);
  public static boolean stillValidBlockEntity(net.minecraft.world.level.block.entity.BlockEntity, net.minecraft.world.entity.player.Player, float);
  public default net.minecraft.world.entity.SlotAccess getSlot(int);
  public default java.util.Iterator<net.minecraft.world.item.ItemStack> iterator();
}
Compiled from "Display.java"
public abstract class net.minecraft.world.entity.Display extends net.minecraft.world.entity.Entity {
  public static final int NO_BRIGHTNESS_OVERRIDE;
  public static final java.lang.String TAG_POS_ROT_INTERPOLATION_DURATION;
  public static final java.lang.String TAG_TRANSFORMATION_INTERPOLATION_DURATION;
  public static final java.lang.String TAG_TRANSFORMATION_START_INTERPOLATION;
  public static final java.lang.String TAG_TRANSFORMATION;
  public static final java.lang.String TAG_BILLBOARD;
  public static final java.lang.String TAG_BRIGHTNESS;
  public static final java.lang.String TAG_VIEW_RANGE;
  public static final java.lang.String TAG_SHADOW_RADIUS;
  public static final java.lang.String TAG_SHADOW_STRENGTH;
  public static final java.lang.String TAG_WIDTH;
  public static final java.lang.String TAG_HEIGHT;
  public static final java.lang.String TAG_GLOW_COLOR_OVERRIDE;
  protected boolean updateRenderState;
  public net.minecraft.world.entity.Display(net.minecraft.world.entity.EntityType<?>, net.minecraft.world.level.Level);
  public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?>);
  public final boolean hurtServer(net.minecraft.server.level.ServerLevel, net.minecraft.world.damagesource.DamageSource, float);
  public void tick();
  public net.minecraft.world.entity.InterpolationHandler getInterpolation();
  protected abstract void updateRenderSubState(boolean, float);
  protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData$Builder);
  protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput);
  public final void setTransformation(com.mojang.math.Transformation);
  protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput);
  public net.minecraft.world.phys.AABB getBoundingBoxForCulling();
  public boolean affectedByCulling();
  public net.minecraft.world.level.material.PushReaction getPistonPushReaction();
  public boolean isIgnoringBlockTriggers();
  public net.minecraft.world.entity.Display$RenderState renderState();
  public final void setTransformationInterpolationDuration(int);
  public final int getTransformationInterpolationDuration();
  public final void setTransformationInterpolationDelay(int);
  public final int getTransformationInterpolationDelay();
  public final void setPosRotInterpolationDuration(int);
  public final int getPosRotInterpolationDuration();
  public final void setBillboardConstraints(net.minecraft.world.entity.Display$BillboardConstraints);
  public final net.minecraft.world.entity.Display$BillboardConstraints getBillboardConstraints();
  public final void setBrightnessOverride(net.minecraft.util.Brightness);
  public final net.minecraft.util.Brightness getBrightnessOverride();
  public final int getPackedBrightnessOverride();
  public final void setViewRange(float);
  public final float getViewRange();
  public final void setShadowRadius(float);
  public final float getShadowRadius();
  public final void setShadowStrength(float);
  public final float getShadowStrength();
  public final void setWidth(float);
  public final float getWidth();
  public final void setHeight(float);
  public final int getGlowColorOverride();
  public final void setGlowColorOverride(int);
  public float calculateInterpolationProgress(float);
  public final float getHeight();
  public void setPos(double, double, double);
  public boolean shouldRenderAtSqrDistance(double);
  public int getTeamColor();
  static {};
}
  public <T extends net.minecraft.core.particles.ParticleOptions> int sendParticles(T, double, double, double, int, double, double, double, double);
  public <T extends net.minecraft.core.particles.ParticleOptions> int sendParticles(T, boolean, boolean, double, double, double, int, double, double, double, double);
  public <T extends net.minecraft.core.particles.ParticleOptions> boolean sendParticles(net.minecraft.server.level.ServerPlayer, T, boolean, boolean, double, double, double, int, double, double, double, double);
  public final boolean sendParticles(net.minecraft.server.level.ServerPlayer, boolean, double, double, double, net.minecraft.network.protocol.Packet<?>);
  protected net.minecraft.world.item.ItemCooldowns createItemCooldowns();
  public void sendSystemMessage(net.minecraft.network.chat.Component);
  public void sendSystemMessage(net.minecraft.network.chat.Component, boolean);
  protected void processPortalCooldown();
Compiled from "ItemCooldowns.java"
public class net.minecraft.world.item.ItemCooldowns {
  public net.minecraft.world.item.ItemCooldowns();
  public boolean isOnCooldown(net.minecraft.world.item.ItemStack);
  public float getCooldownPercent(net.minecraft.world.item.ItemStack, float);
  public void tick();
  public net.minecraft.resources.Identifier getCooldownGroup(net.minecraft.world.item.ItemStack);
  public void addCooldown(net.minecraft.world.item.ItemStack, int);
  public void addCooldown(net.minecraft.resources.Identifier, int);
  public void removeCooldown(net.minecraft.resources.Identifier);
  protected void onCooldownStarted(net.minecraft.resources.Identifier, int);
  protected void onCooldownEnded(net.minecraft.resources.Identifier);
}
Compiled from "CraftingMenu.java"
public class net.minecraft.world.inventory.CraftingMenu extends net.minecraft.world.inventory.AbstractCraftingMenu {
  public static final int RESULT_SLOT;
  public net.minecraft.world.inventory.CraftingMenu(int, net.minecraft.world.entity.player.Inventory);
  public net.minecraft.world.inventory.CraftingMenu(int, net.minecraft.world.entity.player.Inventory, net.minecraft.world.inventory.ContainerLevelAccess);
  protected static void slotChangedCraftingGrid(net.minecraft.world.inventory.AbstractContainerMenu, net.minecraft.server.level.ServerLevel, net.minecraft.world.entity.player.Player, net.minecraft.world.inventory.CraftingContainer, net.minecraft.world.inventory.ResultContainer, net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>);
  public void slotsChanged(net.minecraft.world.Container);
  public void beginPlacingRecipe();
  public void finishPlacingRecipe(net.minecraft.server.level.ServerLevel, net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>);
  public void removed(net.minecraft.world.entity.player.Player);
  public boolean stillValid(net.minecraft.world.entity.player.Player);
  public net.minecraft.world.item.ItemStack quickMoveStack(net.minecraft.world.entity.player.Player, int);
  public boolean canTakeItemForPickAll(net.minecraft.world.item.ItemStack, net.minecraft.world.inventory.Slot);
  public net.minecraft.world.inventory.Slot getResultSlot();
  public java.util.List<net.minecraft.world.inventory.Slot> getInputGridSlots();
  public net.minecraft.world.inventory.RecipeBookType getRecipeBookType();
  protected net.minecraft.world.entity.player.Player owner();
}
Compiled from "ChestMenu.java"
public class net.minecraft.world.inventory.ChestMenu extends net.minecraft.world.inventory.AbstractContainerMenu {
  public static net.minecraft.world.inventory.ChestMenu oneRow(int, net.minecraft.world.entity.player.Inventory);
  public static net.minecraft.world.inventory.ChestMenu twoRows(int, net.minecraft.world.entity.player.Inventory);
  public static net.minecraft.world.inventory.ChestMenu threeRows(int, net.minecraft.world.entity.player.Inventory);
  public static net.minecraft.world.inventory.ChestMenu fourRows(int, net.minecraft.world.entity.player.Inventory);
  public static net.minecraft.world.inventory.ChestMenu fiveRows(int, net.minecraft.world.entity.player.Inventory);
  public static net.minecraft.world.inventory.ChestMenu sixRows(int, net.minecraft.world.entity.player.Inventory);
  public static net.minecraft.world.inventory.ChestMenu threeRows(int, net.minecraft.world.entity.player.Inventory, net.minecraft.world.Container);
  public static net.minecraft.world.inventory.ChestMenu sixRows(int, net.minecraft.world.entity.player.Inventory, net.minecraft.world.Container);
  public net.minecraft.world.inventory.ChestMenu(net.minecraft.world.inventory.MenuType<?>, int, net.minecraft.world.entity.player.Inventory, net.minecraft.world.Container, int);
  public boolean stillValid(net.minecraft.world.entity.player.Player);
  public net.minecraft.world.item.ItemStack quickMoveStack(net.minecraft.world.entity.player.Player, int);
  public void removed(net.minecraft.world.entity.player.Player);
  public net.minecraft.world.Container getContainer();
  public int getRowCount();
}
Compiled from "SimpleContainer.java"
public class net.minecraft.world.SimpleContainer implements net.minecraft.world.Container,net.minecraft.world.inventory.StackedContentsCompatible {
  public final net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> items;
  public net.minecraft.world.SimpleContainer(int);
  public net.minecraft.world.SimpleContainer(net.minecraft.world.item.ItemStack...);
  public net.minecraft.world.item.ItemStack getItem(int);
  public java.util.List<net.minecraft.world.item.ItemStack> removeAllItems();
  public net.minecraft.world.item.ItemStack removeItem(int, int);
  public net.minecraft.world.item.ItemStack removeItemType(net.minecraft.world.item.Item, int);
  public net.minecraft.world.item.ItemStack addItem(net.minecraft.world.item.ItemStack);
  public boolean canAddItem(net.minecraft.world.item.ItemStack);
  public net.minecraft.world.item.ItemStack removeItemNoUpdate(int);
  public void setItem(int, net.minecraft.world.item.ItemStack);
  public void setChanged();
  public int getContainerSize();
  public boolean isEmpty();
  public boolean stillValid(net.minecraft.world.entity.player.Player);
  public void clearContent();
  public void fillStackedContents(net.minecraft.world.entity.player.StackedItemContents);
  public java.lang.String toString();
  public void fromItemList(net.minecraft.world.level.storage.ValueInput$TypedInputList<net.minecraft.world.item.ItemStack>);
  public void storeAsItemList(net.minecraft.world.level.storage.ValueOutput$TypedOutputList<net.minecraft.world.item.ItemStack>);
  public net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> getItems();
}
  public static final double DEFAULT_ATTACK_SPEED;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> ATTACK_SPEED;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> BLOCK_BREAK_SPEED;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> BLOCK_INTERACTION_RANGE;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> ENTITY_INTERACTION_RANGE;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> FLYING_SPEED;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> FOLLOW_RANGE;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> JUMP_STRENGTH;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> MOVEMENT_SPEED;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> SAFE_FALL_DISTANCE;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> SNEAKING_SPEED;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> STEP_HEIGHT;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> SUBMERGED_MINING_SPEED;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> TEMPT_RANGE;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> WAYPOINT_TRANSMIT_RANGE;
  public static final net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> WAYPOINT_RECEIVE_RANGE;
  public static final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> SPEED;
  public static final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> FIRE_RESISTANCE;
  public static final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> WATER_BREATHING;
  public static final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> NIGHT_VISION;
  public static final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> SLOW_FALLING;
  public static final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> DOLPHINS_GRACE;

## 26.2 signature table — T0

| Spec claim | Real 26.2 signature | Task affected |
|---|---|---|
| `SavedData` / `getDataStorage().computeIfAbsent(...)` | `ServerLevel.getDataStorage(): SavedDataStorage`; `SavedDataStorage.computeIfAbsent(SavedDataType<T>)` | T14 |
| `ItemContainerContents` read/write, slot cap | `ItemContainerContents.fromItems(List<ItemStack>)` (create), `copyInto(NonNullList<ItemStack>)` (read); write via `stack.set(DataComponents.CONTAINER, fromItems(list))` | T2 |
| `BonemealableBlock.performBonemeal(...)` | `void performBonemeal(ServerLevel, RandomSource, BlockPos, BlockState)` | T10 |
| `CropBlock.isMaxAge` / `getAge` / `getStateForAge` | `boolean isMaxAge(BlockState)`, `int getAge(BlockState)`, `BlockState getStateForAge(int)` | T8 |
| `Block.dropResources(...)` overloads | `void dropResources(BlockState, Level, BlockPos, BlockEntity, Entity, ItemStack)` | T8 |
| `WorldlyContainer.getSlotsForFace` / `canTakeItemThroughFace` | `int[] getSlotsForFace(Direction)`, `boolean canTakeItemThroughFace(int, ItemStack, Direction)` | T16 |
| `Display.BlockDisplay` + glowing + team colour | `Display$BlockDisplay` exists; `Display.setGlowingTag` from `Entity`; `Display.getTeamColor()`; `Display.setGlowColorOverride(int)` | T13 |
| Action bar overload | `ServerPlayer.sendSystemMessage(Component, boolean)` (`boolean` = action bar) | T1 |
| `ServerPlayer.getCooldowns().addCooldown(...)` — stack or item? | `ItemCooldowns.addCooldown(ItemStack, int)` and `addCooldown(Identifier, int)` | T11 |
| `Attributes.BLOCK_INTERACTION_RANGE` id | `Attributes.BLOCK_INTERACTION_RANGE` exists | T0.5 |
| `Attributes.JUMP_STRENGTH` id | `Attributes.JUMP_STRENGTH` exists | T0.5 |
| `MobEffects.SLOW_FALLING`, `.DOLPHINS_GRACE` ids | `MobEffects.SLOW_FALLING` and `MobEffects.DOLPHINS_GRACE` both exist | T12.5 |

No tasks are blocked. The `DimensionDataStorage` class does not exist; the saved-data API is `ServerLevel.getDataStorage()` returning `SavedDataStorage`.

## T4 decisions

- No per-stack furnace-fuel hook exists in 26.2 without a Mixin. The accepted risk is documented by adding the lore line "dont put me in a furnace" to every tagged item whose base is a fuel.
- The crafting guard uses `AbstractCraftingMenu.getInputGridSlots()` and `getResultSlot()`: any tagged input clears the result slot each server tick.


