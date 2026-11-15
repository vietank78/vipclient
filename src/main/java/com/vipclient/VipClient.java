package com.vipclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.entity.DecoratedPotBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

public class VipClient extends MeteorAddon {
    public static final Category CATEGORY = new Category("VIP", new ItemStack(Items.NETHER_STAR));

    @Override
    public void onInitialize() {
        Modules modules = Modules.get();
        modules.add(new WurstChestEsp());
        modules.add(new WurstEntityEsp());
        modules.add(new WurstTriggerBot());
        modules.add(new WurstBunnyHop());
        modules.add(new WurstPanic());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.vipclient";
    }

    /** How an ESP draws its targets: boxes, tracer lines, or both. */
    public enum EspStyle {
        Boxes(true, false),
        Lines(false, true),
        BoxesAndLines(true, true);

        private final boolean boxes;
        private final boolean lines;

        EspStyle(boolean boxes, boolean lines) {
            this.boxes = boxes;
            this.lines = lines;
        }

        public boolean boxes() { return boxes; }
        public boolean lines() { return lines; }
    }

    /**
     * Wurst-style ChestESP: every container type is its own group with its own on/off switch and color.
     * Double chests are drawn as a single merged box.
     */
    public static class WurstChestEsp extends Module {
        public enum BoxSize { Accurate, Full }

        private final SettingGroup sgGeneral = settings.getDefaultGroup();
        private final SettingGroup sgGroups = settings.createGroup("Groups");

        private final Setting<EspStyle> style = sgGeneral.add(new EnumSetting.Builder<EspStyle>()
            .name("style").description("Boxes, tracer lines, or both.")
            .defaultValue(EspStyle.Boxes).build());

        private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode").description("How the boxes are rendered.")
            .defaultValue(ShapeMode.Both)
            .visible(() -> style.get().boxes()).build());

        private final Setting<BoxSize> boxSize = sgGeneral.add(new EnumSetting.Builder<BoxSize>()
            .name("box-size").description("Accurate follows the real block shape, Full uses the whole block.")
            .defaultValue(BoxSize.Accurate)
            .visible(() -> style.get().boxes()).build());

        private final Setting<Integer> fillOpacity = sgGeneral.add(new IntSetting.Builder()
            .name("fill-opacity").description("Opacity of the box fill (0-255).")
            .defaultValue(40).range(0, 255).sliderRange(0, 255)
            .visible(() -> style.get().boxes() && shapeMode.get().sides()).build());

        private final Group chests = new Group("chests", "normal chests", new SettingColor(0, 255, 0));
        private final Group trapChests = new Group("trapped-chests", "trapped chests", new SettingColor(255, 160, 0));
        private final Group enderChests = new Group("ender-chests", "ender chests", new SettingColor(120, 0, 255));
        private final Group barrels = new Group("barrels", "barrels", new SettingColor(0, 255, 0));
        private final Group shulkers = new Group("shulker-boxes", "shulker boxes", new SettingColor(255, 0, 255));
        private final Group hoppers = new Group("hoppers", "hoppers", new SettingColor(160, 160, 160));
        private final Group furnaces = new Group("furnaces", "furnaces, blast furnaces and smokers", new SettingColor(255, 255, 0));
        private final Group droppers = new Group("droppers", "droppers", new SettingColor(200, 200, 200));
        private final Group dispensers = new Group("dispensers", "dispensers", new SettingColor(200, 200, 200));
        private final Group crafters = new Group("crafters", "crafters", new SettingColor(0, 200, 255));
        private final Group pots = new Group("decorated-pots", "decorated pots", new SettingColor(255, 120, 60));

        private final Color line = new Color();
        private final Color side = new Color();

        public WurstChestEsp() {
            super(VipClient.CATEGORY, "wurst-chest-esp", "Highlights containers like Wurst's ChestESP, with a color per group.");
        }

        /** One container group = a switch plus a color. */
        private class Group {
            final Setting<Boolean> enabled;
            final Setting<SettingColor> color;

