package com.eaglercraft.clientguard;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class ClientGuardPlugin extends JavaPlugin implements PluginMessageListener, Listener {
    private static final String KICK_MESSAGE = "No hacks allowed";
    private static final String[] BRAND_CHANNELS = {"MC|Brand"};
    private static final String STORED_XP_NAME = "Stored XP Bottle";
    private static final String STORED_XP_PREFIX = "Stored XP: ";
    private final Set<UUID> pendingXpConfirmation = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        for (String channel : BRAND_CHANNELS) {
            getServer().getMessenger().registerIncomingPluginChannel(this, channel, this);
        }
    }

    @Override
    public void onDisable() {
        pendingXpConfirmation.clear();
        for (String channel : BRAND_CHANNELS) {
            getServer().getMessenger().unregisterIncomingPluginChannel(this, channel, this);
        }
    }

    @EventHandler
    public void onBottleInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() == Action.PHYSICAL) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        if (item.getType() == Material.GLASS_BOTTLE) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (getCurrentXp(player) <= 0) {
                pendingXpConfirmation.remove(player.getUniqueId());
                player.sendMessage("You do not have any XP to store.");
                return;
            }

            if (pendingXpConfirmation.add(player.getUniqueId())) {
                player.sendMessage("Store all your current XP in a bottle? Type yes or no.");
            } else {
                player.sendMessage("You already have an XP storage confirmation pending. Type yes or no.");
            }
            return;
        }

        if (item.getType() != Material.EXP_BOTTLE) {
            return;
        }

        Integer storedXp = getStoredXp(item);
        if (storedXp == null) {
            return;
        }

        event.setCancelled(true);
        consumeOne(event.getPlayer());
        event.getPlayer().giveExp(storedXp);
        event.getPlayer().sendMessage("Restored " + storedXp + " XP.");
    }

    @EventHandler
    public void onXpConfirmation(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!pendingXpConfirmation.remove(playerId)) {
            return;
        }

        event.setCancelled(true);
        String response = event.getMessage().trim().toLowerCase(Locale.ROOT);
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                return;
            }

            if (response.equals("yes") || response.equals("y")) {
                storeXp(player);
            } else if (response.equals("no") || response.equals("n")) {
                player.sendMessage("XP storage cancelled.");
            } else {
                pendingXpConfirmation.add(playerId);
                player.sendMessage("Please type yes or no to store your XP.");
            }
        });
    }

    private void storeXp(Player player) {
        ItemStack glassBottle = player.getInventory().getItemInMainHand();
        if (glassBottle == null || glassBottle.getType() != Material.GLASS_BOTTLE) {
            player.sendMessage("Hold a glass bottle when confirming to store your XP.");
            return;
        }

        int xp = getCurrentXp(player);
        if (xp <= 0) {
            player.sendMessage("You do not have any XP to store.");
            return;
        }

        ItemStack bottle = new ItemStack(Material.EXP_BOTTLE);
        ItemMeta meta = bottle.getItemMeta();
        meta.setDisplayName(STORED_XP_NAME);
        List<String> lore = new ArrayList<String>();
        lore.add(STORED_XP_PREFIX + xp);
        meta.setLore(lore);
        bottle.setItemMeta(meta);

        consumeOne(player);
        player.setExp(0.0F);
        player.setLevel(0);
        player.setTotalExperience(0);
        for (ItemStack leftover : player.getInventory().addItem(bottle).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        player.sendMessage("Stored " + xp + " XP in an XP bottle.");
    }

    private int getCurrentXp(Player player) {
        int level = player.getLevel();
        int xpAtLevel;
        if (level <= 16) {
            xpAtLevel = level * level + 6 * level;
        } else if (level <= 31) {
            xpAtLevel = (5 * level * level - 81 * level + 720) / 2;
        } else {
            xpAtLevel = (9 * level * level - 325 * level + 4440) / 2;
        }

        int xpToNextLevel;
        if (level < 15) {
            xpToNextLevel = 2 * level + 7;
        } else if (level < 30) {
            xpToNextLevel = 5 * level - 38;
        } else {
            xpToNextLevel = 9 * level - 158;
        }
        return xpAtLevel + Math.round(player.getExp() * xpToNextLevel);
    }

    private Integer getStoredXp(ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (!meta.hasDisplayName() || !STORED_XP_NAME.equals(meta.getDisplayName()) || !meta.hasLore()) {
            return null;
        }
        for (String line : meta.getLore()) {
            if (line.startsWith(STORED_XP_PREFIX)) {
                try {
                    int xp = Integer.parseInt(line.substring(STORED_XP_PREFIX.length()));
                    return xp > 0 ? xp : null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private void consumeOne(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!isBrandChannel(channel)) {
            return;
        }

        String brand = decodeBrand(message);
        if (brand != null && brand.toLowerCase(Locale.ROOT).contains("death client")) {
            player.kickPlayer(KICK_MESSAGE);
        }
    }

    private boolean isBrandChannel(String channel) {
        for (String brandChannel : BRAND_CHANNELS) {
            if (brandChannel.equalsIgnoreCase(channel)) {
                return true;
            }
        }
        return false;
    }

    private String decodeBrand(byte[] message) {
        int length = 0;
        int position = 0;
        for (int index = 0; index < 5; index++) {
            if (position >= message.length) {
                return null;
            }

            int current = message[position++] & 0xFF;
            if (index == 4 && (current & 0xF0) != 0) {
                return null;
            }
            length |= (current & 0x7F) << (index * 7);
            if ((current & 0x80) == 0) {
                if (length < 0 || length > message.length - position) {
                    return null;
                }
                return new String(message, position, length, StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
