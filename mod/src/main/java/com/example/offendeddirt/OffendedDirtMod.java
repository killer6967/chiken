package com.example.offendeddirt;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.command.CommandOutput;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Мод «Обиженная земля» (Fabric, 1.21.x).
 *  1) Ломаешь землю/траву -> вылетает курица В ШЛЯПЕ и жалуется в чат.
 *  2) Каждый твой прыжок сопровождается мычанием коровы.
 *  3) Крипер перед взрывом вежливо извиняется.
 */
public class OffendedDirtMod implements ModInitializer {

    private static final Random RNG = new Random();

    private static final String[] COMPLAINTS = {
        "Земля обиделась и прислала курицу!",
        "Ты сломал мой дом! Вот тебе курица.",
        "Кудах! (перевод: «ты чего?»)",
        "Земля подаёт на тебя в суд.",
        "Эта курица теперь твоя проблема."
    };

    private static final String[] HATS = {
        "minecraft:carved_pumpkin",
        "minecraft:golden_helmet",
        "minecraft:leather_helmet",
        "minecraft:turtle_helmet",
        "minecraft:iron_helmet"
    };

    private static final String[] APOLOGIES = {
        "Извините!",
        "Ничего личного...",
        "Мне правда жаль!",
        "Простите, это рефлекс.",
        "Я не хотел, оно само!"
    };

    // для прыжков
    private static final Map<UUID, Boolean> WAS_GROUNDED = new HashMap<>();
    private static final Map<UUID, Double> LAST_Y = new HashMap<>();
    // крипер уже извинился
    private static final Set<UUID> APOLOGIZED = new HashSet<>();

    @Override
    public void onInitialize() {
        // --- 1. Курица в шляпе из сломанной земли ---
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!(world instanceof ServerWorld serverWorld)) return;

            boolean isDirty = state.isOf(Blocks.DIRT)
                    || state.isOf(Blocks.GRASS_BLOCK)
                    || state.isOf(Blocks.COARSE_DIRT);
            if (!isDirty) return;

            spawnChickenWithHat(serverWorld, Vec3d.ofCenter(pos));

            player.sendMessage(Text.literal(COMPLAINTS[RNG.nextInt(COMPLAINTS.length)]), true);

            // 10% шанс: «взрыв смеха»
            if (RNG.nextInt(10) == 0) {
                serverWorld.playSound(null, pos, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST,
                        SoundCategory.BLOCKS, 1.0f, 2.0f);
                player.sendMessage(Text.literal("БАБАХ! Курица была с сюрпризом."), false);
            }
        });

        // --- 2 и 3. Прыжки и извиняющиеся криперы ---
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                handleJump(p);
                handleCreepers(p);
            }
            if (APOLOGIZED.size() > 1000) APOLOGIZED.clear(); // защита от утечки
        });
    }

    /** Курица в шляпе: курица + маленький невидимый armor stand со шлемом на спине. */
    private static void spawnChickenWithHat(ServerWorld world, Vec3d at) {
        MinecraftServer server = world.getServer();
        ServerCommandSource source = new ServerCommandSource(
                CommandOutput.DUMMY, at, Vec2f.ZERO, world, 4,
                "OffendedDirt", Text.literal("OffendedDirt"), server, null);

        String hat = HATS[RNG.nextInt(HATS.length)];
        String cmd = String.format(Locale.ROOT,
                "summon minecraft:chicken %f %f %f "
                + "{Passengers:[{id:\"minecraft:armor_stand\",Small:1b,Invisible:1b,"
                + "ArmorItems:[{},{},{},{id:\"%s\",count:1}]}]}",
                at.x, at.y, at.z, hat);

        server.getCommandManager().executeWithPrefix(source, cmd);
    }

    /** Корова мычит, когда игрок прыгает. */
    private static void handleJump(ServerPlayerEntity p) {
        UUID id = p.getUuid();
        boolean grounded = p.isOnGround();
        double y = p.getY();

        Boolean wasGrounded = WAS_GROUNDED.put(id, grounded);
        Double lastY = LAST_Y.put(id, y);

        if (wasGrounded != null && wasGrounded && !grounded && lastY != null && y - lastY > 0.1) {
            p.getServerWorld().playSound(null, p.getBlockPos(),
                    SoundEvents.ENTITY_COW_AMBIENT, SoundCategory.PLAYERS,
                    1.0f, 0.8f + RNG.nextFloat() * 0.4f);
        }
    }

    /** Крипер, который начал шипеть, извиняется над головой. */
    private static void handleCreepers(ServerPlayerEntity p) {
        ServerWorld world = p.getServerWorld();
        for (CreeperEntity c : world.getEntitiesByClass(
                CreeperEntity.class, p.getBoundingBox().expand(10), e -> true)) {

            boolean fusing = c.getFuseSpeed() > 0 || c.isIgnited();
            if (fusing && APOLOGIZED.add(c.getUuid())) {
                String apology = APOLOGIES[RNG.nextInt(APOLOGIES.length)];
                c.setCustomName(Text.literal(apology));
                c.setCustomNameVisible(true);
                p.sendMessage(Text.literal("Крипер: " + apology), false);
            } else if (!fusing) {
                APOLOGIZED.remove(c.getUuid()); // успокоился, может извиниться снова
            }
        }
    }
}
