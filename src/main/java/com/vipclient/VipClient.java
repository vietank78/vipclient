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
            if (m
