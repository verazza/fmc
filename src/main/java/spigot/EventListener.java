package spigot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.slf4j.Logger;

import com.google.inject.Inject;

import common.Database;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import spigot_command.Book;
import spigot_command.Button;
import spigot_command.Menu;

public final class EventListener implements Listener {
    private final common.Main plugin;
    private final Logger logger;
    private final Database db;
	private final PortalsConfig psConfig;
    private final Menu menu;
    private final ServerStatusCache ssc;
    private final ImageMap im;
    private final Book book;
    private final Luckperms lp;
    private final Set<Player> playersInPortal = new HashSet<>(); // プレイヤーの状態を管理するためのセット
    //private final Set<Player> playersOpeningNewInventory = new HashSet<>();

    @Inject
	public EventListener(common.Main plugin, Logger logger, Database db, PortalsConfig psConfig, Menu menu, ServerStatusCache ssc, ImageMap im, Book book, Luckperms lp) {
		this.plugin = plugin;
        this.logger = logger;
        this.db = db;
		this.psConfig = psConfig;
        this.menu = menu;
        this.ssc = ssc;
        this.im = im;
        this.book = book;
        this.lp = lp;
	}

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();
        Block clickedBlock = event.getClickedBlock();
        if (action == Action.RIGHT_CLICK_BLOCK && clickedBlock != null) {
            ItemStack itemInHand = player.getInventory().getItemInMainHand();
            if (itemInHand.getType() != Material.AIR) {
                ItemMeta meta = itemInHand.getItemMeta();
                if (meta != null && meta.getPersistentDataContainer().has(new NamespacedKey(plugin, Button.PERSISTANT_KEY), PersistentDataType.STRING)) {
                    player.sendMessage(ChatColor.GREEN + "特定のボタンが右クリックされました！");
                }
            }
        }
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        if (!lp.hasPermission(playerName, "new-fmc-user")) {
            player.teleport(FMCCoords.LOAD_POINT.getLocation());
        } else {
            player.teleport(FMCCoords.HUB_POINT.getLocation());
        }
        // 非同期タスクを実行
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // 非同期タスク内で同期タスクを実行して、プレイヤーのインベントリを更新
            Bukkit.getScheduler().runTask(plugin, () -> {
                try (Connection conn = db.getConnection()) {
                    im.checkPlayerInventory(conn, player);
                } catch (SQLException | ClassNotFoundException e) {
                    player.sendMessage(ChatColor.RED + "画像マップの読み込みに失敗しました。管理者にお問い合わせください。");
                    logger.error("An SQLException | ClassNotFoundException error occurred: {}", e.getMessage());
                    for (StackTraceElement element : e.getStackTrace()) {
                        logger.error(element.toString());
                    }
                }
            });
        });
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            book.checkPlayerInventory(player);
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        String title = event.getView().getTitle();
        if (title.equals(Menu.imageInventoryName)) {
            menu.resetPage(player, Menu.imageInventoryKey);
        } else if (title.equals(Menu.onlineServerInventoryName)) {
            // 1: 必ずこの順序で処理すること(インベントリタイトルが重複しているため)
            menu.resetPage(player, Menu.onlineServerInventoryKey);
        } else if (title.equals(Menu.menuInventoryName)) {
            menu.resetPage(player, Menu.menuInventoryKey);
        } else if (title.endsWith(" servers")) {
            // 2: 必ずこの順序で処理すること(インベントリタイトルが重複しているため)
            String serverType = title.split(" ")[0];
            if (serverType != null) {
                menu.resetPage(player, serverType);
            }
        } else if (title.endsWith(" server")) {
            String serverName = title.split(" ")[0];
            if (serverName != null) {
                menu.resetPage(player, serverName);
            }
        }
    }
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) throws SQLException {
        if (event.getWhoClicked() instanceof Player player) {
            //playersOpeningNewInventory.add(player);
            String title = event.getView().getTitle();
            if (title.equals(Menu.imageInventoryName)) {
                event.setCancelled(true);
                menu.runMenuAction(player, Menu.imageInventoryKey, event.getRawSlot());
            } else if (title.equals(Menu.menuInventoryName)) {
                event.setCancelled(true);
                menu.runMenuAction(player, Menu.menuInventoryKey, event.getRawSlot());
            } else if (title.endsWith(" server")) {
                event.setCancelled(true);
                Map<String, Map<String, Map<String, Object>>> serverStatusMap = ssc.getStatusMap();
                String serverName = title.split(" ")[0];
                boolean iskey = serverStatusMap.entrySet().stream()
                    .anyMatch(e -> e.getValue().entrySet().stream()
                    .anyMatch(e2 -> e2.getKey() instanceof String statusServerName && statusServerName.equals(serverName)));
                if (iskey) {
                    menu.runMenuAction(player, Menu.serverInventoryKey, event.getRawSlot());
                }
            } else if (title.equals(Menu.serverTypeInventoryName)) {
                // 1: 必ずこの順序で処理すること(インベントリタイトルが重複しているため)
                event.setCancelled(true);
                menu.runMenuAction(player, Menu.serverTypeInventoryKey, event.getRawSlot());
            } else if (title.equals(Menu.onlineServerInventoryName)) {
                event.setCancelled(true);
                menu.runMenuAction(player, Menu.onlineServerInventoryKey, event.getRawSlot());
            } else if (title.endsWith(" servers")) {
                // 2: 必ずこの順序で処理すること(インベントリタイトルが重複しているため)
                event.setCancelled(true);
                menu.runMenuAction(player, Menu.serverTypeInventoryKey, event.getRawSlot());
            }
        }
    }
    
	@EventHandler
    @SuppressWarnings("unchecked")
    public void onPlayerMove(PlayerMoveEvent e) {
        if (plugin.getConfig().getBoolean("Portals.Move", false)) {
            Player player = e.getPlayer();
            Location loc = player.getLocation();
            FileConfiguration portalsConfig;
            List<Map<?, ?>> portals;
            if (WandListener.isMakePortal) {
                WandListener.isMakePortal = false;
                portalsConfig = psConfig.getPortalsConfig();
                portals = (List<Map<?, ?>>) portalsConfig.getList("portals");
            } else {
                portalsConfig = psConfig.getPortalsConfig();
                portals = (List<Map<?, ?>>) portalsConfig.getList("portals");
            }
            boolean isInAnyPortal = false;
            if (portals != null) {
                for (Map<?, ?> portal : portals) {
                    String name = (String) portal.get("name");
                    List<?> corner1List = (List<?>) portal.get("corner1");
                    List<?> corner2List = (List<?>) portal.get("corner2");
                    if (corner1List != null && corner2List != null) {
                        Location corner1 = new Location(player.getWorld(),
                                ((Number) corner1List.get(0)).doubleValue(),
                                ((Number) corner1List.get(1)).doubleValue(),
                                ((Number) corner1List.get(2)).doubleValue());
                        Location corner2 = new Location(player.getWorld(),
                                ((Number) corner2List.get(0)).doubleValue(),
                                ((Number) corner2List.get(1)).doubleValue(),
                                ((Number) corner2List.get(2)).doubleValue());
                        if (isWithinBounds(loc, corner1, corner2)) {
                            isInAnyPortal = true;
                            if (!playersInPortal.contains(player)) {
                                playersInPortal.add(player);
                                logger.info("Player {} entered the {}!", new Object[]{player.getName(), name});
                                BaseComponent[] component = new ComponentBuilder()
                                    .append(ChatColor.WHITE + "ゲート: ")
                                    .append(ChatColor.AQUA + name)
                                        .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new net.md_5.bungee.api.chat.hover.content.Text("クリックしてコピー")))
                                        .event(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.COPY_TO_CLIPBOARD, name))
                                    .append(ChatColor.WHITE + " に入りました！")
                                    .create();
                                player.spigot().sendMessage(component);
                                switch (name) {
                                    case "life","distributed","mod" -> {
                                        player.performCommand("fmc menu server " + name);
                                    }
                                    case "waterGate" -> {
                                        // ここで触れているブロックが水源ブロックかどうかを判定
                                        Block block = loc != null ? loc.getBlock() : null;
                                        if (block != null && block.getType() == Material.WATER) {
                                            player.performCommand("fmc check");
                                        }
                                    }
                                }
                            }
                            break; // 一つのポータルに触れたらループを抜ける
                        }
                    }
                }
            }
            if (!isInAnyPortal) {
                playersInPortal.remove(player);
            }
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent e) {
        Player player = e.getPlayer();
        if (playersInPortal.contains(player)) {
            playersInPortal.remove(player);
        }
    }
    
	// MCVCをONにすると、ベッドで寝れなくなるため、必要なメソッド
	@EventHandler
    public void onPlayerBedEnter(PlayerBedEnterEvent e) {
		if (Rcon.isMCVC) {
	        if (e.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
	            World world = e.getPlayer().getWorld();
	            world.setTime(1000);
	            world.setStorm(false);
	            world.setThundering(false);
	            e.getPlayer().sendMessage("おはようございます！時間を朝にしました。");
	        }
		}
    }

    private boolean isWithinBounds(Location loc, Location corner1, Location corner2) {
        double x1 = Math.min(corner1.getX(), corner2.getX());
        double x2 = Math.max(corner1.getX(), corner2.getX());
        double y1 = Math.min(corner1.getY(), corner2.getY());
        double y2 = Math.max(corner1.getY(), corner2.getY());
        double z1 = Math.min(corner1.getZ(), corner2.getZ());
        double z2 = Math.max(corner1.getZ(), corner2.getZ());

        return loc.getX() >= x1 && loc.getX() < x2+1 &&
               loc.getY() >= y1 && loc.getY() < y2+1 &&
               loc.getZ() >= z1 && loc.getZ() < z2+1;
    }
}
