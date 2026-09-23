package com.greev.fabric;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fabric port of Paper LorePunisher. Reads the live survival config when present
 * so keywords stay in sync; falls back to the bundled copy.
 */
public final class LorePunisherFabric implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("LorePunisher");
    private static final Path PAPER_CONFIG = Path.of("/mnt/pool/survival/plugins/LorePunisher/config.yml");
    private static final Path LOCAL_CONFIG = Path.of("/mnt/pool/fabric/config/lorepunisher.yml");

    private volatile Rules rules = Rules.empty();
    private int ticks;
    private int reloadTicks;

    @Override
    public void onInitialize() {
        reload();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        LOG.info("LorePunisher Fabric 1.1.0: {} group(s), paper-config={}",
                rules.groups.size(), Files.isRegularFile(PAPER_CONFIG));
    }

    private void tick(MinecraftServer server) {
        ticks++;
        reloadTicks++;
        if (reloadTicks >= 20 * 60) {
            reloadTicks = 0;
            reload();
        }
        if (ticks % 20 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            apply(player);
        }
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String name : List.of("lorepunisher", "lpunish", "badlore")) {
            dispatcher.register(Commands.literal(name)
                    .requires(src -> Commands.LEVEL_OWNERS.check(src.permissions()))
                    .executes(ctx -> cmdReload(ctx.getSource()))
                    .then(Commands.literal("reload").executes(ctx -> cmdReload(ctx.getSource()))));
        }
    }

    private int cmdReload(CommandSourceStack src) {
        reload();
        src.sendSuccess(() -> Component.literal(
                "LorePunisher reloaded — " + rules.groups.size() + " group(s)."), true);
        return 1;
    }

    synchronized void reload() {
        String raw = readConfig();
        try {
            rules = Rules.parse(raw);
            LOG.info("Loaded {} lore punishment group(s).", rules.groups.size());
        } catch (Exception e) {
            LOG.warn("Failed to parse LorePunisher config: {}", e.toString());
        }
    }

    private static String readConfig() {
        for (Path path : List.of(PAPER_CONFIG, LOCAL_CONFIG)) {
            try {
                if (Files.isRegularFile(path)) {
                    return Files.readString(path, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                LOG.warn("Could not read {}: {}", path, e.toString());
            }
        }
        try (InputStream in = LorePunisherFabric.class.getResourceAsStream("/config.yml")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        return "";
    }

    private void apply(ServerPlayer player) {
        Rules current = this.rules;
        Group match = current.find(player);
        Set<Holder<MobEffect>> ours = current.allEffects();
        for (Holder<MobEffect> effect : ours) {
            if (match != null && match.has(effect)) continue;
            player.removeEffect(effect);
        }
        if (match == null) return;
        for (EffectSpec spec : match.effects) {
            player.addEffect(new MobEffectInstance(
                    spec.effect,
                    MobEffectInstance.INFINITE_DURATION,
                    spec.amplifier,
                    true,
                    spec.particles,
                    true));
        }
    }

    private record EffectSpec(Holder<MobEffect> effect, int amplifier, boolean particles) {}

    private static final class Group {
        final List<String> keywords = new ArrayList<>();
        final List<EffectSpec> effects = new ArrayList<>();

        boolean has(Holder<MobEffect> effect) {
            for (EffectSpec spec : effects) {
                if (spec.effect.equals(effect)) return true;
            }
            return false;
        }
    }

    private static final class Rules {
        final List<Group> groups = new ArrayList<>();
        boolean caseSensitive;
        boolean scanEnderChest;

        static Rules empty() {
            return new Rules();
        }

        Set<Holder<MobEffect>> allEffects() {
            Set<Holder<MobEffect>> out = new LinkedHashSet<>();
            for (Group g : groups) {
                for (EffectSpec spec : g.effects) out.add(spec.effect);
            }
            return out;
        }

        Group find(ServerPlayer player) {
            for (ItemStack stack : iterate(player.getInventory())) {
                Group g = match(stack);
                if (g != null) return g;
            }
            for (EquipmentSlot slot : new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
                Group g = match(player.getItemBySlot(slot));
                if (g != null) return g;
            }
            if (scanEnderChest) {
                for (ItemStack stack : iterate(player.getEnderChestInventory())) {
                    Group g = match(stack);
                    if (g != null) return g;
                }
            }
            return null;
        }

        Group match(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return null;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null || lore.lines().isEmpty()) return null;
            StringBuilder text = new StringBuilder();
            for (Component line : lore.lines()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(line.getString());
            }
            String compare = caseSensitive ? text.toString() : text.toString().toLowerCase(Locale.ROOT);
            for (Group group : groups) {
                for (String keyword : group.keywords) {
                    String key = caseSensitive ? keyword : keyword.toLowerCase(Locale.ROOT);
                    if (!key.isEmpty() && compare.contains(key)) return group;
                }
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        static Rules parse(String raw) {
            Rules rules = new Rules();
            if (raw == null || raw.isBlank()) return rules;
            Map<String, Object> root = new Yaml().load(raw);
            if (root == null) return rules;
            rules.caseSensitive = asBool(root.get("case-sensitive"), false);
            rules.scanEnderChest = asBool(root.get("scan-ender-chest"), true);
            Object punishments = root.get("punishments");
            if (!(punishments instanceof Map<?, ?> map)) return rules;
            for (Object groupObj : map.values()) {
                if (!(groupObj instanceof Map<?, ?> groupMap)) continue;
                Group group = new Group();
                Object kw = groupMap.get("keywords");
                if (kw instanceof List<?> list) {
                    for (Object o : list) {
                        if (o != null) group.keywords.add(o.toString());
                    }
                }
                Object fx = groupMap.get("effects");
                if (fx instanceof List<?> list) {
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> emap)) continue;
                        Object type = emap.get("type");
                        if (type == null) continue;
                        Holder<MobEffect> effect = effectByName(type.toString());
                        if (effect == null) {
                            LOG.warn("Unknown effect type: {}", type);
                            continue;
                        }
                        int amp = asInt(emap.get("amplifier"), 10);
                        amp = Math.max(MobEffectInstance.MIN_AMPLIFIER,
                                Math.min(MobEffectInstance.MAX_AMPLIFIER, amp));
                        boolean particles = asBool(emap.get("particles"), false);
                        group.effects.add(new EffectSpec(effect, amp, particles));
                    }
                }
                if (!group.keywords.isEmpty() && !group.effects.isEmpty()) {
                    rules.groups.add(group);
                }
            }
            return rules;
        }
    }

    private static Iterable<ItemStack> iterate(Container container) {
        List<ItemStack> items = new ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            items.add(container.getItem(i));
        }
        return items;
    }

    private static Holder<MobEffect> effectByName(String type) {
        if (type == null || type.isBlank()) return null;
        String raw = type.trim();
        String id = raw.contains(":")
                ? raw.toLowerCase(Locale.ROOT)
                : "minecraft:" + raw.toLowerCase(Locale.ROOT).replace(' ', '_');
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) return null;
        return BuiltInRegistries.MOB_EFFECT.get(identifier).orElse(null);
    }

    private static boolean asBool(Object o, boolean fallback) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    private static int asInt(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