            Group(String id, String title, SettingColor defaultColor) {
                enabled = sgGroups.add(new BoolSetting.Builder()
                    .name(id).description("Highlight " + title + ".")
                    .defaultValue(true).build());
                color = sgGroups.add(new ColorSetting.Builder()
                    .name(id + "-color").description("Color for " + title + ".")
                    .defaultValue(defaultColor)
                    .visible(enabled::get).build());
            }
        }

        private Group groupOf(BlockEntity be) {
            if (be instanceof TrappedChestBlockEntity) return trapChests;   // must come before ChestBlockEntity
            if (be instanceof ChestBlockEntity) return chests;
            if (be instanceof EnderChestBlockEntity) return enderChests;
            if (be instanceof BarrelBlockEntity) return barrels;
            if (be instanceof ShulkerBoxBlockEntity) return shulkers;
            if (be instanceof HopperBlockEntity) return hoppers;
            if (be instanceof AbstractFurnaceBlockEntity) return furnaces;
            if (be instanceof DropperBlockEntity) return droppers;          // must come before DispenserBlockEntity
            if (be instanceof DispenserBlockEntity) return dispensers;
            if (be instanceof CrafterBlockEntity) return crafters;
            if (be instanceof DecoratedPotBlockEntity) return pots;
            return null;
        }

