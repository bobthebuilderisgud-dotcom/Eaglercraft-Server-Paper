package com.eaglercraft.backpack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class BackpackPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final String LORE_PREFIX = ChatColor.BLACK + "EaglerBackpack:";
    private static final int CHEST_INVENTORY_SIZE = 27;

    private final Map<String, Inventory> openBackpacks = new HashMap<String, Inventory>();
    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "backpacks.yml");
        data = new YamlConfiguration();
        if (dataFile.exists()) {
            try {
                data.load(dataFile);
            } catch (IOException | InvalidConfigurationException exception) {
                throw new IllegalStateException("Could not load backpack storage", exception);
            }
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("backpack").setExecutor(this);
        registerRecipe();
    }

    @Override
    public void onDisable() {
        saveOpenBackpacks();
        saveData();
    }

    private void saveOpenBackpacks() {
        for (Map.Entry<String, Inventory> entry : openBackpacks.entrySet()) {
            storeContents(entry.getKey(), entry.getValue());
        }
    }

    private void registerRecipe() {
        ItemStack backpack = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemMeta meta = backpack.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Backpack");
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "Wear or hold, then use /backpack open");
        lore.add(ChatColor.GRAY + "Use /backpack close to close");
        lore.add(ChatColor.GRAY + "Contents save when you close it");
        lore.add(LORE_PREFIX);
        meta.setLore(lore);
        backpack.setItemMeta(meta);

        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "backpack"), backpack);
        recipe.shape("LLL", "LCL", "LLL");
        recipe.setIngredient('L', Material.LEATHER);
        recipe.setIngredient('C', Material.CHEST);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getCurrentItem();
        if (!isBackpack(result)) {
            return;
        }

        ItemMeta meta = result.getItemMeta();
        List<String> lore = meta.getLore();
        lore.set(getMarkerIndex(meta), LORE_PREFIX + UUID.randomUUID().toString());
        meta.setLore(lore);
        result.setItemMeta(meta);
        event.setCurrentItem(result);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        if (args.length != 1) {
            sendUsage(player);
            return true;
        }

        if ("open".equalsIgnoreCase(args[0])) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (!isBackpack(item)) {
                item = player.getInventory().getItemInOffHand();
            }
            if (!isBackpack(item)) {
                item = player.getInventory().getChestplate();
            }
            if (!isBackpack(item)) {
                player.sendMessage(ChatColor.RED + "Wear or hold a backpack to open it.");
                return true;
            }

            openBackpack(player, item);
            return true;
        }

        if ("close".equalsIgnoreCase(args[0])) {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof BackpackHolder)) {
                player.sendMessage(ChatColor.RED + "You do not have a backpack open.");
                return true;
            }

            player.closeInventory();
            return true;
        }

        sendUsage(player);
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(ChatColor.RED + "Usage: /backpack <open|close>");
    }

    private void openBackpack(Player player, ItemStack item) {
        String id = getBackpackId(item);
        if (id == null) {
            id = UUID.randomUUID().toString();
            setBackpackId(item, id);
        }

        Inventory inventory = openBackpacks.get(id);
        if (inventory == null) {
            String title = item.getItemMeta().hasDisplayName()
                    ? item.getItemMeta().getDisplayName()
                    : "Backpack";
            BackpackHolder holder = new BackpackHolder(id);
            inventory = Bukkit.createInventory(holder, CHEST_INVENTORY_SIZE, title);
            loadContents(id, inventory);
            holder.inventory = inventory;
            openBackpacks.put(id, inventory);
        }

        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackHolder)) {
            return;
        }

        BackpackHolder holder = (BackpackHolder) event.getInventory().getHolder();
        storeContents(holder.id, event.getInventory());
        if (event.getInventory().getViewers().isEmpty()) {
            openBackpacks.remove(holder.id);
        }
        saveData();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof BackpackHolder)) {
            return;
        }

        if (event.getClickedInventory() == topInventory) {
            InventoryAction action = event.getAction();
            if (isBackpack(event.getCursor())
                    && (action == InventoryAction.PLACE_ALL
                            || action == InventoryAction.PLACE_SOME
                            || action == InventoryAction.PLACE_ONE
                            || action == InventoryAction.SWAP_WITH_CURSOR)) {
                event.setCancelled(true);
                return;
            }

            if (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD) {
                int hotbarButton = event.getHotbarButton();
                if (hotbarButton >= 0 && isBackpack(event.getWhoClicked().getInventory().getItem(hotbarButton))) {
                    event.setCancelled(true);
                    return;
                }
            }
        } else if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                && isBackpack(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof BackpackHolder)) {
            return;
        }

        if (!isBackpack(event.getOldCursor())) {
            return;
        }
        for (Integer rawSlot : event.getRawSlots()) {
            if (rawSlot < topInventory.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        saveOpenBackpacks();
        saveData();

        Inventory playerInventory = event.getEntity().getInventory();
        for (int slot = 0; slot < playerInventory.getSize(); slot++) {
            ItemStack item = playerInventory.getItem(slot);
            if (!isBackpack(item)) {
                continue;
            }

            String id = getBackpackId(item);
            if (id == null) {
                id = UUID.randomUUID().toString();
                setBackpackId(item, id);
            }
            if (!containsBackpack(event.getDrops(), id)) {
                event.getDrops().add(item.clone());
            }
            if (event.getKeepInventory()) {
                playerInventory.setItem(slot, null);
            }
        }
    }

    private boolean containsBackpack(List<ItemStack> items, String id) {
        for (ItemStack item : items) {
            if (isBackpack(item) && id.equals(getBackpackId(item))) {
                return true;
            }
        }
        return false;
    }

    private boolean isBackpack(ItemStack item) {
        if (item == null || item.getType() != Material.LEATHER_CHESTPLATE || !item.hasItemMeta()) {
            return false;
        }
        return getMarkerIndex(item.getItemMeta()) >= 0;
    }

    private int getMarkerIndex(ItemMeta meta) {
        if (!meta.hasLore()) {
            return -1;
        }
        List<String> lore = meta.getLore();
        for (int index = 0; index < lore.size(); index++) {
            String line = lore.get(index);
            if (line != null && line.startsWith(LORE_PREFIX)) {
                return index;
            }
        }
        return -1;
    }

    private String getBackpackId(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String marker = meta.getLore().get(getMarkerIndex(meta));
        String id = marker.substring(LORE_PREFIX.length());
        if (id.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void setBackpackId(ItemStack item, String id) {
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta.getLore();
        lore.set(getMarkerIndex(meta), LORE_PREFIX + id);
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private void loadContents(String id, Inventory inventory) {
        for (int slot = 0; slot < CHEST_INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, data.getItemStack("backpacks." + id + "." + slot));
        }
    }

    private void storeContents(String id, Inventory inventory) {
        for (int slot = 0; slot < CHEST_INVENTORY_SIZE; slot++) {
            data.set("backpacks." + id + "." + slot, inventory.getItem(slot));
        }
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save backpack storage: " + exception.getMessage());
        }
    }

    private static final class BackpackHolder implements InventoryHolder {
        private final String id;
        private Inventory inventory;

        private BackpackHolder(String id) {
            this.id = id;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
