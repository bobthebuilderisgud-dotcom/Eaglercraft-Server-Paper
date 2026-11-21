package com.eaglercraft.shop;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class ShopPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final int MENU_SIZE = 54;
    private static final int LISTING_SLOTS = 45;
    private static final long MAX_PRICE = (long) Integer.MAX_VALUE * 100L;
    private static final int CURRENCY_VERSION = 1;
    private static final long COMBAT_DURATION_MILLIS = 20000L;
    private static final long TELEPORT_REQUEST_DURATION_MILLIS = 60000L;
    private static final int TELEPORT_WARMUP_TICKS = 100;
    private static final String SOULBOUND_LORE = ChatColor.DARK_PURPLE + "Soulbound";
    private static final String SOULBOUND_OWNER_LORE_PREFIX =
            ChatColor.DARK_PURPLE + "Soulbound to ";
    private static final String SOULBOUND_OWNER_PREFIX = ChatColor.BLACK + "SoulboundOwner:";
    private static final UUID LEGACY_SOULBOUND_OWNER =
            UUID.fromString("8a337563-3c12-301a-89cd-24e1f3102227");
    private static final String SOULBOUND_BOOK_LORE =
            ChatColor.LIGHT_PURPLE + "Soulbound Enchantment";
    private static final String BACKPACK_LORE_PREFIX = ChatColor.BLACK + "EaglerBackpack:";
    private static final String[] PUBLIC_COMMANDS = {
        "SERVER COMMANDS",
        "/shop - browse",
        "/shop balance",
        "/shop mine",
        "/sell <price>",
        "/offers",
        "/earn",
        "/earn prices",
        "/ptp <player>",
        "/ptphere <player>",
        "/home",
        "/spawn",
        "/rule"
    };

    private final Map<UUID, BossBar> balanceBars = new ConcurrentHashMap<UUID, BossBar>();
    private final Map<UUID, BossBar> combatBars = new ConcurrentHashMap<UUID, BossBar>();
    private final Map<UUID, Long> combatExpiry = new ConcurrentHashMap<UUID, Long>();
    private final Map<UUID, TeleportRequest> pendingTeleportRequests =
            new ConcurrentHashMap<UUID, TeleportRequest>();
    private final Map<UUID, TeleportWarmup> teleportWarmups =
            new ConcurrentHashMap<UUID, TeleportWarmup>();
    private final Map<UUID, Boolean> logJailTeleports =
            new ConcurrentHashMap<UUID, Boolean>();
    private final Map<UUID, Scoreboard> originalScoreboards = new ConcurrentHashMap<UUID, Scoreboard>();
    private final Map<UUID, Objective> commandObjectives = new ConcurrentHashMap<UUID, Objective>();
    private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<UUID, PendingInput>();
    private File dataFile;
    private YamlConfiguration data;
    private BukkitTask combatBarTask;
    private volatile boolean shuttingDown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "shop.yml");
        data = new YamlConfiguration();
        if (dataFile.exists()) {
            try {
                data.load(dataFile);
            } catch (IOException | InvalidConfigurationException exception) {
                throw new IllegalStateException("Could not load shop data", exception);
            }
        }
        migrateCurrencyToCents();
        cancelUnreservedLegacyOffers();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("shop").setExecutor(this);
        getCommand("sell").setExecutor(this);
        getCommand("soulbound").setExecutor(this);
        getCommand("offers").setExecutor(this);
        getCommand("earn").setExecutor(this);
        getCommand("ptp").setExecutor(this);
        getCommand("ptphere").setExecutor(this);
        getCommand("home").setExecutor(this);
        getCommand("spawn").setExecutor(this);
        getCommand("tpaccept").setExecutor(this);
        getCommand("tpdeny").setExecutor(this);
        getCommand("logjail").setExecutor(this);
        getCommand("unlogjail").setExecutor(this);
        getCommand("rule").setExecutor(this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            showBalance(player);
            showCombatBar(player);
            showCommandSidebar(player);
            restoreLogJail(player);
            restoreSoulboundChestItems(player);
        }
        combatBarTask = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                for (UUID playerId : combatBars.keySet()) {
                    updateCombatBar(playerId);
                }
            }
        }, 1L, 5L);
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        saveData();
        for (Map.Entry<UUID, TeleportWarmup> entry : teleportWarmups.entrySet()) {
            cancelTeleportWarmup(entry.getKey(), entry.getValue());
        }
        for (BossBar bar : balanceBars.values()) {
            bar.removeAll();
        }
        balanceBars.clear();
        if (combatBarTask != null) {
            combatBarTask.cancel();
        }
        for (BossBar bar : combatBars.values()) {
            bar.removeAll();
        }
        combatBars.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Scoreboard original = originalScoreboards.get(player.getUniqueId());
            if (original != null) {
                player.setScoreboard(original);
            }
        }
        originalScoreboards.clear();
        commandObjectives.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("shop")) {
            return handleShopCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("sell")) {
            return handleSellCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("soulbound")) {
            return handleSoulboundCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("offers")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }
            if (args.length != 0) {
                sender.sendMessage(ChatColor.RED + "Usage: /offers");
                return true;
            }
            openOffers((Player) sender);
            return true;
        }
        if (command.getName().equalsIgnoreCase("earn")) {
            return handleEarnCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("ptp")) {
            return handleTeleportCommand(sender, args, true);
        }
        if (command.getName().equalsIgnoreCase("ptphere")) {
            return handleTeleportCommand(sender, args, false);
        }
        if (command.getName().equalsIgnoreCase("home")) {
            return handleHomeCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("spawn")) {
            return handleSpawnCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("tpaccept")) {
            return handleTeleportResponse(sender, args, true);
        }
        if (command.getName().equalsIgnoreCase("tpdeny")) {
            return handleTeleportResponse(sender, args, false);
        }
        if (command.getName().equalsIgnoreCase("logjail")) {
            return handleLogJailCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("unlogjail")) {
            return handleUnlogJailCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("rule")) {
            return handleRuleCommand(sender, args);
        }
        return false;
    }

    private boolean handleRuleCommand(CommandSender sender, String[] args) {
        if (args.length != 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /rule");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Server Rules:");
        sender.sendMessage(ChatColor.GRAY + "- No raiding offline players");
        sender.sendMessage(ChatColor.GRAY + "- No betraying teammates");
        sender.sendMessage(ChatColor.GRAY + "- No hacks");
        sender.sendMessage(ChatColor.GRAY + "- No hack clients");
        sender.sendMessage(ChatColor.RED
                + "Breaking any rule will result in being teleported to the server owner and killed.");
        return true;
    }

    private boolean handleLogJailCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shop.logjail")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to log-jail players.");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /logjail <player>");
            return true;
        }

        Player jailer = (Player) sender;
        OfflinePlayer targetProfile = Bukkit.getOfflinePlayer(args[0]);
        if (!targetProfile.hasPlayedBefore() && !targetProfile.isOnline()) {
            jailer.sendMessage(ChatColor.RED + "That player has not played on this server.");
            return true;
        }
        UUID targetId = targetProfile.getUniqueId();
        if (targetId.equals(jailer.getUniqueId())) {
            jailer.sendMessage(ChatColor.RED + "You cannot log-jail yourself.");
            return true;
        }

        String path = logJailPath(targetId);
        if (data.isConfigurationSection(path)) {
            jailer.sendMessage(ChatColor.RED + "That player is already log-jailed.");
            return true;
        }

        Location destination = jailer.getLocation().clone();
        Vector direction = destination.getDirection().setY(0.0);
        if (direction.lengthSquared() < 0.0001) {
            double yaw = Math.toRadians(destination.getYaw());
            direction.setX(-Math.sin(yaw)).setZ(Math.cos(yaw));
        }
        destination.add(direction.normalize());

        Player target = Bukkit.getPlayer(targetId);
        if (target != null && !teleportForLogJail(target, destination)) {
            jailer.sendMessage(ChatColor.RED + "Could not teleport that player.");
            return true;
        }

        data.set(path + ".name", targetProfile.getName() == null
                ? args[0] : targetProfile.getName());
        data.set(path + ".world", destination.getWorld().getName());
        data.set(path + ".x", destination.getX());
        data.set(path + ".y", destination.getY());
        data.set(path + ".z", destination.getZ());
        data.set(path + ".yaw", destination.getYaw());
        data.set(path + ".pitch", destination.getPitch());
        saveData();

        if (target == null) {
            jailer.sendMessage(ChatColor.GREEN + args[0]
                    + " will be log-jailed when they next join.");
        } else {
            target.sendMessage(ChatColor.RED
                    + "You are log-jailed. Use /unlogjail to release everyone.");
            jailer.sendMessage(ChatColor.GREEN + target.getName()
                    + " has been log-jailed.");
        }
        return true;
    }

    private boolean handleUnlogJailCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /unlogjail");
            return true;
        }

        Player player = (Player) sender;
        if (!data.isConfigurationSection(logJailPath(player.getUniqueId()))
                && !player.hasPermission("shop.logjail")) {
            player.sendMessage(ChatColor.RED
                    + "Only operators or log-jailed players can release all log-jails.");
            return true;
        }

        for (Player jailed : Bukkit.getOnlinePlayers()) {
            if (!jailed.equals(player)
                    && data.isConfigurationSection(logJailPath(jailed.getUniqueId()))) {
                jailed.sendMessage(ChatColor.GREEN
                        + "All log-jails have been released.");
            }
        }
        data.set("logjail", null);
        saveData();
        player.sendMessage(ChatColor.GREEN + "All log-jails have been released.");
        return true;
    }

    private String logJailPath(UUID playerId) {
        return "logjail." + playerId;
    }

    private boolean teleportForLogJail(Player player, Location destination) {
        UUID playerId = player.getUniqueId();
        logJailTeleports.put(playerId, Boolean.TRUE);
        try {
            return player.teleport(destination);
        } finally {
            logJailTeleports.remove(playerId);
        }
    }

    private void restoreLogJail(Player player) {
        String path = logJailPath(player.getUniqueId());
        if (!data.isConfigurationSection(path)) {
            return;
        }

        String worldName = data.getString(path + ".world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("Could not restore log-jail for " + player.getName()
                    + ": world '" + worldName + "' is unavailable.");
            player.sendMessage(ChatColor.RED
                    + "Your log-jail destination is unavailable. Use /unlogjail to release everyone.");
            return;
        }

        Location destination = new Location(world,
                data.getDouble(path + ".x"),
                data.getDouble(path + ".y"),
                data.getDouble(path + ".z"),
                (float) data.getDouble(path + ".yaw"),
                (float) data.getDouble(path + ".pitch"));
        if (!teleportForLogJail(player, destination)) {
            getLogger().warning("Could not teleport log-jailed player " + player.getName() + ".");
            player.sendMessage(ChatColor.RED
                    + "Could not move you to your log-jail destination.");
            return;
        }
        player.sendMessage(ChatColor.RED
                + "You are log-jailed. Use /unlogjail to release everyone.");
    }

    private boolean handleHomeCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /home");
            return true;
        }

        Player player = (Player) sender;
        if (denyTeleportInCombat(player)) {
            return true;
        }
        if (teleportWarmups.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You are already preparing for a teleport.");
            return true;
        }

        Location respawn = player.getBedSpawnLocation();
        if (respawn == null) {
            respawn = player.getWorld().getSpawnLocation();
        }
        startLocationTeleportWarmup(player, respawn, "your respawn point");
        return true;
    }

    private boolean handleSpawnCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /spawn");
            return true;
        }

        Player player = (Player) sender;
        if (denyTeleportInCombat(player)) {
            return true;
        }
        if (teleportWarmups.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You are already preparing for a teleport.");
            return true;
        }

        startLocationTeleportWarmup(player, player.getWorld().getSpawnLocation(), "the world spawn");
        return true;
    }

    private boolean handleTeleportCommand(CommandSender sender, String[] args,
            boolean requesterTravels) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can request a teleport.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /"
                    + (requesterTravels ? "ptp" : "ptphere") + " <player>");
            return true;
        }

        Player requester = (Player) sender;
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            requester.sendMessage(ChatColor.RED + "That player is not online.");
            return true;
        }
        if (target.equals(requester)) {
            requester.sendMessage(ChatColor.RED + "You cannot request to teleport to yourself.");
            return true;
        }
        if (denyTeleportBetweenPlayers(requester, target)) {
            return true;
        }

        UUID requestId = UUID.randomUUID();
        TeleportRequest request = new TeleportRequest(
                requestId, requester.getUniqueId(), System.currentTimeMillis()
                        + TELEPORT_REQUEST_DURATION_MILLIS, requesterTravels);
        TeleportRequest replaced = pendingTeleportRequests.put(target.getUniqueId(), request);
        if (replaced != null) {
            Player previousRequester = Bukkit.getPlayer(replaced.requesterId);
            if (previousRequester != null) {
                previousRequester.sendMessage(ChatColor.RED + "Your teleport request to "
                        + target.getName() + " was replaced by a newer request.");
            }
        }

        String requestMessage = requesterTravels
                ? requester.getName() + " wants to teleport to you. Allow them to teleport to you? "
                : requester.getName() + " wants you to teleport to them. Allow them to teleport? ";
        TextComponent prompt = new TextComponent(ChatColor.YELLOW + requestMessage);
        TextComponent yes = new TextComponent(ChatColor.GREEN + "[Yes]");
        yes.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/tpaccept " + requestId));
        TextComponent no = new TextComponent(ChatColor.RED + "[No]");
        no.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/tpdeny " + requestId));
        prompt.addExtra(yes);
        prompt.addExtra(new TextComponent(" "));
        prompt.addExtra(no);
        target.spigot().sendMessage(prompt);
        requester.sendMessage(ChatColor.GREEN + "Teleport request sent to "
                + target.getName() + ".");
        return true;
    }

    private boolean handleTeleportResponse(CommandSender sender, String[] args, boolean accept) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can respond to teleport requests.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "This button is only available from a teleport request.");
            return true;
        }

        Player target = (Player) sender;
        UUID targetId = target.getUniqueId();
        TeleportRequest request = pendingTeleportRequests.get(targetId);
        if (request == null || !request.id.toString().equalsIgnoreCase(args[0])) {
            target.sendMessage(ChatColor.RED + "That teleport request is no longer available.");
            return true;
        }
        if (!pendingTeleportRequests.remove(targetId, request)) {
            target.sendMessage(ChatColor.RED + "That teleport request is no longer available.");
            return true;
        }
        if (request.expiresAt < System.currentTimeMillis()) {
            target.sendMessage(ChatColor.RED + "That teleport request has expired.");
            return true;
        }

        Player requester = Bukkit.getPlayer(request.requesterId);
        if (requester == null) {
            target.sendMessage(ChatColor.RED + "That player is no longer online.");
            return true;
        }
        if (!accept) {
            requester.sendMessage(ChatColor.RED + target.getName() + " declined your teleport request.");
            target.sendMessage(ChatColor.YELLOW + "You declined "
                    + requester.getName() + "'s teleport request.");
            return true;
        }
        if (denyTeleportBetweenPlayers(requester, target)) {
            return true;
        }
        Player traveler = request.requesterTravels ? requester : target;
        Player destination = request.requesterTravels ? target : requester;
        if (teleportWarmups.containsKey(traveler.getUniqueId())) {
            target.sendMessage(ChatColor.RED + traveler.getName()
                    + " is already preparing for a teleport.");
            return true;
        }
        startTeleportWarmup(traveler, destination);
        return true;
    }

    private void startTeleportWarmup(final Player requester, final Player target) {
        startTeleportWarmup(requester, new TeleportWarmup(target.getUniqueId(), null, null), target);
    }

    private void startLocationTeleportWarmup(Player requester, Location destination,
            String destinationName) {
        startTeleportWarmup(requester,
                new TeleportWarmup(null, destination.clone(), destinationName), null);
    }

    private void startTeleportWarmup(final Player requester, final TeleportWarmup warmup,
            final Player target) {
        final UUID requesterId = requester.getUniqueId();
        teleportWarmups.put(requesterId, warmup);
        requester.sendMessage(ChatColor.YELLOW + "Teleporting in 5 seconds. Please stay still.");
        if (target != null) {
            target.sendMessage(ChatColor.YELLOW + requester.getName()
                    + " will teleport to you in 5 seconds.");
        }
        spawnTeleportSmoke(requester);

        warmup.task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                Player onlineRequester = Bukkit.getPlayer(requesterId);
                Player onlineTarget = warmup.targetId == null
                        ? null : Bukkit.getPlayer(warmup.targetId);
                if (onlineRequester == null) {
                    cancelTeleportWarmup(requesterId, warmup);
                    return;
                }
                if (warmup.targetId != null && onlineTarget == null) {
                    cancelTeleportWarmup(requesterId, warmup);
                    onlineRequester.sendMessage(ChatColor.RED
                            + "Teleport cancelled because the target left.");
                    return;
                }
                if (onlineTarget != null
                        && denyTeleportBetweenPlayers(onlineRequester, onlineTarget)) {
                    cancelTeleportWarmup(requesterId, warmup);
                    onlineRequester.sendMessage(ChatColor.RED
                            + "Teleport cancelled because you or the target entered combat.");
                    onlineTarget.sendMessage(ChatColor.RED
                            + "Teleport cancelled because you or the target entered combat.");
                    return;
                }
                if (onlineTarget == null && denyTeleportInCombat(onlineRequester)) {
                    cancelTeleportWarmup(requesterId, warmup);
                    return;
                }

                warmup.elapsedTicks += 5;
                if (warmup.elapsedTicks >= TELEPORT_WARMUP_TICKS) {
                    cancelTeleportWarmup(requesterId, warmup);
                    boolean teleported = onlineTarget == null
                            ? onlineRequester.teleport(warmup.destination)
                            : onlineRequester.teleport(onlineTarget);
                    if (teleported) {
                        if (onlineTarget == null) {
                            onlineRequester.sendMessage(ChatColor.GREEN + "Teleported to "
                                    + warmup.destinationName + ".");
                        } else {
                            onlineRequester.sendMessage(ChatColor.GREEN + "Teleported to "
                                    + onlineTarget.getName() + ".");
                            onlineTarget.sendMessage(ChatColor.GREEN + onlineRequester.getName()
                                    + " teleported to you.");
                        }
                    } else {
                        onlineRequester.sendMessage(ChatColor.RED + "Teleport failed.");
                        if (onlineTarget != null) {
                            onlineTarget.sendMessage(ChatColor.RED + "Could not teleport "
                                    + onlineRequester.getName() + ".");
                        }
                    }
                    return;
                }
                spawnTeleportSmoke(onlineRequester);
            }
        }, 5L, 5L);
    }

    private void spawnTeleportSmoke(Player player) {
        Location location = player.getLocation().add(0.0, 0.5, 0.0);
        player.getWorld().spawnParticle(Particle.SMOKE_NORMAL, location, 20, 0.6, 0.8, 0.6, 0.01);
    }

    private void cancelTeleportWarmup(UUID requesterId, TeleportWarmup warmup) {
        if (warmup != null && teleportWarmups.remove(requesterId, warmup)
                && warmup.task != null) {
            warmup.task.cancel();
        }
    }

    @EventHandler
    public void onTeleportWarmupMove(PlayerMoveEvent event) {
        if (!teleportWarmups.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()
                || from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLogJailMove(PlayerMoveEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (logJailTeleports.containsKey(playerId)
                || !data.isConfigurationSection(logJailPath(playerId))) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getWorld() != to.getWorld()
                || from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ()) {
            Location lockedLocation = from.clone();
            lockedLocation.setYaw(to.getYaw());
            lockedLocation.setPitch(to.getPitch());
            event.setTo(lockedLocation);
        }
    }

    private boolean handleShopCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can open the shop.");
                return true;
            }
            openShop((Player) sender, 0);
            return true;
        }

        if (args.length == 1 && (args[0].equalsIgnoreCase("mine")
                || args[0].equalsIgnoreCase("listings"))) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can manage listings.");
                return true;
            }
            openMyListings((Player) sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("setbalance") || args[0].equalsIgnoreCase("addbalance")) {
            if (!sender.hasPermission("shop.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to manage balances.");
                return true;
            }
            if (args.length != 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /shop " + args[0] + " <player> <amount>");
                return true;
            }
            applyAdminBalance(sender, args[0].equalsIgnoreCase("setbalance"), args[1], args[2]);
            return true;
        }

        if (args[0].equalsIgnoreCase("balance") && args.length == 1 && sender instanceof Player) {
            Player player = (Player) sender;
            player.sendMessage(ChatColor.GOLD + "Balance: "
                    + formatAmount(getBalance(player.getUniqueId())));
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Usage: /shop [balance|setbalance|addbalance]");
        return true;
    }

    private void applyAdminBalance(CommandSender sender, boolean set, String targetName, String amountText) {
        long amount = parseAmount(amountText);
        if (amount < 0) {
            sender.sendMessage(ChatColor.RED + "Amount must be between $0.00 and "
                    + formatAmount(MAX_PRICE) + ".");
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        UUID id = target.getUniqueId();
        long reserved = getReservedBalance(id);
        if (set) {
            if (amount > MAX_PRICE - reserved) {
                sender.sendMessage(ChatColor.RED + "That balance would exceed the maximum after reserved offers.");
                return;
            }
            data.set("balances." + id, amount);
        } else {
            long current = getBalance(id);
            if (current > MAX_PRICE - reserved - amount) {
                sender.sendMessage(ChatColor.RED + "That would exceed the maximum balance.");
                return;
            }
            data.set("balances." + id, current + amount);
        }
        saveData();
        updateBalanceBar(id);
        sender.sendMessage(ChatColor.GREEN + (set ? "Set " : "Added to ") + targetName + "'s balance.");
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            online.sendMessage(ChatColor.GOLD + "Your balance is now "
                    + formatAmount(getBalance(id)) + ".");
        }
    }

    private boolean handleEarnCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }
            Player player = (Player) sender;
            if (!denyMarketActionInCombat(player)) {
                openEarn(player);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("prices") && args.length == 1) {
            ConfigurationSection prices = getConfig().getConfigurationSection("earn-prices");
            List<String> materials = prices == null
                    ? new ArrayList<String>() : new ArrayList<String>(prices.getKeys(false));
            ConfigurationSection overrides = data.getConfigurationSection("earn-prices");
            if (overrides != null) {
                for (String material : overrides.getKeys(false)) {
                    if (!materials.contains(material)) {
                        materials.add(material);
                    }
                }
            }
            if (materials.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No default earn prices are configured.");
            } else {
                sender.sendMessage(ChatColor.GOLD + "Earn prices (per item):");
                for (String material : materials) {
                    sender.sendMessage(ChatColor.YELLOW + material + ": "
                            + formatAmount(getEarnPrice(material)));
                }
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("price")) {
            if (!sender.hasPermission("shop.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to change earn prices.");
                return true;
            }
            if (args.length != 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /earn price <material[:data]> <price-per-item>");
                return true;
            }
            String materialKey = args[1].toUpperCase(Locale.ROOT);
            String[] materialParts = materialKey.split(":", -1);
            if (!isValidMaterialKey(materialKey)) {
                sender.sendMessage(ChatColor.RED + "Unknown item material: " + args[1]);
                return true;
            }
            Material material = Material.valueOf(materialParts[0]);
            long price = parseAmount(args[2]);
            if (price < 0) {
                sender.sendMessage(ChatColor.RED + "Price must be between $0.00 and "
                        + formatAmount(MAX_PRICE) + ".");
                return true;
            }
            materialKey = material.name() + (materialParts.length == 2 ? ":" + materialParts[1] : "");
            data.set("earn-prices." + materialKey, price);
            saveData();
            sender.sendMessage(ChatColor.GREEN + "Set " + materialKey + " to "
                    + formatAmount(price) + " per item. Use $0.00 to disable selling it.");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Usage: /earn [prices|price <material[:data]> <price-per-item>]");
        return true;
    }

    private void openEarn(Player player) {
        EarnHolder holder = new EarnHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, ChatColor.DARK_GREEN + "Earn Items");
        holder.inventory = inventory;
        inventory.setItem(49, menuItem(Material.EMERALD, ChatColor.GREEN + "Sell priced items"));
        inventory.setItem(53, menuItem(Material.BARRIER, ChatColor.RED + "Close"));
        player.openInventory(inventory);
    }

    private boolean handleSellCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can list items.");
            return true;
        }
        if (args.length > 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /sell [price-per-item]");
            return true;
        }

        Player player = (Player) sender;
        if (denyMarketActionInCombat(player)) {
            return true;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + "Hold the item you want to sell in your main hand.");
            return true;
        }
        if (isSoulbound(item)) {
            player.sendMessage(ChatColor.RED + "Soulbound items cannot be sold.");
            return true;
        }

        if (args.length == 1) {
            long price = parseAmount(args[0]);
            if (price <= 0) {
                player.sendMessage(ChatColor.RED + "Price must be greater than $0.00.");
                return true;
            }
            createListing(player, item, price);
        } else {
            pendingInputs.put(player.getUniqueId(), PendingInput.listing());
            player.sendMessage(ChatColor.YELLOW + "Enter the price per item, or type cancel.");
        }
        return true;
    }

    private void createListing(Player seller, ItemStack heldItem, long price) {
        if (denyMarketActionInCombat(seller)) {
            return;
        }
        if (heldItem == null || heldItem.getType() == Material.AIR
                || price <= 0 || price > MAX_PRICE || price > MAX_PRICE / heldItem.getAmount()) {
            seller.sendMessage(ChatColor.RED + "That item or price cannot be listed.");
            return;
        }
        if (isSoulbound(heldItem)) {
            seller.sendMessage(ChatColor.RED + "Soulbound items cannot be sold.");
            return;
        }

        UUID id = UUID.randomUUID();
        ItemStack escrow = heldItem.clone();
        int amount = heldItem.getAmount();
        seller.getInventory().setItemInMainHand(null);
        String path = "listings." + id;
        data.set(path + ".seller", seller.getUniqueId().toString());
        data.set(path + ".seller-name", seller.getName());
        data.set(path + ".price", price);
        data.set(path + ".item", escrow);
        saveData();
        seller.sendMessage(ChatColor.GREEN + "Listed " + amount + " " + itemName(escrow)
                + " for " + formatAmount(price) + " each.");
    }

    private void openShop(Player player, int page) {
        ShopHolder holder = new ShopHolder(Math.max(0, page));
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, ChatColor.DARK_GREEN + "Shop");
        holder.inventory = inventory;
        List<String> listingIds = getListingIds();
        int start = holder.page * LISTING_SLOTS;
        int end = Math.min(start + LISTING_SLOTS, listingIds.size());

        for (int index = start; index < end; index++) {
            String id = listingIds.get(index);
            String path = listingPath(id);
            ItemStack item = data.getItemStack(path + ".item");
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            item = item.clone();
            List<String> lore = item.hasItemMeta() && item.getItemMeta().hasLore()
                    ? new ArrayList<String>(item.getItemMeta().getLore()) : new ArrayList<String>();
            lore.add(ChatColor.GRAY + "Seller: " + data.getString(path + ".seller-name", "Unknown"));
            lore.add(ChatColor.GOLD + "Price each: " + formatAmount(data.getLong(path + ".price")));
            lore.add(ChatColor.GRAY + "Available: " + item.getAmount());
            lore.add(ChatColor.YELLOW + "Click to view and make an offer");
            ItemMeta meta = item.getItemMeta();
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(index - start, item);
        }

        if (holder.page > 0) {
            inventory.setItem(45, menuItem(Material.ARROW, ChatColor.YELLOW + "Previous page"));
        }
        inventory.setItem(47, menuItem(Material.CHEST, ChatColor.YELLOW + "My listings"));
        inventory.setItem(49, menuItem(Material.GOLD_INGOT,
                ChatColor.GOLD + "Your balance: "
                        + formatAmount(getBalance(player.getUniqueId()))));
        if (end < listingIds.size()) {
            inventory.setItem(53, menuItem(Material.ARROW, ChatColor.YELLOW + "Next page"));
        }
        player.openInventory(inventory);
    }

    private void openMyListings(Player player) {
        MyListingsHolder holder = new MyListingsHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, ChatColor.DARK_GREEN + "My Listings");
        holder.inventory = inventory;
        int slot = 0;
        for (String id : getListingIds()) {
            String path = listingPath(id);
            if (!player.getUniqueId().toString().equals(data.getString(path + ".seller"))
                    || slot >= LISTING_SLOTS) {
                continue;
            }
            ItemStack item = data.getItemStack(path + ".item");
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            item = item.clone();
            List<String> lore = item.hasItemMeta() && item.getItemMeta().hasLore()
                    ? new ArrayList<String>(item.getItemMeta().getLore()) : new ArrayList<String>();
            lore.add(ChatColor.GOLD + "Price each: " + formatAmount(data.getLong(path + ".price")));
            lore.add(ChatColor.YELLOW + "Click to remove listing and reclaim items");
            ItemMeta meta = item.getItemMeta();
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot++, item);
        }
        inventory.setItem(49, menuItem(Material.BARRIER, ChatColor.RED + "Back to shop"));
        player.openInventory(inventory);
    }

    private void openListing(Player player, String id, int page) {
        String path = listingPath(id);
        ItemStack listed = data.getItemStack(path + ".item");
        if (listed == null || listed.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + "That listing is no longer available.");
            openShop(player, page);
            return;
        }
        ListingHolder holder = new ListingHolder(id, page);
        Inventory inventory = Bukkit.createInventory(holder, 27, ChatColor.DARK_GREEN + "Listing");
        holder.inventory = inventory;

        ItemStack display = listed.clone();
        List<String> lore = listed.hasItemMeta() && listed.getItemMeta().hasLore()
                ? new ArrayList<String>(listed.getItemMeta().getLore()) : new ArrayList<String>();
        lore.add(ChatColor.GRAY + "Seller: " + data.getString(path + ".seller-name", "Unknown"));
        lore.add(ChatColor.GOLD + "Price each: " + formatAmount(data.getLong(path + ".price")));
        lore.add(ChatColor.GRAY + "Available: " + listed.getAmount());
        ItemMeta displayMeta = display.getItemMeta();
        displayMeta.setLore(lore);
        display.setItemMeta(displayMeta);
        inventory.setItem(13, display);
        inventory.setItem(11, menuItem(Material.REDSTONE,
                ChatColor.RED + "Barter: offer one total price"));
        inventory.setItem(15, menuItem(Material.EMERALD,
                ChatColor.GREEN + "Buy entire stack"));
        inventory.setItem(22, menuItem(Material.ARROW, ChatColor.YELLOW + "Back to shop"));
        player.openInventory(inventory);
    }

    private void openOffers(Player player) {
        OffersHolder holder = new OffersHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, ChatColor.DARK_PURPLE + "Your Offers");
        holder.inventory = inventory;
        List<String> offers = getOfferIdsForPlayer(player.getUniqueId());
        int slot = 0;
        for (String id : offers) {
            String path = offerPath(id);
            if (slot >= LISTING_SLOTS) {
                break;
            }
            String listingId = data.getString(path + ".listing");
            ItemStack item = data.getItemStack(listingPath(listingId) + ".item");
            if (item == null || item.getType() == Material.AIR) {
                item = new ItemStack(Material.PAPER);
            } else {
                item = item.clone();
            }
            List<String> lore = item.hasItemMeta() && item.getItemMeta().hasLore()
                    ? new ArrayList<String>(item.getItemMeta().getLore()) : new ArrayList<String>();
            boolean outgoing = player.getUniqueId().toString().equals(data.getString(path + ".from"));
            lore.add(ChatColor.GRAY + (outgoing ? "Sent to: " : "From: ")
                    + data.getString(path + (outgoing ? ".to-name" : ".from-name"), "Unknown"));
            lore.add(ChatColor.GOLD + "Total offer: " + formatAmount(data.getLong(path + ".amount")));
            lore.add(ChatColor.GRAY + "Whole listing: " + data.getInt(path + ".quantity", 1) + " item(s)");
            lore.add(ChatColor.YELLOW + (outgoing
                    ? "Click to cancel and get your funds back"
                    : "Click to accept, decline, or counter"));
            ItemMeta meta = item.getItemMeta();
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot++, item);
        }
        inventory.setItem(49, menuItem(Material.BOOK, ChatColor.YELLOW + "Incoming and sent offers"));
        player.openInventory(inventory);
    }

    private void openOffer(Player player, String offerId) {
        String path = offerPath(offerId);
        if (!"pending".equals(data.getString(path + ".status"))
                || !player.getUniqueId().toString().equals(data.getString(path + ".to"))
                && !player.getUniqueId().toString().equals(data.getString(path + ".from"))) {
            player.sendMessage(ChatColor.RED + "That offer is no longer available.");
            openOffers(player);
            return;
        }

        String listingId = data.getString(path + ".listing");
        ItemStack item = data.getItemStack(listingPath(listingId) + ".item");
        if (item == null || item.getType() == Material.AIR) {
            item = new ItemStack(Material.PAPER);
        } else {
            item = item.clone();
        }
        ItemMeta itemMeta = item.getItemMeta();
        List<String> itemLore = item.hasItemMeta() && itemMeta.hasLore()
                ? new ArrayList<String>(itemMeta.getLore()) : new ArrayList<String>();
        boolean outgoing = player.getUniqueId().toString().equals(data.getString(path + ".from"));
        itemLore.add(ChatColor.GRAY + (outgoing ? "Sent to: " : "From: ")
                + data.getString(path + (outgoing ? ".to-name" : ".from-name"), "Unknown"));
        itemLore.add(ChatColor.GOLD + "Total offer: " + formatAmount(data.getLong(path + ".amount")));
        itemLore.add(ChatColor.GRAY + "Whole listing: " + data.getInt(path + ".quantity", 1) + " item(s)");
        itemMeta.setLore(itemLore);
        item.setItemMeta(itemMeta);
        OfferHolder holder = new OfferHolder(offerId);
        Inventory inventory = Bukkit.createInventory(holder, 27, ChatColor.DARK_PURPLE + "Offer Details");
        holder.inventory = inventory;
        inventory.setItem(13, item);
        if (outgoing) {
            String cancelName = data.getBoolean(path + ".reserved")
                    ? "Cancel offer and refund funds" : "Cancel counteroffer";
            inventory.setItem(12, menuItem(Material.REDSTONE, ChatColor.RED + cancelName));
        } else {
            inventory.setItem(10, menuItem(Material.EMERALD, ChatColor.GREEN + "Accept offer"));
            inventory.setItem(12, menuItem(Material.REDSTONE, ChatColor.RED + "Decline offer"));
            inventory.setItem(14, menuItem(Material.GOLD_INGOT, ChatColor.YELLOW + "Counteroffer"));
        }
        inventory.setItem(22, menuItem(Material.ARROW, ChatColor.YELLOW + "Back to offers"));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof ShopHolder) && !(holder instanceof ListingHolder)
                && !(holder instanceof OffersHolder) && !(holder instanceof OfferHolder)
                && !(holder instanceof MyListingsHolder) && !(holder instanceof EarnHolder)) {
            return;
        }
        if (holder instanceof EarnHolder) {
            handleEarnClick(event, top, (EarnHolder) holder);
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player) || event.getRawSlot() < 0
                || event.getRawSlot() >= top.getSize()) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        if (holder instanceof ShopHolder) {
            ShopHolder shop = (ShopHolder) holder;
            if (slot < LISTING_SLOTS) {
                List<String> ids = getListingIds();
                int listingIndex = shop.page * LISTING_SLOTS + slot;
                if (listingIndex < ids.size()) {
                    openListing(player, ids.get(listingIndex), shop.page);
                }
            } else if (slot == 45 && shop.page > 0) {
                openShop(player, shop.page - 1);
            } else if (slot == 47) {
                openMyListings(player);
            } else if (slot == 53) {
                openShop(player, shop.page + 1);
            }
        } else if (holder instanceof MyListingsHolder) {
            if (!((MyListingsHolder) holder).playerId.equals(player.getUniqueId())) {
                return;
            }
            if (slot < LISTING_SLOTS) {
                List<String> ids = getListingIdsForSeller(player.getUniqueId());
                if (slot < ids.size()) {
                    withdrawListing(player, ids.get(slot));
                }
            } else if (slot == 49) {
                openShop(player, 0);
            }
        } else if (holder instanceof ListingHolder) {
            ListingHolder listing = (ListingHolder) holder;
            if (slot == 11) {
                String path = listingPath(listing.listingId);
                if (!listingAvailable(listing.listingId)) {
                    player.sendMessage(ChatColor.RED + "That listing is no longer available.");
                    openShop(player, listing.page);
                    return;
                }
                String sellerId = data.getString(path + ".seller");
                if (player.getUniqueId().toString().equals(sellerId)) {
                    player.sendMessage(ChatColor.RED + "You cannot make an offer on your own listing.");
                    return;
                }
                pendingInputs.put(player.getUniqueId(), PendingInput.offer(listing.listingId));
                player.closeInventory();
                player.sendMessage(ChatColor.YELLOW
                        + "Enter one total price for all items in the listing, or type cancel.");
            } else if (slot == 15) {
                buyListing(player, listing.listingId);
            } else if (slot == 22) {
                openShop(player, listing.page);
            }
        } else if (holder instanceof OffersHolder) {
            if (slot < LISTING_SLOTS) {
                List<String> ids = getOfferIdsForPlayer(player.getUniqueId());
                if (slot < ids.size()) {
                    openOffer(player, ids.get(slot));
                }
            }
        } else if (holder instanceof OfferHolder) {
            OfferHolder offer = (OfferHolder) holder;
            String offerPath = offerPath(offer.offerId);
            boolean incoming = player.getUniqueId().toString().equals(data.getString(offerPath + ".to"));
            if (incoming && slot == 10) {
                acceptOffer(player, offer.offerId);
            } else if (slot == 12) {
                if (incoming) {
                    updateOfferStatus(player, offer.offerId, "declined");
                } else {
                    cancelOffer(player, offer.offerId);
                }
            } else if (incoming && slot == 14) {
                String path = offerPath(offer.offerId);
                if (!"pending".equals(data.getString(path + ".status"))
                        || !player.getUniqueId().toString().equals(data.getString(path + ".to"))) {
                    player.sendMessage(ChatColor.RED + "That offer is no longer available.");
                    openOffers(player);
                    return;
                }
                pendingInputs.put(player.getUniqueId(), PendingInput.counter(offer.offerId));
                player.closeInventory();
                player.sendMessage(ChatColor.YELLOW
                        + "Enter one total counteroffer for all items in the listing, or type cancel.");
            } else if (slot == 22) {
                openOffers(player);
            }
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        updateSoulboundOwnerLore(event.getInventory());
        if (event.getPlayer() instanceof Player) {
            hideCommandSidebar((Player) event.getPlayer());
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        final Player player = (Player) event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() instanceof Player) {
                    showCommandSidebar(player);
                }
            }
        }, 1L);
    }

    private void handleEarnClick(InventoryClickEvent event, Inventory top, EarnHolder holder) {
        event.setCancelled(false);
        if (!(event.getWhoClicked() instanceof Player)
                || !holder.playerId.equals(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }
        if (rawSlot < LISTING_SLOTS) {
            return;
        }

        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        if (rawSlot == 49) {
            sellEarnItems(player, top);
        } else if (rawSlot == 53) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onEarnInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EarnHolder)) {
            return;
        }
        EarnHolder holder = (EarnHolder) top.getHolder();
        if (!holder.playerId.equals(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= LISTING_SLOTS) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onEarnInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof EarnHolder)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        EarnHolder holder = (EarnHolder) inventory.getHolder();
        if (!holder.playerId.equals(player.getUniqueId())) {
            return;
        }
        for (int slot = 0; slot < LISTING_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            inventory.setItem(slot, null);
        }
    }

    private void sellEarnItems(Player player, Inventory inventory) {
        if (denyMarketActionInCombat(player)) {
            return;
        }
        long total = 0;
        int totalItems = 0;
        boolean hasSoulboundItems = false;
        for (int slot = 0; slot < LISTING_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            if (isSoulbound(item)) {
                hasSoulboundItems = true;
                continue;
            }
            long unitPrice = getEarnPrice(item);
            if (unitPrice == 0) {
                continue;
            }
            if (unitPrice > (MAX_PRICE - total) / item.getAmount()) {
                player.sendMessage(ChatColor.RED + "That sale is too large to process. Remove some items and try again.");
                return;
            }
            long stackValue = unitPrice * item.getAmount();
            total += stackValue;
            totalItems += item.getAmount();
        }
        if (totalItems == 0) {
            player.sendMessage(ChatColor.RED + (hasSoulboundItems
                    ? "Soulbound items cannot be sold. Unsold items remain."
                    : "There are no items with a configured sell price. Unsold items remain."));
            return;
        }

        long balance = getBalance(player.getUniqueId());
        long reserved = getReservedBalance(player.getUniqueId());
        if (balance > MAX_PRICE - reserved || total > MAX_PRICE - balance - reserved) {
            player.sendMessage(ChatColor.RED + "That sale would exceed the maximum balance. Remove some items and try again.");
            return;
        }

        for (int slot = 0; slot < LISTING_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() != Material.AIR
                    && !isSoulbound(item)
                    && getEarnPrice(item) > 0) {
                inventory.setItem(slot, null);
            }
        }
        data.set("balances." + player.getUniqueId(), balance + total);
        saveData();
        updateBalanceBar(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "Sold " + totalItems + " item(s) to the server for "
                + formatAmount(total) + ". Items without a configured price remain in the menu."
                + (hasSoulboundItems ? " Soulbound items cannot be sold." : ""));
    }

    private boolean handleSoulboundCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shop.soulbound")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can enchant items.");
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /soulbound");
            return true;
        }

        Player player = (Player) sender;
        Map<Integer, ItemStack> leftovers =
                player.getInventory().addItem(createSoulboundBook());
        for (ItemStack item : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        player.sendMessage(ChatColor.GREEN + "You received a Soulbound enchantment book.");
        return true;
    }

    private ItemStack createSoulboundBook() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Soulbound Book");
        List<String> lore = new ArrayList<String>();
        lore.add(SOULBOUND_BOOK_LORE);
        lore.add(ChatColor.GRAY + "Anvil: item left, book right; take result for 1 level.");
        meta.setLore(lore);
        book.setItemMeta(meta);
        return book;
    }

    private boolean isSoulboundBook(ItemStack item) {
        return item != null && item.getType() == Material.ENCHANTED_BOOK
                && item.hasItemMeta() && item.getItemMeta().hasLore()
                && item.getItemMeta().getLore().contains(SOULBOUND_BOOK_LORE);
    }

    private boolean isSoulbound(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) {
            return false;
        }
        for (String line : item.getItemMeta().getLore()) {
            if (isSoulboundLoreLine(line)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSoulboundLoreLine(String line) {
        String plainLine = ChatColor.stripColor(line);
        if (plainLine == null) {
            return false;
        }
        plainLine = plainLine.trim();
        return "Soulbound".equalsIgnoreCase(plainLine)
                || plainLine.regionMatches(true, 0, "Soulbound to ", 0, 13)
                        && plainLine.length() > 13;
    }

    private boolean isSoulboundLegacyEquipment(ItemStack item) {
        if (item == null) {
            return false;
        }
        String materialName = item.getType().name();
        return item.getType() == Material.BOW
                || materialName.endsWith("_SWORD")
                || materialName.endsWith("_AXE")
                || materialName.endsWith("_HELMET")
                || materialName.endsWith("_CHESTPLATE")
                || materialName.endsWith("_LEGGINGS")
                || materialName.endsWith("_BOOTS")
                || item.getType() == Material.ELYTRA
                || item.getType() == Material.SHIELD;
    }

    private UUID getSoulboundOwner(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) {
            return null;
        }
        for (String line : item.getItemMeta().getLore()) {
            if (line != null && line.startsWith(SOULBOUND_OWNER_PREFIX)) {
                try {
                    return UUID.fromString(line.substring(SOULBOUND_OWNER_PREFIX.length()));
                } catch (IllegalArgumentException exception) {
                    return null;
                }
            }
        }
        return isSoulbound(item) && isSoulboundLegacyEquipment(item)
                ? LEGACY_SOULBOUND_OWNER : null;
    }

    private String getSoulboundOwnerName(UUID ownerId) {
        if (LEGACY_SOULBOUND_OWNER.equals(ownerId)) {
            return "thedeaths";
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerId);
        String name = owner.getName();
        return name == null ? ownerId.toString() : name;
    }

    private void updateSoulboundOwnerLore(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!isSoulbound(item)) {
                continue;
            }
            UUID ownerId = getSoulboundOwner(item);
            if (ownerId == null) {
                continue;
            }

            ItemMeta meta = item.getItemMeta();
            List<String> oldLore = meta.hasLore()
                    ? new ArrayList<String>(meta.getLore()) : new ArrayList<String>();
            List<String> newLore = new ArrayList<String>();
            boolean ownerIdAdded = false;
            boolean ownerNameAdded = false;
            for (String line : oldLore) {
                if (line != null && line.startsWith(SOULBOUND_OWNER_PREFIX)) {
                    if (!ownerIdAdded) {
                        newLore.add(SOULBOUND_OWNER_PREFIX + ownerId);
                        ownerIdAdded = true;
                    }
                } else if (isSoulboundLoreLine(line)) {
                    if (!ownerNameAdded) {
                        newLore.add(SOULBOUND_OWNER_LORE_PREFIX
                                + getSoulboundOwnerName(ownerId));
                        ownerNameAdded = true;
                    }
                } else {
                    newLore.add(line);
                }
            }
            if (!ownerIdAdded) {
                newLore.add(SOULBOUND_OWNER_PREFIX + ownerId);
            }
            if (!ownerNameAdded) {
                newLore.add(SOULBOUND_OWNER_LORE_PREFIX
                        + getSoulboundOwnerName(ownerId));
            }
            if (!newLore.equals(oldLore)) {
                meta.setLore(newLore);
                item.setItemMeta(meta);
                inventory.setItem(slot, item);
            }
        }
    }

    private boolean isSoulboundOwnedByAnotherPlayer(ItemStack item, UUID playerId) {
        if (!isSoulbound(item)) {
            return false;
        }
        UUID owner = getSoulboundOwner(item);
        return owner != null && !owner.equals(playerId);
    }

    private boolean inventoryContainsSoulboundOwnedByAnotherPlayer(
            Inventory inventory, UUID playerId) {
        for (ItemStack item : inventory.getContents()) {
            if (isSoulboundOwnedByAnotherPlayer(item, playerId)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulboundEnchant(EnchantItemEvent event) {
        if (event.whichButton() != 2 || event.getItem().getType() != Material.BOOK
                || ThreadLocalRandom.current().nextInt(100) >= 10) {
            return;
        }

        ItemStack item = event.getItem();
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta.hasLore()
                ? new ArrayList<String>(meta.getLore()) : new ArrayList<String>();
        lore.add(SOULBOUND_BOOK_LORE);
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Soulbound Book");
        meta.setLore(lore);
        item.setItemMeta(meta);
        event.getEnchanter().sendMessage(ChatColor.LIGHT_PURPLE
                + "This book has a rare Soulbound enchantment.");
    }

    @EventHandler
    public void onPrepareSoulboundAnvil(PrepareAnvilEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL) {
            return;
        }
        ItemStack target = event.getInventory().getItem(0);
        ItemStack book = event.getInventory().getItem(1);
        if (target == null || target.getType() == Material.AIR || !isSoulboundBook(book)) {
            return;
        }
        if (isBackpackItem(target)) {
            event.setResult(null);
            event.getInventory().setRepairCost(0);
            return;
        }
        if (isSoulbound(target)) {
            return;
        }
        if (!(event.getView().getPlayer() instanceof Player)) {
            return;
        }
        Player enchanter = (Player) event.getView().getPlayer();

        ItemStack result = event.getResult();
        result = result == null || result.getType() == Material.AIR
                ? target.clone() : result.clone();
        ItemMeta meta = result.getItemMeta();
        List<String> lore = meta.hasLore()
                ? new ArrayList<String>(meta.getLore()) : new ArrayList<String>();
        lore.add(SOULBOUND_OWNER_PREFIX + enchanter.getUniqueId());
        lore.add(SOULBOUND_OWNER_LORE_PREFIX + enchanter.getName());
        meta.setLore(lore);
        result.setItemMeta(meta);
        event.setResult(result);
        event.getInventory().setRepairCost(1);
        scheduleSoulboundAnvilCost(enchanter);
    }

    private void scheduleSoulboundAnvilCost(final Player player) {
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                Inventory top = player.getOpenInventory().getTopInventory();
                if (top.getType() != InventoryType.ANVIL
                        || !isSoulboundBook(top.getItem(1))) {
                    return;
                }
                ItemStack target = top.getItem(0);
                if (target == null || isBackpackItem(target) || isSoulbound(target)) {
                    return;
                }
                ((AnvilInventory) top).setRepairCost(1);
                player.updateInventory();
            }
        });
    }

    private boolean isBackpackItem(ItemStack item) {
        if (item == null || item.getType() != Material.LEATHER_CHESTPLATE
                || !item.hasItemMeta() || !item.getItemMeta().hasLore()) {
            return false;
        }
        for (String line : item.getItemMeta().getLore()) {
            if (line != null && line.startsWith(BACKPACK_LORE_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulboundInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        UUID playerId = player.getUniqueId();
        boolean unauthorized = isSoulboundOwnedByAnotherPlayer(event.getCurrentItem(), playerId)
                || isSoulboundOwnedByAnotherPlayer(event.getCursor(), playerId);
        if (!unauthorized && event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            unauthorized = inventoryContainsSoulboundOwnedByAnotherPlayer(
                    event.getView().getTopInventory(), playerId);
        }
        if (unauthorized) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "That Soulbound item belongs to another player.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulboundInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            Player player = (Player) event.getWhoClicked();
            if (isSoulboundOwnedByAnotherPlayer(
                    event.getOldCursor(), player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage(
                        ChatColor.RED + "That Soulbound item belongs to another player.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulboundInventoryMove(InventoryMoveItemEvent event) {
        if (isSoulbound(event.getItem()) && getSoulboundOwner(event.getItem()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulboundChestBreak(BlockBreakEvent event) {
        BlockState state = event.getBlock().getState();
        if (!(state instanceof Chest)) {
            return;
        }
        recoverSoulboundChestItems(((Chest) state).getBlockInventory(),
                event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulboundChestExplosion(BlockExplodeEvent event) {
        for (org.bukkit.block.Block block : event.blockList()) {
            BlockState state = block.getState();
            if (state instanceof Chest) {
                recoverSoulboundChestItems(((Chest) state).getBlockInventory(), null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulboundChestEntityExplosion(EntityExplodeEvent event) {
        for (org.bukkit.block.Block block : event.blockList()) {
            BlockState state = block.getState();
            if (state instanceof Chest) {
                recoverSoulboundChestItems(((Chest) state).getBlockInventory(), null);
            }
        }
    }

    private void recoverSoulboundChestItems(Inventory inventory, UUID breakerId) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            UUID ownerId = getSoulboundOwner(item);
            if (!isSoulbound(item)) {
                continue;
            }

            inventory.setItem(slot, null);
            if (ownerId == null) {
                ownerId = breakerId;
            }
            if (ownerId == null) {
                continue;
            }
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null) {
                saveSoulboundChestRecovery(ownerId, item);
            } else {
                Map<Integer, ItemStack> leftovers =
                        deliverSoulboundChestItem(owner, item);
                if (leftovers.isEmpty()) {
                    owner.sendMessage(ChatColor.GREEN
                            + "Your Soulbound item was returned because its chest was broken.");
                } else {
                    for (ItemStack leftover : leftovers.values()) {
                        saveSoulboundChestRecovery(ownerId, leftover);
                    }
                    owner.sendMessage(ChatColor.YELLOW
                            + "Some Soulbound items are stored until you make room in your inventory.");
                }
            }
        }
    }

    private Map<Integer, ItemStack> deliverSoulboundChestItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
        player.updateInventory();
        return leftovers;
    }

    private void saveSoulboundChestRecovery(UUID playerId, ItemStack item) {
        String path = "soulbound-chest-recovery." + playerId;
        List<ItemStack> recovery = new ArrayList<ItemStack>();
        List<?> stored = data.getList(path);
        if (stored != null) {
            for (Object value : stored) {
                if (value instanceof ItemStack) {
                    recovery.add(((ItemStack) value).clone());
                } else {
                    getLogger().warning("Ignoring invalid Soulbound chest recovery item for "
                            + playerId + ".");
                }
            }
        }
        recovery.add(item.clone());
        data.set(path, recovery);
        saveData();
    }

    private void restoreSoulboundChestItems(Player player) {
        String path = "soulbound-chest-recovery." + player.getUniqueId();
        List<?> stored = data.getList(path);
        if (stored == null || stored.isEmpty()) {
            return;
        }

        List<ItemStack> recovery = new ArrayList<ItemStack>();
        for (Object value : stored) {
            if (value instanceof ItemStack) {
                recovery.add(((ItemStack) value).clone());
            } else {
                getLogger().warning("Ignoring invalid Soulbound chest recovery item for "
                        + player.getUniqueId() + ".");
            }
        }
        data.set(path, null);
        saveData();
        List<ItemStack> leftovers = new ArrayList<ItemStack>();
        for (ItemStack item : recovery) {
            for (ItemStack leftover : deliverSoulboundChestItem(player, item).values()) {
                leftovers.add(leftover.clone());
            }
        }
        if (!leftovers.isEmpty()) {
            for (ItemStack leftover : leftovers) {
                saveSoulboundChestRecovery(player.getUniqueId(), leftover);
            }
            player.sendMessage(ChatColor.YELLOW
                    + "Some Soulbound chest items are stored until you make room in your inventory.");
            return;
        }
        if (!recovery.isEmpty()) {
            player.sendMessage(ChatColor.GREEN
                    + "Your Soulbound items from broken chests have been returned.");
        }
    }

    @EventHandler
    public void onSoulboundChestLoot(ChunkPopulateEvent event) {
        for (BlockState state : event.getChunk().getTileEntities()) {
            if (!(state instanceof Chest) || ThreadLocalRandom.current().nextDouble() >= 0.002) {
                continue;
            }
            Chest chest = (Chest) state;
            Map<Integer, ItemStack> leftovers =
                    chest.getBlockInventory().addItem(createSoulboundBook());
            for (ItemStack item : leftovers.values()) {
                chest.getBlock().getWorld().dropItemNaturally(chest.getLocation(), item);
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        final UUID playerId = event.getPlayer().getUniqueId();
        final PendingInput input = pendingInputs.remove(playerId);
        if (input == null) {
            return;
        }
        event.setCancelled(true);
        final String text = event.getMessage().trim();
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null) {
                    return;
                }
                if ("cancel".equalsIgnoreCase(text)) {
                    player.sendMessage(ChatColor.YELLOW + "Cancelled.");
                    return;
                }
                if (input.kind == InputKind.LISTING) {
                    ItemStack held = player.getInventory().getItemInMainHand();
                    if (held == null || held.getType() == Material.AIR) {
                        player.sendMessage(ChatColor.RED + "Hold the item in your main hand and try /sell again.");
                        return;
                    }
                    long price = parseAmount(text);
                    if (price <= 0 || price > MAX_PRICE / held.getAmount()) {
                        player.sendMessage(ChatColor.RED + "Enter a valid price per item for the whole stack.");
                        return;
                    }
                    createListing(player, held, price);
                } else if (input.kind == InputKind.OFFER) {
                    long totalOffer = parseAmount(text);
                    if (totalOffer <= 0) {
                        player.sendMessage(ChatColor.RED + "Enter one total price greater than 0, or type cancel.");
                        return;
                    }
                    createOffer(player, input.reference, totalOffer);
                } else {
                    long amount = parseAmount(text);
                    if (amount <= 0) {
                        player.sendMessage(ChatColor.RED + "Enter a total counteroffer greater than 0, or type cancel.");
                        return;
                    }
                    counterOffer(player, input.reference, amount);
                }
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        showBalance(player);
        showCombatBar(player);
        showCommandSidebar(player);
        restoreSoulboundDeathItems(player);
        restoreSoulboundItems(player);
        restoreSoulboundChestItems(player);
        updateSoulboundOwnerLore(player.getInventory());
        restoreCombatLogoutRespawn(player);
        restoreLogJail(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }

        Player player = event.getEntity();
        List<ItemStack> capturedItems = new ArrayList<ItemStack>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isSoulbound(item)) {
                capturedItems.add(item.clone());
            }
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (isSoulbound(item)) {
                capturedItems.add(item.clone());
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isSoulbound(offHand)) {
            capturedItems.add(offHand.clone());
        }

        ItemStack cursor = player.getItemOnCursor();
        if (isSoulbound(cursor)) {
            capturedItems.add(cursor.clone());
            saveSoulboundRecovery(player.getUniqueId(),
                    Collections.singletonList(cursor.clone()));
        } else if (cursor != null && cursor.getType() != Material.AIR) {
            event.getDrops().add(cursor.clone());
        }

        List<ItemStack> ordinaryDrops = new ArrayList<ItemStack>();
        Iterator<ItemStack> drops = event.getDrops().iterator();
        while (drops.hasNext()) {
            ItemStack drop = drops.next();
            if (isSoulbound(drop)) {
                int capturedAmount = getCapturedSoulboundAmount(capturedItems, drop);
                if (capturedAmount < drop.getAmount()) {
                    ItemStack extra = drop.clone();
                    extra.setAmount(drop.getAmount() - capturedAmount);
                    List<ItemStack> recoveryItems = new ArrayList<ItemStack>();
                    recoveryItems.add(extra);
                    saveSoulboundRecovery(player.getUniqueId(), recoveryItems);
                    capturedItems.add(extra);
                }
            } else {
                ordinaryDrops.add(drop.clone());
            }
            drops.remove();
        }

        event.setKeepInventory(true);
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (!isSoulbound(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int slot = 0; slot < armor.length; slot++) {
            if (!isSoulbound(armor[slot])) {
                armor[slot] = null;
            }
        }
        player.getInventory().setArmorContents(armor);
        if (!isSoulbound(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        }
        player.setItemOnCursor(new ItemStack(Material.AIR));

        for (ItemStack item : ordinaryDrops) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        saveData();
    }

    private int getCapturedSoulboundAmount(List<ItemStack> capturedItems, ItemStack item) {
        int amount = 0;
        for (ItemStack captured : capturedItems) {
            if (captured.isSimilar(item)) {
                amount += captured.getAmount();
            }
        }
        return amount;
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        restoreSoulboundDeathItems(player);
        restoreSoulboundItems(player);
        player.updateInventory();
    }

    private void restoreSoulboundDeathItems(Player player) {
        String root = "soulbound-slot-recovery." + player.getUniqueId();
        if (!data.isConfigurationSection(root)) {
            return;
        }

        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            restoreSoulboundDeathItem(player, root + ".storage." + slot,
                    player.getInventory().getItem(slot), slot, null, -1, false);
        }

        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int slot = 0; slot < armor.length; slot++) {
            restoreSoulboundDeathItem(player, root + ".armor." + slot,
                    armor[slot], -1, armor, slot, false);
        }
        player.getInventory().setArmorContents(armor);

        restoreSoulboundDeathItem(player, root + ".offhand",
                player.getInventory().getItemInOffHand(), -1, null, -1, true);
        restoreSoulboundDeathItem(player, root + ".cursor", null, -1, null, -1, false);

        if (!hasSoulboundDeathRecovery(player.getUniqueId())) {
            data.set(root, null);
        } else {
            player.sendMessage(ChatColor.YELLOW
                    + "Some Soulbound items are stored until you make room in your inventory.");
        }
        saveData();
    }

    private boolean restoreSoulboundDeathItem(Player player, String path, ItemStack current,
            int storageSlot, ItemStack[] armor, int armorSlot, boolean offHand) {
        Object value = data.get(path);
        if (value == null) {
            return false;
        }
        if (!(value instanceof ItemStack)) {
            getLogger().warning("Ignoring invalid Soulbound recovery item at " + path + ".");
            data.set(path, null);
            return false;
        }

        ItemStack saved = ((ItemStack) value).clone();
        if (current == null || current.getType() == Material.AIR) {
            if (storageSlot >= 0) {
                player.getInventory().setItem(storageSlot, saved);
            } else if (armor != null) {
                armor[armorSlot] = saved;
            } else if (offHand) {
                player.getInventory().setItemInOffHand(saved);
            } else {
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(saved);
                if (!leftovers.isEmpty()) {
                    data.set(path, leftovers.values().iterator().next());
                    return false;
                }
            }
            data.set(path, null);
            return true;
        }
        if (current.isSimilar(saved) && current.getAmount() == saved.getAmount()) {
            data.set(path, null);
            return true;
        }

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(saved);
        if (leftovers.isEmpty()) {
            data.set(path, null);
            return true;
        }
        int remaining = 0;
        ItemStack remainder = null;
        for (ItemStack item : leftovers.values()) {
            remaining += item.getAmount();
            remainder = item;
        }
        remainder = remainder.clone();
        remainder.setAmount(remaining);
        data.set(path, remainder);
        return false;
    }

    private boolean hasSoulboundDeathRecovery(UUID playerId) {
        String root = "soulbound-slot-recovery." + playerId;
        for (int slot = 0; slot < 36; slot++) {
            if (data.get(root + ".storage." + slot) != null) {
                return true;
            }
        }
        for (int slot = 0; slot < 4; slot++) {
            if (data.get(root + ".armor." + slot) != null) {
                return true;
            }
        }
        return data.get(root + ".offhand") != null || data.get(root + ".cursor") != null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulboundDrop(PlayerDropItemEvent event) {
        if (isSoulbound(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulboundItemSpawn(ItemSpawnEvent event) {
        if (isSoulbound(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        String command = event.getCommand().trim().toLowerCase(Locale.ROOT);
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        int argumentStart = command.indexOf(' ');
        if (argumentStart >= 0) {
            command = command.substring(0, argumentStart);
        }
        if ("stop".equals(command) || "restart".equals(command)) {
            shuttingDown = true;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        cancelTeleportWarmup(playerId, teleportWarmups.get(playerId));
        pendingTeleportRequests.remove(playerId);
        for (Map.Entry<UUID, TeleportRequest> entry : pendingTeleportRequests.entrySet()) {
            if (entry.getValue().requesterId.equals(playerId)) {
                pendingTeleportRequests.remove(entry.getKey(), entry.getValue());
            }
        }
        if (!shuttingDown && getCombatRemaining(playerId) > 0) {
            Location logoutLocation = player.getLocation();
            saveCombatLogoutRespawn(player);
            dropCombatLogoutItems(player, logoutLocation);
            dropCombatLogoutExperience(player, logoutLocation);
            combatExpiry.remove(playerId);
            saveData();
            Bukkit.broadcastMessage(ChatColor.RED + player.getName()
                    + " died from combat-logging.");
        }
        BossBar bar = balanceBars.remove(playerId);
        if (bar != null) {
            bar.removePlayer(player);
        }
        BossBar combatBar = combatBars.remove(playerId);
        if (combatBar != null) {
            combatBar.removePlayer(player);
        }
        restoreOriginalScoreboard(player);
        pendingInputs.remove(playerId);
    }

    private void saveCombatLogoutRespawn(Player player) {
        Location respawn = player.getBedSpawnLocation();
        if (respawn == null) {
            respawn = player.getWorld().getSpawnLocation();
        }

        String path = "combat-logout-respawns." + player.getUniqueId();
        data.set(path + ".world", respawn.getWorld().getName());
        data.set(path + ".x", respawn.getX());
        data.set(path + ".y", respawn.getY());
        data.set(path + ".z", respawn.getZ());
        data.set(path + ".yaw", respawn.getYaw());
        data.set(path + ".pitch", respawn.getPitch());
    }

    private void dropCombatLogoutItems(Player player, Location location) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        ItemStack[] armor = player.getInventory().getArmorContents();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        ItemStack cursor = player.getItemOnCursor();

        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (isSoulbound(item)) {
                continue;
            }
            dropItemAt(location, item);
            player.getInventory().setItem(slot, null);
        }
        for (int slot = 0; slot < armor.length; slot++) {
            ItemStack item = armor[slot];
            if (isSoulbound(item)) {
                continue;
            }
            dropItemAt(location, item);
            armor[slot] = null;
        }
        player.getInventory().setArmorContents(armor);
        if (!isSoulbound(offHand)) {
            dropItemAt(location, offHand);
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        }
        if (isSoulbound(cursor)) {
            Map<Integer, ItemStack> leftovers =
                    player.getInventory().addItem(cursor.clone());
            saveSoulboundRecovery(player.getUniqueId(),
                    new ArrayList<ItemStack>(leftovers.values()));
            player.setItemOnCursor(new ItemStack(Material.AIR));
        } else {
            dropItemAt(location, cursor);
            player.setItemOnCursor(new ItemStack(Material.AIR));
        }
    }

    private void saveSoulboundRecovery(UUID playerId, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        String path = "soulbound-recovery." + playerId;
        List<ItemStack> recovery = new ArrayList<ItemStack>();
        List<?> stored = data.getList(path);
        if (stored != null) {
            for (Object value : stored) {
                if (value instanceof ItemStack) {
                    recovery.add(((ItemStack) value).clone());
                } else {
                    getLogger().warning("Ignoring invalid Soulbound recovery item for " + playerId + ".");
                }
            }
        }
        for (ItemStack item : items) {
            if (isSoulbound(item)) {
                recovery.add(item.clone());
            }
        }
        if (!recovery.isEmpty()) {
            data.set(path, recovery);
            saveData();
        }
    }

    private void restoreSoulboundItems(Player player) {
        String path = "soulbound-recovery." + player.getUniqueId();
        List<?> stored = data.getList(path);
        if (stored == null || stored.isEmpty()) {
            return;
        }

        List<ItemStack> recovery = new ArrayList<ItemStack>();
        for (Object value : stored) {
            if (value instanceof ItemStack) {
                recovery.add(((ItemStack) value).clone());
            } else {
                getLogger().warning("Ignoring invalid Soulbound recovery item for "
                        + player.getUniqueId() + ".");
            }
        }
        data.set(path, null);
        saveData();

        List<ItemStack> leftovers = new ArrayList<ItemStack>();
        for (ItemStack item : recovery) {
            leftovers.addAll(player.getInventory().addItem(item).values());
        }
        if (!leftovers.isEmpty()) {
            saveSoulboundRecovery(player.getUniqueId(), leftovers);
            player.sendMessage(ChatColor.YELLOW
                    + "Some Soulbound items are stored until you make room in your inventory.");
        } else if (!recovery.isEmpty()) {
            player.sendMessage(ChatColor.GREEN + "Your Soulbound items have been returned.");
        }
    }

    private void dropCombatLogoutExperience(Player player, Location location) {
        int experience = getCurrentExperience(player);
        if (experience > 0) {
            ExperienceOrb orb = location.getWorld().spawn(location, ExperienceOrb.class);
            orb.setExperience(experience);
        }
        clearPlayerExperience(player);
    }

    private int getCurrentExperience(Player player) {
        int level = player.getLevel();
        long experienceAtLevel;
        if (level <= 15) {
            experienceAtLevel = level * level + 6 * level;
        } else if (level <= 31) {
            experienceAtLevel = (long) (2.5 * level * level - 40.5 * level + 360);
        } else {
            experienceAtLevel = (long) (4.5 * level * level - 162.5 * level + 2220);
        }

        int experienceToNextLevel;
        if (level <= 15) {
            experienceToNextLevel = 2 * level + 7;
        } else if (level <= 30) {
            experienceToNextLevel = 5 * level - 38;
        } else {
            experienceToNextLevel = 9 * level - 158;
        }
        float progress = Math.max(0.0F, Math.min(1.0F, player.getExp()));
        long progressExperience = (long) Math.ceil(progress * experienceToNextLevel);
        long levelExperience = Math.max(0L, experienceAtLevel + progressExperience);
        long totalExperience = Math.max(levelExperience, player.getTotalExperience());
        return (int) Math.min(Integer.MAX_VALUE, totalExperience);
    }

    private void clearPlayerExperience(Player player) {
        player.setLevel(0);
        player.setExp(0.0F);
        player.setTotalExperience(0);
    }

    private void dropItemAt(Location location, ItemStack item) {
        if (item != null && item.getType() != Material.AIR) {
            location.getWorld().dropItemNaturally(location, item.clone());
        }
    }

    private void restoreCombatLogoutRespawn(final Player player) {
        final String path = "combat-logout-respawns." + player.getUniqueId();
        if (!data.contains(path + ".world")) {
            return;
        }

        World world = Bukkit.getWorld(data.getString(path + ".world"));
        if (world == null) {
            getLogger().warning("Cannot restore combat logout respawn for "
                    + player.getName() + ": world " + data.getString(path + ".world")
                    + " is not loaded.");
            return;
        }

        final Location respawn = new Location(world,
                data.getDouble(path + ".x"),
                data.getDouble(path + ".y"),
                data.getDouble(path + ".z"),
                (float) data.getDouble(path + ".yaw"),
                (float) data.getDouble(path + ".pitch"));
        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                if (!player.teleport(respawn)) {
                    getLogger().warning("Could not restore combat logout respawn for "
                            + player.getName() + ".");
                    return;
                }
                data.set(path, null);
                saveData();
                clearPlayerExperience(player);
                player.setHealth(player.getMaxHealth());
                player.setFoodLevel(20);
                player.setSaturation(20.0F);
                player.setExhaustion(0.0F);
                Bukkit.getScheduler().runTaskLater(ShopPlugin.this, new Runnable() {
                    @Override
                    public void run() {
                        if (player.isOnline()) {
                            player.sendMessage(ChatColor.RED
                                    + "You died from combat-logging. Your items and XP were dropped, and you were sent to your respawn point.");
                        }
                    }
                }, 200L);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCombatDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player) || event.getFinalDamage() <= 0) {
            return;
        }

        Player victim = (Player) event.getEntity();
        Player attacker = null;
        Entity damager = event.getDamager();
        if (damager instanceof Player) {
            attacker = (Player) damager;
        } else if (damager instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Player) {
                attacker = (Player) shooter;
            }
        }

        long expiry = System.currentTimeMillis() + COMBAT_DURATION_MILLIS;
        tagInCombat(victim, expiry);
        if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId())) {
            tagInCombat(attacker, expiry);
        }
    }

    private void buyListing(Player buyer, String listingId) {
        if (!listingAvailable(listingId)) {
            buyer.sendMessage(ChatColor.RED + "That listing is no longer available.");
            openShop(buyer, 0);
            return;
        }
        String path = listingPath(listingId);
        long price = data.getLong(path + ".price");
        UUID sellerId = UUID.fromString(data.getString(path + ".seller"));
        if (sellerId.equals(buyer.getUniqueId())) {
            buyer.sendMessage(ChatColor.RED + "You cannot buy your own listing.");
            return;
        }
        ItemStack item = data.getItemStack(path + ".item");
        if (item == null || item.getType() == Material.AIR) {
            buyer.sendMessage(ChatColor.RED + "That listing is no longer available.");
            return;
        }
        completeSale(buyer, listingId, price, item.getAmount(), null);
    }

    private void createOffer(Player buyer, String listingId, long totalOffer) {
        if (!listingAvailable(listingId)) {
            buyer.sendMessage(ChatColor.RED + "That listing is no longer available.");
            return;
        }
        String listing = listingPath(listingId);
        ItemStack listedItem = data.getItemStack(listing + ".item");
        if (listedItem == null || listedItem.getType() == Material.AIR) {
            buyer.sendMessage(ChatColor.RED + "That listing is no longer available.");
            return;
        }
        UUID sellerId = UUID.fromString(data.getString(listing + ".seller"));
        if (sellerId.equals(buyer.getUniqueId())) {
            buyer.sendMessage(ChatColor.RED + "You cannot make an offer on your own listing.");
            return;
        }
        long quantity = listedItem.getAmount();
        if (totalOffer <= 0 || totalOffer > MAX_PRICE) {
            buyer.sendMessage(ChatColor.RED + "That total offer is not valid.");
            return;
        }
        long balance = getBalance(buyer.getUniqueId());
        long reserved = getReservedBalance(buyer.getUniqueId());
        if (balance < totalOffer) {
            buyer.sendMessage(ChatColor.RED + "You need " + formatAmount(totalOffer)
                    + " available to make that offer.");
            return;
        }
        if (balance > MAX_PRICE - reserved || totalOffer > MAX_PRICE - balance - reserved) {
            buyer.sendMessage(ChatColor.RED + "That offer would exceed the maximum balance.");
            return;
        }
        String offerId = UUID.randomUUID().toString();
        String path = offerPath(offerId);
        data.set(path + ".listing", listingId);
        data.set(path + ".from", buyer.getUniqueId().toString());
        data.set(path + ".from-name", buyer.getName());
        data.set(path + ".to", sellerId.toString());
        data.set(path + ".to-name", data.getString(listing + ".seller-name", "Unknown"));
        data.set(path + ".payer", buyer.getUniqueId().toString());
        data.set(path + ".amount", totalOffer);
        data.set(path + ".quantity", quantity);
        data.set(path + ".price-mode", "total");
        data.set(path + ".status", "pending");
        data.set(path + ".reserved", true);
        data.set("balances." + buyer.getUniqueId(), balance - totalOffer);
        saveData();
        updateBalanceBar(buyer.getUniqueId());
        buyer.sendMessage(ChatColor.GREEN + "Total offer of " + formatAmount(totalOffer) + " sent for all "
                + quantity + " item(s). The seller can respond with /offers.");
        Player seller = Bukkit.getPlayer(sellerId);
        if (seller != null) {
            seller.sendMessage(ChatColor.YELLOW + buyer.getName() + " made an offer. Check /offers.");
        }
    }

    private void counterOffer(Player counterer, String offerId, long amount) {
        String oldPath = offerPath(offerId);
        if (!"pending".equals(data.getString(oldPath + ".status"))
                || !counterer.getUniqueId().toString().equals(data.getString(oldPath + ".to"))) {
            counterer.sendMessage(ChatColor.RED + "That offer is no longer available.");
            return;
        }
        String listingId = data.getString(oldPath + ".listing");
        if (!listingAvailable(listingId)) {
            refundOffer(offerId, "cancelled");
            saveData();
            counterer.sendMessage(ChatColor.RED + "That listing is no longer available.");
            return;
        }

        long quantity = data.getLong(oldPath + ".quantity", 1);
        if (amount <= 0 || amount > MAX_PRICE || quantity <= 0) {
            counterer.sendMessage(ChatColor.RED + "That total counteroffer is invalid.");
            return;
        }
        String listingPath = listingPath(listingId);
        ItemStack currentListing = data.getItemStack(listingPath + ".item");
        if (currentListing == null || currentListing.getType() == Material.AIR
                || quantity != currentListing.getAmount()) {
            counterer.sendMessage(ChatColor.RED + "The listing changed; start a new offer for its current amount.");
            return;
        }
        UUID sellerId = UUID.fromString(data.getString(listingPath + ".seller"));
        boolean countererIsBuyer = !counterer.getUniqueId().equals(sellerId);
        long countererBalance = 0;
        if (countererIsBuyer) {
            long newTotal = amount;
            countererBalance = getBalance(counterer.getUniqueId());
            long countererReserved = getReservedBalance(counterer.getUniqueId());
            if (countererBalance < newTotal) {
                counterer.sendMessage(ChatColor.RED + "You need " + formatAmount(newTotal)
                        + " available to make that counteroffer.");
                return;
            }
            if (countererBalance > MAX_PRICE - countererReserved
                    || newTotal > MAX_PRICE - countererBalance - countererReserved) {
                counterer.sendMessage(ChatColor.RED + "That counteroffer would exceed the maximum balance.");
                return;
            }
        }
        UUID otherParty = UUID.fromString(data.getString(oldPath + ".from"));
        refundOffer(offerId, "countered");
        String newId = UUID.randomUUID().toString();
        String newPath = offerPath(newId);
        data.set(newPath + ".listing", listingId);
        data.set(newPath + ".from", counterer.getUniqueId().toString());
        data.set(newPath + ".from-name", counterer.getName());
        data.set(newPath + ".to", otherParty.toString());
        data.set(newPath + ".to-name", data.getString(oldPath + ".from-name", "Unknown"));
        String payerId = countererIsBuyer
                ? counterer.getUniqueId().toString()
                : data.getString(oldPath + ".payer", data.getString(oldPath + ".from"));
        data.set(newPath + ".payer", payerId);
        data.set(newPath + ".amount", amount);
        data.set(newPath + ".quantity", quantity);
        data.set(newPath + ".price-mode", "total");
        data.set(newPath + ".status", "pending");
        data.set(newPath + ".reserved", countererIsBuyer);
        if (countererIsBuyer) {
            data.set("balances." + counterer.getUniqueId(), countererBalance - amount);
            updateBalanceBar(counterer.getUniqueId());
        }
        saveData();
        counterer.sendMessage(ChatColor.GREEN + "Total counteroffer of " + formatAmount(amount)
                + " sent for all " + quantity + " item(s).");
        Player recipient = Bukkit.getPlayer(otherParty);
        if (recipient != null) {
            recipient.sendMessage(ChatColor.YELLOW + counterer.getName() + " sent a counteroffer. Check /offers.");
        }
    }

    private void acceptOffer(Player recipient, String offerId) {
        String path = offerPath(offerId);
        if (!"pending".equals(data.getString(path + ".status"))
                || !recipient.getUniqueId().toString().equals(data.getString(path + ".to"))) {
            recipient.sendMessage(ChatColor.RED + "That offer is no longer available.");
            openOffers(recipient);
            return;
        }
        String listingId = data.getString(path + ".listing");
        String payer = data.getString(path + ".payer", data.getString(path + ".from"));
        Player buyer = Bukkit.getPlayer(UUID.fromString(payer));
        if (buyer == null) {
            recipient.sendMessage(ChatColor.RED + "The other player must be online to complete this trade.");
            return;
        }
        if (!listingAvailable(listingId)) {
            refundOffer(offerId, "cancelled");
            saveData();
            recipient.sendMessage(ChatColor.RED + "That listing is no longer available.");
            openOffers(recipient);
            return;
        }
        completeSale(buyer, listingId, data.getLong(path + ".amount"),
                data.getLong(path + ".quantity", 1), offerId);
    }

    private void completeSale(Player buyer, String listingId, long unitPrice, long quantity,
            String acceptedOfferId) {
        String path = listingPath(listingId);
        UUID sellerId = UUID.fromString(data.getString(path + ".seller"));
        if (getCombatRemaining(sellerId) > 0) {
            buyer.sendMessage(ChatColor.RED + "That seller cannot complete a sale while in combat.");
            return;
        }
        ItemStack listedItem = data.getItemStack(path + ".item");
        if (listedItem == null || listedItem.getType() == Material.AIR
                || quantity <= 0 || quantity > listedItem.getAmount()
                || quantity > Integer.MAX_VALUE || unitPrice <= 0
                || unitPrice > MAX_PRICE
                || acceptedOfferId == null && unitPrice > MAX_PRICE / quantity
                || acceptedOfferId != null && quantity != listedItem.getAmount()) {
            buyer.sendMessage(ChatColor.RED + "That listing does not have the requested amount available.");
            return;
        }
        long totalPrice = acceptedOfferId == null ? unitPrice * quantity : unitPrice;
        long buyerBalance = getBalance(buyer.getUniqueId());
        long sellerBalance = getBalance(sellerId);
        long sellerReserved = getReservedBalance(sellerId);
        if (acceptedOfferId == null && buyerBalance < totalPrice) {
            buyer.sendMessage(ChatColor.RED + "You need " + formatAmount(totalPrice) + ", but only have "
                    + formatAmount(buyerBalance) + ".");
            return;
        }
        if (sellerReserved > MAX_PRICE - totalPrice
                || sellerBalance > MAX_PRICE - sellerReserved - totalPrice) {
            buyer.sendMessage(ChatColor.RED + "The seller's balance is at the maximum; this trade cannot complete.");
            return;
        }
        if (acceptedOfferId != null) {
            String offer = offerPath(acceptedOfferId);
            if (!"pending".equals(data.getString(offer + ".status"))
                    || !buyer.getUniqueId().toString().equals(
                            data.getString(offer + ".payer", data.getString(offer + ".from")))
                    || data.getLong(offer + ".amount") != unitPrice
                    || data.getLong(offer + ".quantity", 1) != quantity) {
                buyer.sendMessage(ChatColor.RED + "That reserved offer is no longer available.");
                return;
            }
        }
        boolean reservedOffer = acceptedOfferId != null
                && data.getBoolean(offerPath(acceptedOfferId) + ".reserved");
        if (!reservedOffer && buyerBalance < totalPrice) {
            buyer.sendMessage(ChatColor.RED + "You need " + formatAmount(totalPrice) + ", but only have "
                    + formatAmount(buyerBalance) + ".");
            return;
        }

        ItemStack item = listedItem.clone();
        item.setAmount((int) quantity);
        if (!hasInventorySpace(buyer, item, (int) quantity)) {
            buyer.sendMessage(ChatColor.RED + "Cannot buy: your inventory doesn't have enough room.");
            return;
        }
        if (!reservedOffer) {
            data.set("balances." + buyer.getUniqueId(), buyerBalance - totalPrice);
        }
        data.set("balances." + sellerId, sellerBalance + totalPrice);

        int remaining = listedItem.getAmount() - (int) quantity;
        if (remaining == 0) {
            data.set(path, null);
        } else {
            listedItem.setAmount(remaining);
            data.set(path + ".item", listedItem);
        }
        for (String offerId : getOfferIds()) {
            String offerPath = offerPath(offerId);
            if (listingId.equals(data.getString(offerPath + ".listing"))
                    && "pending".equals(data.getString(offerPath + ".status"))) {
                if (offerId.equals(acceptedOfferId)) {
                    data.set(offerPath + ".status", "completed");
                } else {
                    refundOffer(offerId, "cancelled");
                }
            }
        }
        saveData();

        buyer.getInventory().addItem(item);
        updateBalanceBar(buyer.getUniqueId());
        updateBalanceBar(sellerId);
        buyer.sendMessage(ChatColor.GREEN + "Bought " + quantity + " " + itemName(item)
                + " for " + formatAmount(totalPrice) + ".");
        Player seller = Bukkit.getPlayer(sellerId);
        if (seller != null) {
            seller.sendMessage(ChatColor.GREEN + "You sold " + quantity + " " + itemName(item)
                    + " to " + buyer.getName() + " for " + formatAmount(totalPrice) + ".");
        }
        if (buyer.getOpenInventory().getTopInventory().getHolder() instanceof ListingHolder
                || buyer.getOpenInventory().getTopInventory().getHolder() instanceof ShopHolder) {
            openShop(buyer, 0);
        }
        if (seller != null && seller.getOpenInventory().getTopInventory().getHolder() instanceof OffersHolder) {
            openOffers(seller);
        }
    }

    private void updateOfferStatus(Player player, String offerId, String status) {
        String path = offerPath(offerId);
        if (!"pending".equals(data.getString(path + ".status"))
                || !player.getUniqueId().toString().equals(data.getString(path + ".to"))) {
            player.sendMessage(ChatColor.RED + "That offer is no longer available.");
        } else {
            refundOffer(offerId, status);
            saveData();
            player.sendMessage(ChatColor.YELLOW + "Offer declined.");
        }
        openOffers(player);
    }

    private void cancelOffer(Player player, String offerId) {
        String path = offerPath(offerId);
        if (!"pending".equals(data.getString(path + ".status"))
                || !player.getUniqueId().toString().equals(data.getString(path + ".from"))) {
            player.sendMessage(ChatColor.RED + "That offer is no longer available.");
        } else {
            boolean reserved = data.getBoolean(path + ".reserved");
            refundOffer(offerId, "cancelled");
            saveData();
            player.sendMessage(ChatColor.GREEN + (reserved
                    ? "Offer cancelled; your reserved funds were returned."
                    : "Counteroffer cancelled."));
        }
        openOffers(player);
    }

    private void withdrawListing(Player seller, String listingId) {
        String path = listingPath(listingId);
        if (!seller.getUniqueId().toString().equals(data.getString(path + ".seller"))
                || !listingAvailable(listingId)) {
            seller.sendMessage(ChatColor.RED + "That listing is no longer available.");
            openMyListings(seller);
            return;
        }

        ItemStack item = data.getItemStack(path + ".item");
        refundOffersForListing(listingId);
        data.set(path, null);
        saveData();
        if (item != null && item.getType() != Material.AIR) {
            Map<Integer, ItemStack> leftovers = seller.getInventory().addItem(item);
            for (ItemStack leftover : leftovers.values()) {
                seller.getWorld().dropItemNaturally(seller.getLocation(), leftover);
            }
        }
        seller.sendMessage(ChatColor.GREEN + "Listing removed; unsold items returned.");
        openMyListings(seller);
    }

    private void refundOffersForListing(String listingId) {
        for (String id : getOfferIds()) {
            String path = offerPath(id);
            if (listingId.equals(data.getString(path + ".listing"))
                    && "pending".equals(data.getString(path + ".status"))) {
                refundOffer(id, "cancelled");
            }
        }
    }

    private void refundOffer(String offerId, String status) {
        String path = offerPath(offerId);
        if (!"pending".equals(data.getString(path + ".status"))) {
            return;
        }
        UUID payer = UUID.fromString(data.getString(path + ".payer", data.getString(path + ".from")));
        long refund = 0;
        if (data.getBoolean(path + ".reserved")) {
            refund = data.getLong(path + ".amount");
            long balance = getBalance(payer);
            data.set("balances." + payer, balance + refund);
            updateBalanceBar(payer);
        }
        data.set(path + ".status", status);
        data.set(path + ".reserved", false);
        Player online = refund > 0 ? Bukkit.getPlayer(payer) : null;
        if (online != null) {
            online.sendMessage(ChatColor.GREEN + "Your " + formatAmount(refund)
                    + " reserved offer funds were returned.");
        }
    }

    private void cancelUnreservedLegacyOffers() {
        boolean changed = false;
        for (String id : getOfferIds()) {
            String path = offerPath(id);
            if (!"pending".equals(data.getString(path + ".status"))
                    || "total".equals(data.getString(path + ".price-mode"))) {
                continue;
            }
            if (data.getBoolean(path + ".reserved")) {
                UUID payer = UUID.fromString(data.getString(path + ".payer",
                        data.getString(path + ".from")));
                long refund = data.getLong(path + ".amount")
                        * data.getLong(path + ".quantity", 1);
                long balance = getBalance(payer);
                if (refund > MAX_PRICE - balance) {
                    getLogger().severe("Could not refund legacy barter offer " + id
                            + " because the refund exceeds the maximum balance.");
                    continue;
                }
                data.set("balances." + payer, balance + refund);
                updateBalanceBar(payer);
            }
            data.set(path + ".status", "cancelled");
            data.set(path + ".reserved", false);
            changed = true;
        }
        if (changed) {
            saveData();
        }
    }

    private boolean listingAvailable(String id) {
        return data.contains(listingPath(id) + ".item");
    }

    private boolean hasInventorySpace(Player player, ItemStack item, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        int storageSlots = Math.min(36, contents.length);
        for (int slot = 0; slot < storageSlots && remaining > 0; slot++) {
            ItemStack current = contents[slot];
            if (current == null || current.getType() == Material.AIR) {
                remaining -= item.getMaxStackSize();
            } else if (current.isSimilar(item)) {
                remaining -= Math.max(0, current.getMaxStackSize() - current.getAmount());
            }
        }
        return remaining <= 0;
    }

    private void migrateCurrencyToCents() {
        if (data.getInt("currency-version", 0) >= CURRENCY_VERSION) {
            return;
        }
        migrateAmounts("balances");
        for (String id : getListingIds()) {
            migrateAmount(listingPath(id) + ".price");
        }
        for (String id : getOfferIds()) {
            migrateAmount(offerPath(id) + ".amount");
        }
        migrateEarnPrices();
        data.set("currency-version", CURRENCY_VERSION);
        saveData();
    }

    private void migrateEarnPrices() {
        ConfigurationSection overrides = data.getConfigurationSection("earn-prices");
        if (overrides == null) {
            return;
        }
        ConfigurationSection defaults = getConfig().getConfigurationSection("earn-prices");
        for (String key : overrides.getKeys(false)) {
            String path = "earn-prices." + key;
            if (defaults != null && defaults.contains(key)) {
                data.set(path, null);
            } else if (!isValidMaterialKey(key)) {
                getLogger().warning("Removing invalid legacy earn-price material: " + key);
                data.set(path, null);
            } else {
                migrateAmount(path);
            }
        }
    }

    private void migrateAmounts(String sectionPath) {
        ConfigurationSection section = data.getConfigurationSection(sectionPath);
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            migrateAmount(sectionPath + "." + key);
        }
    }

    private void migrateAmount(String path) {
        if (!data.contains(path)) {
            return;
        }
        long cents = configuredAmount(data.get(path), path);
        data.set(path, Math.min(MAX_PRICE, Math.max(0, cents)));
    }

    private long configuredAmount(Object value, String path) {
        if (value == null) {
            return 0;
        }
        try {
            long cents = new BigDecimal(value.toString()).movePointRight(2).longValueExact();
            if (cents < 0 || cents > MAX_PRICE) {
                throw new ArithmeticException("amount out of range");
            }
            return cents;
        } catch (NumberFormatException | ArithmeticException exception) {
            getLogger().severe("Invalid currency amount at " + path + ": " + value);
            return 0;
        }
    }

    private long getBalance(UUID id) {
        String path = "balances." + id;
        if (!data.contains(path)) {
            data.set(path, Math.min(MAX_PRICE,
                    Math.max(0, configuredAmount(getConfig().get("starting-balance", 100), "starting-balance"))));
            updateBalanceBar(id);
            saveData();
        }
        return Math.max(0, data.getLong(path));
    }

    private long getReservedBalance(UUID id) {
        long total = 0;
        for (String offerId : getOfferIds()) {
            String path = offerPath(offerId);
            if (!"pending".equals(data.getString(path + ".status"))
                    || !data.getBoolean(path + ".reserved")
                    || !id.toString().equals(data.getString(path + ".payer", data.getString(path + ".from")))) {
                continue;
            }
            long amount = data.getLong(path + ".amount");
            if (amount > MAX_PRICE - total) {
                return MAX_PRICE;
            }
            total += amount;
        }
        return total;
    }

    private long getEarnPrice(String material) {
        String key = material.toUpperCase(Locale.ROOT);
        String path = "earn-prices." + key;
        if (data.contains(path)) {
            return Math.max(0, data.getLong(path));
        }
        return Math.max(0, configuredAmount(getConfig().get(path), path));
    }

    private long getEarnPrice(ItemStack item) {
        String material = item.getType().name();
        short dataValue = item.getDurability();
        String variant = material + ":" + dataValue;
        long price;
        if (data.contains("earn-prices." + variant)
                || getConfig().contains("earn-prices." + variant)) {
            price = getEarnPrice(variant);
        } else {
            price = getEarnPrice(material);
        }

        int maxDurability = item.getType().getMaxDurability();
        if (price == 0 || maxDurability <= 0) {
            return price;
        }

        int damage = Math.max(0, Math.min(item.getDurability(), maxDurability));
        int remainingDurability = maxDurability - damage;
        if (remainingDurability == 0) {
            return 0;
        }

        long adjustedPrice = BigDecimal.valueOf(price)
                .multiply(BigDecimal.valueOf(remainingDurability))
                .divide(BigDecimal.valueOf(maxDurability), 0, RoundingMode.DOWN)
                .longValueExact();
        if (damage > 0) {
            adjustedPrice = Math.min(adjustedPrice, price - 1);
        }
        return Math.max(1, adjustedPrice);
    }

    private void showCombatBar(Player player) {
        UUID playerId = player.getUniqueId();
        BossBar bar = combatBars.get(playerId);
        if (bar == null) {
            bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
            combatBars.put(playerId, bar);
        }
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }
        updateCombatBar(playerId);
    }

    private void updateCombatBar(UUID playerId) {
        BossBar bar = combatBars.get(playerId);
        if (bar == null) {
            return;
        }

        long remaining = getCombatRemaining(playerId);
        if (remaining > 0) {
            bar.setColor(BarColor.RED);
            bar.setTitle(ChatColor.RED + "Combat: "
                    + String.format(Locale.ROOT, "%.1f", remaining / 1000.0) + "s");
            bar.setProgress(Math.min(1.0, remaining / (double) COMBAT_DURATION_MILLIS));
        } else {
            bar.setColor(BarColor.GREEN);
            bar.setTitle(ChatColor.GREEN + "Combat: Safe");
            bar.setProgress(1.0);
        }
    }

    private void tagInCombat(Player player, long expiry) {
        combatExpiry.put(player.getUniqueId(), expiry);
        if (!combatBars.containsKey(player.getUniqueId())) {
            showCombatBar(player);
        } else {
            updateCombatBar(player.getUniqueId());
        }
    }

    private long getCombatRemaining(UUID playerId) {
        Long expiry = combatExpiry.get(playerId);
        if (expiry == null) {
            return 0;
        }

        long remaining = expiry - System.currentTimeMillis();
        if (remaining <= 0) {
            combatExpiry.remove(playerId, expiry);
            return 0;
        }
        return remaining;
    }

    private boolean denyMarketActionInCombat(Player player) {
        long remaining = getCombatRemaining(player.getUniqueId());
        if (remaining <= 0) {
            return false;
        }

        long seconds = (remaining + 999L) / 1000L;
        player.sendMessage(ChatColor.RED
                + "You cannot list or sell items while in combat. Wait "
                + seconds + " second" + (seconds == 1 ? "" : "s") + ".");
        return true;
    }

    private boolean denyTeleportInCombat(Player player) {
        long remaining = getCombatRemaining(player.getUniqueId());
        if (remaining <= 0) {
            return false;
        }

        long seconds = (remaining + 999L) / 1000L;
        player.sendMessage(ChatColor.RED + "You cannot teleport while in combat. Wait "
                + seconds + " second" + (seconds == 1 ? "" : "s") + ".");
        return true;
    }

    private boolean denyTeleportBetweenPlayers(Player first, Player second) {
        boolean firstInCombat = denyTeleportInCombat(first);
        boolean secondInCombat = denyTeleportInCombat(second);
        if (firstInCombat) {
            second.sendMessage(ChatColor.RED + first.getName()
                    + " cannot teleport while in combat.");
        }
        if (secondInCombat) {
            first.sendMessage(ChatColor.RED + second.getName()
                    + " cannot teleport while in combat.");
        }
        return firstInCombat || secondInCombat;
    }

    private void showBalance(Player player) {
        getBalance(player.getUniqueId());
        BossBar bar = balanceBars.get(player.getUniqueId());
        if (bar == null) {
            bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
            balanceBars.put(player.getUniqueId(), bar);
        }
        bar.setTitle(ChatColor.GOLD + "Balance: "
                + formatAmount(getBalance(player.getUniqueId())));
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }
    }

    private void showCommandSidebar(Player player) {
        UUID playerId = player.getUniqueId();
        Objective objective = commandObjectives.get(playerId);
        if (objective == null) {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                getLogger().severe("Could not display shop commands: scoreboard manager is unavailable.");
                return;
            }
            originalScoreboards.put(playerId, player.getScoreboard());
            Scoreboard scoreboard = manager.getNewScoreboard();
            objective = scoreboard.registerNewObjective("shopcmd", "dummy");
            objective.setDisplayName(ChatColor.GOLD + "Server Commands");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);

            for (int index = 0; index < PUBLIC_COMMANDS.length; index++) {
                String entry = ChatColor.values()[index].toString();
                Team team = scoreboard.registerNewTeam("shopcmd" + index);
                team.addEntry(entry);
                String line = PUBLIC_COMMANDS[index];
                team.setPrefix(line.substring(0, Math.min(16, line.length())));
                if (line.length() > 16) {
                    team.setSuffix(line.substring(16, Math.min(32, line.length())));
                }
                objective.getScore(entry).setScore(PUBLIC_COMMANDS.length - index);
            }

            commandObjectives.put(playerId, objective);
            player.setScoreboard(scoreboard);
        }
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    private void hideCommandSidebar(Player player) {
        Objective objective = commandObjectives.get(player.getUniqueId());
        if (objective != null) {
            objective.setDisplaySlot(null);
        }
    }

    private void restoreOriginalScoreboard(Player player) {
        UUID playerId = player.getUniqueId();
        Scoreboard original = originalScoreboards.remove(playerId);
        if (original != null) {
            player.setScoreboard(original);
        }
        commandObjectives.remove(playerId);
    }

    private void updateBalanceBar(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player != null && balanceBars.containsKey(id)) {
            balanceBars.get(id).setTitle(ChatColor.GOLD + "Balance: "
                    + formatAmount(getBalance(id)));
        }
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save shop data: " + exception.getMessage());
        }
    }

    private List<String> getListingIds() {
        ConfigurationSection section = data.getConfigurationSection("listings");
        return section == null ? new ArrayList<String>() : new ArrayList<String>(section.getKeys(false));
    }

    private List<String> getListingIdsForSeller(UUID seller) {
        List<String> result = new ArrayList<String>();
        for (String id : getListingIds()) {
            if (seller.toString().equals(data.getString(listingPath(id) + ".seller"))) {
                result.add(id);
            }
        }
        return result;
    }

    private List<String> getOfferIds() {
        ConfigurationSection section = data.getConfigurationSection("offers");
        return section == null ? new ArrayList<String>() : new ArrayList<String>(section.getKeys(false));
    }

    private List<String> getOfferIdsForPlayer(UUID player) {
        List<String> result = new ArrayList<String>();
        for (String id : getOfferIds()) {
            String path = offerPath(id);
            if ("pending".equals(data.getString(path + ".status"))
                    && (player.toString().equals(data.getString(path + ".to"))
                    || player.toString().equals(data.getString(path + ".from")))) {
                result.add(id);
            }
        }
        return result;
    }

    private long parseAmount(String text) {
        try {
            String normalized = text.trim().replace("$", "").replace(",", "");
            BigDecimal dollars = new BigDecimal(normalized);
            if (dollars.scale() > 2 || dollars.signum() < 0) {
                return -1;
            }
            long cents = dollars.movePointRight(2).longValueExact();
            return cents <= MAX_PRICE ? cents : -1;
        } catch (NumberFormatException | ArithmeticException exception) {
            return -1;
        }
    }

    private String formatAmount(long cents) {
        return "$" + cents / 100 + "." + String.format(Locale.ROOT, "%02d", cents % 100);
    }

    private boolean isValidDataValue(String value) {
        if (!value.matches("\\d+") || value.length() > 5) {
            return false;
        }
        return Integer.parseInt(value) <= 32767;
    }

    private boolean isValidMaterialKey(String key) {
        String[] parts = key.split(":", -1);
        if (parts.length > 2 || parts.length == 2 && !isValidDataValue(parts[1])) {
            return false;
        }
        try {
            Material.valueOf(parts[0]);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String listingPath(String id) {
        return "listings." + id;
    }

    private String offerPath(String id) {
        return "offers." + id;
    }

    private ItemStack menuItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private String itemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return item.getType().toString().toLowerCase().replace('_', ' ');
    }

    private enum InputKind {
        LISTING,
        OFFER,
        COUNTER
    }

    private static final class TeleportRequest {
        private final UUID id;
        private final UUID requesterId;
        private final long expiresAt;
        private final boolean requesterTravels;

        private TeleportRequest(UUID id, UUID requesterId, long expiresAt,
                boolean requesterTravels) {
            this.id = id;
            this.requesterId = requesterId;
            this.expiresAt = expiresAt;
            this.requesterTravels = requesterTravels;
        }
    }

    private static final class TeleportWarmup {
        private final UUID targetId;
        private final Location destination;
        private final String destinationName;
        private int elapsedTicks;
        private BukkitTask task;

        private TeleportWarmup(UUID targetId, Location destination, String destinationName) {
            this.targetId = targetId;
            this.destination = destination;
            this.destinationName = destinationName;
        }
    }

    private static final class PendingInput {
        private final InputKind kind;
        private final String reference;

        private PendingInput(InputKind kind, String reference) {
            this.kind = kind;
            this.reference = reference;
        }

        private static PendingInput listing() {
            return new PendingInput(InputKind.LISTING, "");
        }

        private static PendingInput offer(String listingId) {
            return new PendingInput(InputKind.OFFER, listingId);
        }

        private static PendingInput counter(String offerId) {
            return new PendingInput(InputKind.COUNTER, offerId);
        }

    }

    private interface MenuHolder extends InventoryHolder {
        Inventory getInventory();
    }

    private static final class ShopHolder implements MenuHolder {
        private final int page;
        private Inventory inventory;

        private ShopHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ListingHolder implements MenuHolder {
        private final String listingId;
        private final int page;
        private Inventory inventory;

        private ListingHolder(String listingId, int page) {
            this.listingId = listingId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class OffersHolder implements MenuHolder {
        private final UUID playerId;
        private Inventory inventory;

        private OffersHolder(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class MyListingsHolder implements MenuHolder {
        private final UUID playerId;
        private Inventory inventory;

        private MyListingsHolder(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class EarnHolder implements MenuHolder {
        private final UUID playerId;
        private Inventory inventory;

        private EarnHolder(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class OfferHolder implements MenuHolder {
        private final String offerId;
        private Inventory inventory;

        private OfferHolder(String offerId) {
            this.offerId = offerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