        @EventHandler
        private void onRender3D(Render3DEvent event) {
            if (mc.world == null || mc.player == null) return;

            for (BlockEntity be : Utils.blockEntities()) {
                Group group = groupOf(be);
                if (group == null || !group.enabled.get()) continue;

                Box box = getBox(be);
                if (box == null) continue;

                line.set(group.color.get());
                side.set(line).a(fillOpacity.get());

                if (style.get().boxes()) {
                    event.renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                        side, line, shapeMode.get(), 0);
                }

                if (style.get().lines()) {
                    Vec3d from = RenderUtils.center;
                    Vec3d to = box.getCenter();
                    event.renderer.line(from.x, from.y, from.z, to.x, to.y, to.z, line);
                }
            }
        }

        /** Returns null for the second half of a double chest (the first half draws the merged box). */
        private Box getBox(BlockEntity be) {
            BlockPos pos = be.getPos();
            BlockState state = be.getCachedState();

            Box box = blockBox(pos, state);
            if (box == null) return null;

            if (be instanceof ChestBlockEntity && state.contains(ChestBlock.CHEST_TYPE) && state.contains(ChestBlock.FACING)) {
                ChestType type = state.get(ChestBlock.CHEST_TYPE);
                if (type == ChestType.RIGHT) return null;

                if (type == ChestType.LEFT) {
                    Direction facing = state.get(ChestBlock.FACING);
                    Box other = otherHalf(pos, state, facing.rotateYClockwise());
                    if (other == null) other = otherHalf(pos, state, facing.rotateYCounterclockwise());
                    if (other != null) box = box.union(other);
                }
            }

            return box;
        }

        private Box otherHalf(BlockPos pos, BlockState state, Direction dir) {
            BlockPos otherPos = pos.offset(dir);
            BlockState other = mc.world.getBlockState(otherPos);

            if (other.getBlock() != state.getBlock()) return null;
            if (!other.contains(ChestBlock.CHEST_TYPE) || other.get(ChestBlock.CHEST_TYPE) != ChestType.RIGHT) return null;

            return blockBox(otherPos, other);
        }

        private Box blockBox(BlockPos pos, BlockState state) {
            if (boxSize.get() == BoxSize.Full) return new Box(pos);

            VoxelShape shape = state.getOutlineShape(mc.world, pos);
            if (shape.isEmpty()) return new Box(pos);
            return shape.getBoundingBox().offset(pos);
        }
    }

    /** Wurst-style PlayerESP + MobESP + ItemESP in one module, each with its own color. */
    public static class WurstEntityEsp extends Module {
        public enum BoxSize { Accurate, Fancy }

        private final SettingGroup sgGeneral = settings.getDefaultGroup();
        private final SettingGroup sgTargets = settings.createGroup("Targets");
        private final SettingGroup sgColors = settings.createGroup("Colors");

        private final Setting<EspStyle> style = sgGeneral.add(new EnumSetting.Builder<EspStyle>()
            .name("style").description("Boxes, tracer lines, or both.")
            .defaultValue(EspStyle.Boxes).build());

        private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode").description("How the boxes are rendered.")
            .defaultValue(ShapeMode.Both)
            .visible(() -> style.get().boxes()).build());

        private final Setting<BoxSize> boxSize = sgGeneral.add(new EnumSetting.Builder<BoxSize>()
            .name("box-size").description("Accurate matches the hitbox, Fancy is slightly larger.")
            .defaultValue(BoxSize.Accurate)
            .visible(() -> style.get().boxes()).build());

        private final Setting<Integer> fillOpacity = sgGeneral.add(new IntSetting.Builder()
            .name("fill-opacity").description("Opacity of the box fill (0-255).")
            .defaultValue(40).range(0, 255).sliderRange(0, 255)
            .visible(() -> style.get().boxes() && shapeMode.get().sides()).build());

        private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
            .name("max-distance").description("Ignore targets farther than this (0 = unlimited).")
            .defaultValue(0).min(0).sliderMax(256).build());

        private final Setting<Boolean> players = sgTargets.add(new BoolSetting.Builder()
            .name("players").description("Highlight players.").defaultValue(true).build());
        private final Setting<Boolean> hostiles = sgTargets.add(new BoolSetting.Builder()
            .name("hostile-mobs").description("Highlight hostile mobs.").defaultValue(true).build());
        private final Setting<Boolean> passives = sgTargets.add(new BoolSetting.Builder()
            .name("other-mobs").description("Highlight animals, villagers and other living entities.").defaultValue(false).build());
        private final Setting<Boolean> items = sgTargets.add(new BoolSetting.Builder()
            .name("items").description("Highlight dropped items.").defaultValue(true).build());

        private final Setting<SettingColor> playerColor = sgColors.add(new ColorSetting.Builder()
            .name("player-color").description("Color for players.")
            .defaultValue(new SettingColor(255, 60, 60)).build());
        private final Setting<SettingColor> friendColor = sgColors.add(new ColorSetting.Builder()
            .name("friend-color").description("Color for friends.")
            .defaultValue(new SettingColor(0, 200, 255)).build());
        private final Setting<SettingColor> hostileColor = sgColors.add(new ColorSetting.Builder()
            .name("hostile-color").description("Color for hostile mobs.")
            .defaultValue(new SettingColor(255, 140, 0)).build());
        private final Setting<SettingColor> passiveColor = sgColors.add(new ColorSetting.Builder()
            .name("other-mob-color").description("Color for other mobs.")
            .defaultValue(new SettingColor(60, 255, 60)).build());
        private final Setting<SettingColor> itemColor = sgColors.add(new ColorSetting.Builder()
            .name("item-color").description("Color for dropped items.")
            .defaultValue(new SettingColor(255, 230, 0)).build());

        private final Color line = new Color();
        private final Color side = new Color();

        public WurstEntityEsp() {
            super(VipClient.CATEGORY, "wurst-entity-esp", "Wurst-style PlayerESP / MobESP / ItemESP in one module.");
        }

        private SettingColor colorOf(Entity e) {
            if (e instanceof PlayerEntity p) {
                if (!players.get()) return null;
                return Friends.get().isFriend(p) ? friendColor.get() : playerColor.get();
            }
            if (e instanceof ItemEntity) return items.get() ? itemColor.get() : null;
            if (e instanceof ArmorStandEntity) return null;
            if (e instanceof Monster) return hostiles.get() ? hostileColor.get() : null;
            if (e instanceof LivingEntity) return passives.get() ? passiveColor.get() : null;
            return null;
        }

        @EventHandler
        private void onRender3D(Render3DEvent event) {
            if (mc.world == null || mc.player == null) return;

            for (Entity e : mc.world.getEntities()) {
                if (e == mc.player || !e.isAlive()) continue;

                SettingColor c = colorOf(e);
                if (c == null) continue;

                double max = maxDistance.get();
                if (max > 0 && PlayerUtils.distanceTo(e) > max) continue;

                // Interpolate between ticks so boxes follow smoothly.
                double dx = MathHelper.lerp(event.tickDelta, e.lastRenderX, e.getX()) - e.getX();
                double dy = MathHelper.lerp(event.tickDelta, e.lastRenderY, e.getY()) - e.getY();
                double dz = MathHelper.lerp(event.tickDelta, e.lastRenderZ, e.getZ()) - e.getZ();

                Box box = e.getBoundingBox();
                if (boxSize.get() == BoxSize.Fancy) box = box.expand(0.1);

                line.set(c);
                side.set(line).a(fillOpacity.get());

                if (style.get().boxes()) {
                    event.renderer.box(
                        dx + box.minX, dy + box.minY, dz + box.minZ,
                        dx + box.maxX, dy + box.maxY, dz + box.maxZ,
                        side, line, shapeMode.get(), 0);
                }

                if (style.get().lines()) {
                    Vec3d from = RenderUtils.center;
                    Vec3d mid = box.getCenter();
                    event.renderer.line(from.x, from.y, from.z, dx + mid.x, dy + mid.y, dz + mid.z, line);
                }
            }
        }
    }

    /** Wurst-style TriggerBot: attacks whatever is under the crosshair once the weapon is charged. */
    public static class WurstTriggerBot extends Module {
        private final SettingGroup sgGeneral = settings.getDefaultGroup();

        private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to attack.")
            .onlyAttackable().defaultValue(EntityType.PLAYER).build());

        private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-friends").description("Never attack friends.").defaultValue(true).build());

        private final Setting<Double> minCharge = sgGeneral.add(new DoubleSetting.Builder()
            .name("min-charge").description("Attack cooldown progress required before hitting (1 = fully charged).")
            .defaultValue(1.0).range(0, 1).sliderRange(0, 1).build());

        private final Setting<Boolean> pauseWhenUsingItem = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-when-using-item").description("Do not attack while eating, blocking or drawing a bow.")
            .defaultValue(true).build());

        public WurstTriggerBot() {
            super(VipClient.CATEGORY, "wurst-trigger-bot", "Automatically attacks the entity under your crosshair.");
        }

        @EventHandler
        private void onTick(TickEvent.Pre event) {
            if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
            if (pauseWhenUsingItem.get() && mc.player.isUsingItem()) return;

            HitResult hit = mc.crosshairTarget;
            if (!(hit instanceof EntityHitResult entityHit)) return;

            Entity target = entityHit.getEntity();
            if (!target.isAlive() || !target.isAttackable()) return;
            if (!entities.get().contains(target.getType())) return;
            if (ignoreFriends.get() && target instanceof PlayerEntity p && Friends.get().isFriend(p)) return;

            if (mc.player.getAttackCooldownProgress(0.5f) < minCharge.get()) return;

            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Wurst-style BunnyHop: keeps jumping while you move on the ground. */
    public static class WurstBunnyHop extends Module {
        public WurstBunnyHop() {
            super(VipClient.CATEGORY, "wurst-bunny-hop", "Automatically jumps while you are moving.");
        }

        @EventHandler
        private void onTick(TickEvent.Pre event) {
            if (mc.player == null) return;
            if (!mc.player.isOnGround() || mc.player.isSneaking()) return;
            if (mc.player.isTouchingWater() || mc.player.isInLava()) return;
            if (!PlayerUtils.isMoving()) return;

            mc.player.jump();
        }
    }

    /** Wurst-style Panic: turn it on and every active module is switched off. */
    public static class WurstPanic extends Module {
        public WurstPanic() {
            super(VipClient.CATEGORY, "wurst-panic", "Disables all active modules in one click.");
        }

        @Override
        public void onActivate() {
            List<Module> active = new ArrayList<>(Modules.get().getActive());
            for (Module module : active) {
                if (module != this) module.disable();
            }
            disable();
        }
    }
}
