## Eaglercraft-Server-Paper

**Server address:** `verbose-carnival-96q6v949q54pfxv57-25565.app.github.dev`

The Boi's SMP: create teams, sell items, and be the best team.
Follow the rules: three warnings will result in a ban.

Universal Eaglercraft server (1.5, 1.8, 1.12) using Paper 1.12.2.

Use the `run.bat` file to run on Windows, or `run.sh` to run on macOS or Linux. The server uses port 25565 for both Vanilla and Eaglercraft players.

Based on EaglercraftXServer, EaglercraftXRewind, ViaVersion, SkinsRestorer, and AuthMeReloaded.

### Backpacks

The included Backpack plugin adds a named, 27-slot backpack. Craft one with leather in
all eight outer crafting slots and a chest in the center. Wear it or hold it in either
hand and run `/backpack open` to open it; run `/backpack close` to close it. Its contents are
saved when closed and are associated with the backpack item, not a specific player,
so they remain available if it is renamed, dropped, or carried by another player.
Backpacks cannot be placed inside another backpack or inside themselves.
Backpack data is stored in `plugins/Backpack/backpacks.yml`.

The plugin source is in `backpack-plugin/`; rebuild it with
`mvn -f backpack-plugin/pom.xml package` and place `backpack-plugin/target/Backpack.jar`
in the server's `plugins/` directory.

### Player shop

The Shop plugin adds player listings, barter offers, and a persistent dollar balance
shown in the boss bar at the top of the screen. Hold an item and run `/sell <price>`
to list the whole stack at that per-item price (for example, `2.50`); `/sell` prompts for the price. Use
`/shop` to browse; select a listing to buy the whole stack at its asking price or make
a single total-price barter offer for the entire listing.
Use `/offers` to accept, decline, counter, or cancel your sent offers. Pending offer
funds are reserved and refunded if an offer is cancelled or declined. Barter offers
and counteroffers are one total price for the entire listing, not a per-item price.
Sellers receive payment when a trade completes, and purchases are blocked if the
buyer's inventory cannot fit the items.
Players start with $100.00 by default. Operators can use `/shop setbalance <player>
<amount>` or `/shop addbalance <player> <amount>` to manage balances; amounts accept up to two
decimal places.

Use `/shop` then **My listings** to remove an unsold listing and reclaim its items.
`/offers` also lets you cancel a sent offer and get reserved funds back. `/earn` opens
a menu where you can place items and click **Sell priced items** to earn money.
Operators set per-item prices with
`/earn price <material[:data]> <price-per-item>` (use `0` to disable an item); `/earn prices`
shows the configured defaults. The default sell catalog covers common 1.12.2 items in
the ores and gems, building blocks, farming and food, mob drops, and rare/end-game
categories; inferred prices scale with rarity, while the supplied prices are kept exact.
Damageable items sell for a proportion of their configured price based on remaining
durability, so damaged armor and tools are worth less.
Soulbound books have a 10% chance when enchanting a book using the level-30 option and
a 0.2% chance in each naturally generated chest. Operators can use `/soulbound` to get a
book. Apply it to any item in an anvil for 1 level; the item keeps its other enchantments.
The player who applies the book owns the resulting item; other players cannot take it
from containers, and hopper transfers leave it where it is stored. Soulbound items
return to their owner's inventory when their chest is broken or destroyed by an explosion.
If the owner is offline, they remain in recovery and return on the owner's next login.
Unowned legacy items return to the player breaking the chest. Full inventories keep
recovery items until space is available. They cannot be dropped, listed, or sold to the
server, and stay in the same inventory or armor slot through death and combat-logging. Their lore shows
`Soulbound to <player>`.
Older Soulbound swords, axes, bows, armor, Elytra, and shields without an owner marker
are treated as owned by `thedeaths`.
The player-facing Shop commands are displayed in a sidebar and hidden while an
inventory menu is open.

Use `/ptp <player>` to request to teleport to an online player, or
`/ptphere <player>` to request that an online player teleport to you. They can
accept or decline using the clickable **Yes** and **No** buttons in the request
message. After acceptance, the player who will move is held in place for five
seconds while smoke surrounds them, then teleported. Teleport requests expire
after one minute, and cannot be sent, accepted, or completed while either player
is in combat.
Use `/home` to teleport to your bed respawn point, or the world spawn if you have
not set a bed. It uses the same five-second, stay-still warmup and is cancelled if
you are in combat.
Use `/spawn` to teleport to the world spawn with the same warmup and combat restriction.
Use `/rule` to view the server rules: no raiding offline players, betraying teammates,
hacks, or hack clients. Breaking any rule results in being teleported to the server
owner and killed.
Operators with `shop.logjail` can use `/logjail <player>` to move an online player, or
set an offline player's next login location, one block in front of themselves. Log-jailed
players cannot move but can drop ordinary items. Operators with `shop.logjail`, or any
log-jailed player, can run `/unlogjail` to release all log-jailed players.

Shop data is stored in `plugins/Shop/shop.yml`. The plugin source is in `shop-plugin/`;
build it with `mvn -f shop-plugin/pom.xml package` and put
`shop-plugin/target/Shop.jar` in the server's `plugins/` directory.
Stop the server before replacing the plugin JAR, then start it again; replacing it
while the server is running can cause internal command errors.

### Client guard

The ClientGuard plugin kicks players whose reported client brand contains `Death Client`,
with the message “No hacks allowed”. Build it with
`mvn -f client-guard-plugin/pom.xml package` and put
`client-guard-plugin/target/ClientGuard.jar` in the server's `plugins/` directory.
Client brands are self-reported and can be changed or hidden, so this is only a basic
filter and not a substitute for server-side cheat detection.

You can also download the EaglerMOTD plugin to animate your server's MOTD, and the EaglerWeb plugin to host a website from your server, both of which are available here [https://github.com/lax1dude/eaglerxserver/releases](https://github.com/lax1dude/eaglerxserver/releases)

**Note:** If you would like to support versions of Eaglercraft 1.5 older than 22w34a (before singleplayer), add `-Deaglerxrewind.oldChunkFormat=true` to the JVM arguments in the run script. This will perform worse for both clients and the server, but will be compatible with every version of 1.5 going back to 2020.
