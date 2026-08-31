package com.greev;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LorePunisher extends JavaPlugin implements Listener {

    private static class PunishmentGroup {
        List<String> keywords = new ArrayList<>();
        List<PotionEffect> effects = new ArrayList<>();
    }

    private List<PunishmentGroup> punishmentGroups = new ArrayList<>();
    private boolean caseSensitive;
    private boolean scanEnderChest;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfigValues();

        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                checkAndApplyEffect(p);
            }
        }, 20L, 20L);

        getCommand("lorepunisher").setExecutor((sender, cmd, label, args) -> {
            if (!sender.hasPermission("lorepunisher.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            reloadConfig();
            reloadConfigValues();
            sender.sendMessage("§aLorePunisher config reloaded!");
            return true;
        });
    }

    private void reloadConfigValues() {
        reloadConfig();
        punishmentGroups.clear();

        ConfigurationSection punishmentsSection = getConfig().getConfigurationSection("punishments");
        if (punishmentsSection != null) {
            for (String groupKey : punishmentsSection.getKeys(false)) {
                ConfigurationSection groupSection = punishmentsSection.getConfigurationSection(groupKey);
                if (groupSection == null) continue;

                PunishmentGroup group = new PunishmentGroup();
                group.keywords = groupSection.getStringList("keywords");

                List<Map<?, ?>> effectsList = groupSection.getMapList("effects");
                for (Map<?, ?> map : effectsList) {
                    String typeStr = (String) map.get("type");
                    if (typeStr == null) continue;

                    PotionEffectType type = PotionEffectType.getByName(typeStr);
                    if (type == null) {
                        getLogger().warning("Unknown effect type in group '" + groupKey + "': " + typeStr);
                        continue;
                    }

                    int amplifier = map.containsKey("amplifier") ? ((Number) map.get("amplifier")).intValue() : 10;
                    boolean particles = map.containsKey("particles") && (Boolean) map.get("particles");

                    group.effects.add(new PotionEffect(type, Integer.MAX_VALUE, amplifier, true, particles, true));
                }

                if (!group.keywords.isEmpty() && !group.effects.isEmpty()) {
                    punishmentGroups.add(group);
                    getLogger().info("Loaded punishment group '" + groupKey + "' with " + group.effects.size() + " effect(s)");
                }
            }
        }

        this.caseSensitive = getConfig().getBoolean("case-sensitive", false);
        this.scanEnderChest = getConfig().getBoolean("scan-ender-chest", true);

        getLogger().info("LorePunisher reloaded — " + punishmentGroups.size() + " punishment group(s) active.");
    }

    private PunishmentGroup getMatchingGroup(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta() || !item.getItemMeta().hasLore())
            return null;

        String loreText = String.join("\n", item.getItemMeta().getLore());
        String compareLore = caseSensitive ? loreText : loreText.toLowerCase();

        for (PunishmentGroup group : punishmentGroups) {
            for (String keyword : group.keywords) {
                String compareKey = caseSensitive ? keyword : keyword.toLowerCase();
                if (compareLore.contains(compareKey)) {
                    return group;
                }
            }
        }
        return null;
    }

    private PunishmentGroup getPlayerPunishmentGroup(Player player) {
        if (player.hasPermission("lorepunisher.bypass")) return null;

        for (ItemStack item : player.getInventory().getContents()) {
            PunishmentGroup g = getMatchingGroup(item);
            if (g != null) return g;
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            PunishmentGroup g = getMatchingGroup(item);
            if (g != null) return g;
        }
        PunishmentGroup offhand = getMatchingGroup(player.getInventory().getItemInOffHand());
        if (offhand != null) return offhand;

        if (scanEnderChest) {
            for (ItemStack item : player.getEnderChest().getContents()) {
                PunishmentGroup g = getMatchingGroup(item);
                if (g != null) return g;
            }
        }
        return null;
    }

    private void checkAndApplyEffect(Player player) {
        PunishmentGroup activeGroup = getPlayerPunishmentGroup(player);

        for (PunishmentGroup group : punishmentGroups) {
            for (PotionEffect effect : group.effects) {
                player.removePotionEffect(effect.getType());
            }
        }

        if (activeGroup != null) {
            for (PotionEffect effect : activeGroup.effects) {
                player.addPotionEffect(effect);
            }
        }
    }

    @EventHandler public void onClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p) checkAndApplyEffect(p); }
    @EventHandler public void onPickup(InventoryPickupItemEvent e) { if (e.getInventory().getHolder() instanceof Player p) checkAndApplyEffect(p); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { checkAndApplyEffect(e.getPlayer()); }
    @EventHandler public void onHeld(PlayerItemHeldEvent e) { checkAndApplyEffect(e.getPlayer()); }
    @EventHandler public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(this, () -> checkAndApplyEffect(e.getPlayer()), 10L); }
}
