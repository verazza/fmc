package spigot;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import com.google.inject.Inject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;

import net.md_5.bungee.api.ChatColor;

public class ImageMap {
    public static List<String> imagesColumnsList = new ArrayList<>();
    public static AtomicBoolean isGetColumnInfo = new AtomicBoolean(false);
    public static List<Integer> thisServerMapIds = new ArrayList<>();
    public static List<String> args2 = new ArrayList<>(Arrays.asList("create", "createqr", "q"));
    private final common.Main plugin;
    private final Database db;
    private final String serverName;
    @Inject
    public ImageMap(common.Main plugin, Database db, ServerHomeDir shd) {
        this.plugin = plugin;
        this.db = db;
        this.serverName = shd.getServerName();
    }

    public void executeQ(CommandSender sender, Command command, String label, String[] args, boolean q) {
        if (sender instanceof Player player) {
            int argsLength = args.length;
            String otp;
            // q : true -> /q, false -> /fmc q
            if (q) {
                otp = args[0];
                if (argsLength < 1) {
                    player.sendMessage("使用法: /q <code>");
                    return;
                }
            } else {
                otp = args[1];
                if (argsLength < 2) {
                    player.sendMessage("使用法: /fmc q <code>");
                    return;
                }
            }
            try (Connection conn = db.getConnection()) {
                if (checkOTPIsCorrect(conn, otp)) {
                    player.sendMessage("認証に成功しました。");
                    Map<String, Object> imageInfo = getImageInfoByOTP(conn, otp);
                    String dName = (String) imageInfo.get("name"),
                        dId = (String) imageInfo.get("did"),
                        title = (String) imageInfo.get("title"),
                        comment = (String) imageInfo.get("comment"),
                        url = (String) imageInfo.get("url");
                    Date date = Date.valueOf(((Date) imageInfo.get("date")).toLocalDate());
                    boolean isQr = (boolean) imageInfo.get("isqr");
                    String[] imageArgs = new String[] {"im", isQr ? "createqr" : "create", url, title, comment};
                    // dateを使うので、player.performCommandメソッドを使わず、直接実行させる
                    executeImageMap(sender, command, label, imageArgs, new Object[] {otp, dName, dId, date});
                } else {
                    player.sendMessage(ChatColor.RED + "ワンタイムパスワードが間違っています。");
                }
            } catch (SQLException | ClassNotFoundException e) {
                player.sendMessage("画像の読み込みに失敗しました。");
                plugin.getLogger().log(Level.SEVERE, "An SQLException | ClassNotFoundException error occurred: {0}", e.getMessage());
                for (StackTraceElement element : e.getStackTrace()) {
                    plugin.getLogger().severe(element.toString());
                }
            }
        } else {
            if (sender != null) {
                sender.sendMessage("このコマンドはプレイヤーのみが実行できます。");
            }
        }
    }
    
    private void copyLineFromMenu(Connection conn, int id, Map<String, String> modifyMap) throws SQLException, ClassNotFoundException {
        List<String> modifiedImageColumnsList = new ArrayList<>(imagesColumnsList),
            imagesColumnsListCopied = new ArrayList<>(imagesColumnsList);
        for (Map.Entry<String, String> entry : modifyMap.entrySet()) {
            String key = entry.getKey(),
                newValue = entry.getValue();
                modifiedImageColumnsList = modifiedImageColumnsList.stream()
                .map(s -> s.equals(key) ? newValue : s)
                .collect(Collectors.toList());
        }
        // idカラムを除外
        imagesColumnsListCopied.remove("id");
        modifiedImageColumnsList.remove("id");
        String query = "INSERT INTO images (" + String.join(", ", imagesColumnsListCopied) + ") " +
                    "SELECT " + String.join(", ", modifiedImageColumnsList) + " " +
                    "FROM images " +
                    "WHERE id = ?;";
        PreparedStatement ps = conn.prepareStatement(query);
        ps.setInt(1, id);
        ps.executeUpdate();
    }

    public void executeImageMapFromMenu(Player player, Object[] mArgs) {
        // このサーバーにはないマップを生成する
        try (Connection conn = db.getConnection()) {
            int id = (int) mArgs[0];
            boolean isQr = (boolean) mArgs[1];
            String authorName = (String) mArgs[2],
                imageUUID = (String) mArgs[3],
                title = (String) mArgs[4],
                comment = (String) mArgs[5],
                ext = (String) mArgs[6],
                date = (String) mArgs[7],
                fullPath = getImageSaveFolder(conn) + "/" + date.replace("-", "") + "/" + imageUUID + "." + ext,
                playerName = player.getName();
            BufferedImage image;
            image = loadImage(fullPath);
            image = !isQr ? resizeImage(image, 128, 128) : image;
            List<String> lores = new ArrayList<>();
            lores.add(isQr ? "<QRコード>" : "<イメージマップ>");
            List<String> commentLines = Arrays.stream(comment.split("\n"))
                                .map(String::trim)
                                .collect(Collectors.toList());
            lores.addAll(commentLines);
            lores.add("created by " + authorName);
            lores.add("at " + date.replace("-", "/"));
            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
            MapView mapView = Bukkit.createMap(player.getWorld());
            int mapId = mapView.getId(); // 一意のmapIdを取得
            mapView.getRenderers().clear();
            mapView.addRenderer(new ImageMapRenderer(plugin, image, fullPath));
            var meta = (org.bukkit.inventory.meta.MapMeta) mapItem.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(title);
                meta.setLore(lores);
                meta.setMapView(mapView);
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "custom_image"), PersistentDataType.STRING, "true");
                mapItem.setItemMeta(meta);
            }
            Map<String, String> modifyMap = new HashMap<>();
            modifyMap.put("menu", "true");
            modifyMap.put("menuer", "\"" + playerName + "\"");
            modifyMap.put("server", "\"" + serverName + "\"");
            modifyMap.put("mapid", String.valueOf(mapId));
            copyLineFromMenu(conn, id, modifyMap);
            player.getInventory().addItem(mapItem);
            player.sendMessage("画像マップを渡しました。");
        } catch (IOException | SQLException | ClassNotFoundException e) {
            player.sendMessage("画像の読み取りに失敗しました。");
            plugin.getLogger().log(Level.SEVERE, "An IOException | SQLException | URISyntaxException | ClassNotFoundException error occurred: {0}", e.getMessage());
            for (StackTraceElement element : e.getStackTrace()) {
                plugin.getLogger().severe(element.toString());
            }
        }
    }

    @SuppressWarnings("null")
    // 未認証マップをクリックしたときの動作は、discordで発行したワンタイムパスワードをつかうよう、促す
    // ワンタイムパスワードを再取得するコマンドが必要？優先度は低いか
    // あるプレイヤーがマップを生成した特定のサーバーとは異なる別のサーバーで、誰かが新たに生成する場合、
    // そのサーバーには、そのマップは存在しないので、新たに生成するが、
    // そのマップのcreated by は最初に生成したプレイヤーの名前になることを留意する
    public void executeImageMap(CommandSender sender, Command command, String label, String[] args, Object[] dArgs) {
        if (sender instanceof Player player) {
            if (args.length < 3) {
                player.sendMessage("使用法: /fmc im <create|createqr> <url> [Optional: <title> <comment>]");
                return;
            }
            boolean isQr = args[1].equalsIgnoreCase("createqr"),
                fromDiscord = (dArgs != null);
            String playerName = player.getName(),
                playerUUID = player.getUniqueId().toString(),
                imageUUID = UUID.randomUUID().toString(),
                url = args[2],
                title = (args.length > 3 && !args[3].isEmpty()) ? args[3]: "無名のタイトル",
                comment = (args.length > 4 && !args[4].isEmpty()) ? args[4]: "コメントなし",
                ext,
                fullPath;
            if (!isQr && !isValidURL(url)) {
                player.sendMessage("無効なURLです。");
                return;
            }
            try (Connection conn = db.getConnection()) {
                LocalDate localDate = LocalDate.now();
                String now = fromDiscord ? ((Date) dArgs[3]).toString() : localDate.toString();
                BufferedImage image;
                if (isQr) {
                    ext = "png";
                    fullPath = getImageSaveFolder(conn) + "/" + now.replace("-", "") + "/" + imageUUID + "." + ext;
                    image = generateQRCodeImage(url);
                    saveImageToFileSystem(conn, image, imageUUID, ext);
                } else {
                    URL getUrl = new URI(url).toURL();
                    HttpURLConnection connection = (HttpURLConnection) getUrl.openConnection();
                    connection.setRequestMethod("GET");
                    connection.connect();
                    String contentType = connection.getContentType();
                    switch (contentType) {
                        case "image/png" -> ext = "png";
                        case "image/jpeg" -> ext = "jpeg";
                        case "image/jpg" -> ext = "jpg";
                        default -> {
                            player.sendMessage("指定のURLは規定の拡張子を持ちません。");
                            return;
                        }
                    }
                    fullPath = getImageSaveFolder(conn) + "/" + now.replace("-", "") + "/" + imageUUID + "." + ext;
                    image =  ImageIO.read(getUrl);
                    saveImageToFileSystem(conn, image, imageUUID, ext); // リサイズ前の画像を保存
                    image = resizeImage(image, 128, 128);
                    if (image == null) {
                        player.sendMessage("指定のURLは規定の拡張子を持ちません。");
                        return;
                    }
                }
                List<String> lores = new ArrayList<>();
                lores.add(isQr ? "<QRコード>" : "<イメージマップ>");
                List<String> commentLines = Arrays.stream(comment.split("\n"))
                                  .map(String::trim)
                                  .collect(Collectors.toList());
                lores.addAll(commentLines);
                lores.add("created by " + playerName);
                lores.add("at " + now.replace("-", "/"));
                ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
                MapView mapView = Bukkit.createMap(player.getWorld());
                int mapId = mapView.getId(); // 一意のmapIdを取得
                mapView.getRenderers().clear();
                mapView.addRenderer(new ImageMapRenderer(plugin, image, fullPath));
                var meta = (org.bukkit.inventory.meta.MapMeta) mapItem.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(title);
                    meta.setLore(lores);
                    meta.setMapView(mapView);
                    meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "custom_image"), PersistentDataType.STRING, "true");
                    mapItem.setItemMeta(meta);
                }
                if (fromDiscord) {
                    db.updateLog(conn, "UPDATE images SET name=?, uuid=?, server=?, mapid=?, title=?, imuuid=?, ext=?, url=?, comment=?, isqr=?, otp=?, d=?, dname=?, did=?, date=? WHERE otp=?;", new Object[] {playerName, playerUUID, serverName, mapId, title, imageUUID, ext, url, comment, isQr, null, fromDiscord, (String) dArgs[1], (String) dArgs[2], now, (String) dArgs[0]});
                } else {
                    db.insertLog(conn, "INSERT INTO images (name, uuid, server, mapid, title, imuuid, ext, url, comment, isqr, date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);", new Object[] {playerName, playerUUID, serverName, mapId, title, imageUUID, ext, url, comment, isQr, Date.valueOf(LocalDate.now())});
                }
                player.getInventory().addItem(mapItem);
                player.sendMessage("画像マップを渡しました。");
            } catch (IOException | SQLException | URISyntaxException | ClassNotFoundException | WriterException e) {
                player.sendMessage("画像のダウンロードまたは保存に失敗しました: " + url);
                plugin.getLogger().log(Level.SEVERE, "An IOException | SQLException | URISyntaxException | ClassNotFoundException error occurred: {0}", e.getMessage());
                for (StackTraceElement element : e.getStackTrace()) {
                    plugin.getLogger().severe(element.toString());
                }
            }
        } else {
            if (sender != null) {
                sender.sendMessage("このコマンドはプレイヤーのみが実行できます。");
            }
        }
    }

    public void checkPlayerInventory(Connection conn, Player player) throws SQLException, ClassNotFoundException {
        Map<Integer, Map<String, Object>> serverImageInfo = getThisServerImages(conn);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == Material.FILLED_MAP) {
                    MapMeta mapMeta = (MapMeta) item.getItemMeta();
                    if (mapMeta != null && mapMeta.hasMapView()) {
                        MapView mapView = mapMeta.getMapView();
                        if (mapView != null) {
                            int mapId = mapView.getId();
                            // このサーバーのワールド内のどこかに配置されているマップはloadAllItemInThisServerFramesメソッドで処理済み
                            if (serverImageInfo.containsKey(mapId) && !thisServerMapIds.contains(mapId)) {
                                Map<String, Object> imageInfo = serverImageInfo.get(mapId);
                                boolean isQr = (boolean) imageInfo.get("isqr");
                                Date date = (Date) imageInfo.get("date");
                                String imageUUID = (String) imageInfo.get("imuuid");
                                String ext = (String) imageInfo.get("ext");
                                try (Connection connection = (conn != null && !conn.isClosed()) ? conn : db.getConnection()) {
                                    String fullPath = getFullPath(connection, date, imageUUID, ext); // Connectionが必要ない場合はnullを渡す
                                    plugin.getLogger().log(Level.INFO, "Replacing image to the map(No.{0})...", new Object[] {mapId, fullPath});
                                        BufferedImage image = loadImage(fullPath);
                                        if (image != null) {
                                            // QRコードならリサイズしない
                                            image = isQr ? image : resizeImage(image, 128, 128);
                                            mapView.getRenderers().clear();
                                            mapView.addRenderer(new ImageMapRenderer(plugin, image, fullPath));
                                            mapMeta.setMapView(mapView);
                                            item.setItemMeta(mapMeta);
                                        } else {
                                            plugin.getLogger().log(Level.WARNING, "Failed to load image from path: {0}", fullPath);
                                        }
                                } catch (IOException e) {
                                    plugin.getLogger().log(Level.SEVERE, "Error loading image: {0}" , e);
                                } catch (SQLException | ClassNotFoundException e) {
                                    plugin.getLogger().log(Level.SEVERE, "Error getting full path: " + date + ", " + imageUUID + ", " + ext, e);
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    public void loadAllItemInThisServerFrames(Connection conn) throws SQLException, ClassNotFoundException {
        plugin.getLogger().info("Loading all item frames...");
        Map<Integer, Map<String, Object>> serverImageInfo = getThisServerImages(conn);
        for (World world : Bukkit.getWorlds()) {
            plugin.getLogger().log(Level.INFO, "Loading item frames in the world: {0}", world.getName());
            for (ItemFrame itemFrame : world.getEntitiesByClass(ItemFrame.class)) {
                ItemStack item = itemFrame.getItem();
                if (item.getType() == Material.FILLED_MAP) {
                    plugin.getLogger().info("Found a map item.");
                    MapMeta mapMeta = (MapMeta) item.getItemMeta();
                    if (mapMeta != null && mapMeta.hasMapView()) {
                        /*mapMeta.getPersistentDataContainer().getKeys().forEach(key -> {
                            plugin.getLogger().info(key.toString());
                        });*/
                        if (mapMeta.getPersistentDataContainer().has(new NamespacedKey(plugin, "custom_image"), PersistentDataType.STRING)) {
                            MapView mapView = mapMeta.getMapView();
                            if (mapView != null) {
                                int mapId = mapView.getId();
                                if (serverImageInfo.containsKey(mapId)) {
                                    thisServerMapIds.add(mapId);
                                    Map<String, Object> imageInfo = serverImageInfo.get(mapId);
                                    boolean isQr = (boolean) imageInfo.get("isqr");
                                    Date date = (Date) imageInfo.get("date");
                                    String imageUUID = (String) imageInfo.get("imuuid");
                                    String ext = (String) imageInfo.get("ext");
                                    String fullPath = getFullPath(conn, date, imageUUID, ext);
                                    plugin.getLogger().log(Level.INFO, "Replacing image to the map(No.{0})...", new Object[] {mapId, fullPath});
                                    try {
                                        BufferedImage image = loadImage(fullPath);
                                        if (image != null) {
                                            // QRコードならリサイズしない
                                            image = isQr ? image : resizeImage(image, 128, 128);
                                            mapView.getRenderers().clear();
                                            mapView.addRenderer(new ImageMapRenderer(plugin, image, fullPath));
                                        } 
                                    } catch (IOException e) {
                                        plugin.getLogger().log(Level.SEVERE, "マップId {0} の画像の読み込みに失敗しました: {1}", new Object[] {mapId, fullPath});
                                        plugin.getLogger().log(Level.SEVERE, "An IOException error occurred: {0}", e.getMessage());
                                        for (StackTraceElement element : e.getStackTrace()) {
                                            plugin.getLogger().severe(element.toString());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        plugin.getLogger().info("Loaded all item frames.");
    }

    public Map<Integer, Map<String, Object>> getImageMap(Connection conn) throws SQLException, ClassNotFoundException {
        Map<Integer, Map<String, Object>> imageInfo = new HashMap<>();
        String query = "SELECT * FROM images WHERE menu!=?;";
        PreparedStatement ps = conn.prepareStatement(query);
        ps.setBoolean(1, true);
        ResultSet rs = ps.executeQuery();
        int index = 0;
        while (rs.next()) {
            Map<String, Object> rowMap = new HashMap<>();
            int columnCount = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = rs.getMetaData().getColumnName(i);
                rowMap.put(columnName, rs.getObject(columnName));
            }
            imageInfo.computeIfAbsent(index, _ -> rowMap);
            index++;
        }
        return imageInfo;
    }

    public Map<Integer, Map<String, Object>> getThisServerImages(Connection conn) throws SQLException, ClassNotFoundException {
        Map<Integer, Map<String, Object>> serverImageInfo = new HashMap<>();
        String query = "SELECT * FROM images WHERE server=?;";
        PreparedStatement ps = conn.prepareStatement(query);
        ps.setString(1, serverName);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            Map<String, Object> rowMap = new HashMap<>();
            int mapId = rs.getInt("mapid");
            int columnCount = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = rs.getMetaData().getColumnName(i);
                if (!ImageMap.isGetColumnInfo.get()) {
                    imagesColumnsList.add(columnName);
                }
                if (!columnName.equals("mapid")) {
                    rowMap.put(columnName, rs.getObject(columnName));
                }
            }
            ImageMap.isGetColumnInfo.set(true);
            serverImageInfo.computeIfAbsent(mapId, _ -> rowMap);
        }
        return serverImageInfo;
    }

    public void giveMapToPlayer(Player player, int mapId) {
        AtomicBoolean found = new AtomicBoolean(false);
        for (World world : Bukkit.getWorlds()) {
            for (ItemFrame itemFrame : world.getEntitiesByClass(ItemFrame.class)) {
                ItemStack item = itemFrame.getItem();
                if (item.getType() == Material.FILLED_MAP) {
                    MapMeta mapMeta = (MapMeta) item.getItemMeta();
                    if (mapMeta != null && mapMeta.hasMapView()) {
                        MapView mapView = mapMeta.getMapView();
                        if (mapView != null && mapView.getId() == mapId) {
                            found.set(true);
                            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
                            mapMeta.setMapView(mapView);
                            mapItem.setItemMeta(mapMeta);
                            player.getInventory().addItem(mapItem);
                            player.sendMessage("画像マップを渡しました。");
                            return;
                        }
                    }
                }
            }
        }
        if (!found.get()) {
            player.sendMessage("地図ID " + mapId + " が見つかりません。");
        }
    }

    public String formatDate2(LocalDate date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd");
        return date.format(formatter);
    }

    private Map<String, Object> getImageInfoByOTP(Connection conn, String otp) throws SQLException, ClassNotFoundException {
        Map<String, Object> imageInfo = new HashMap<>();
        String query = "SELECT * FROM images WHERE otp=?;";
        PreparedStatement ps = conn.prepareStatement(query);
        ps.setString(1, otp);
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
            int columnCount = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = rs.getMetaData().getColumnName(i);
                imageInfo.put(columnName, rs.getObject(columnName));
            }
        }
        return imageInfo;
    }

    private boolean checkOTPIsCorrect(Connection conn, String code) throws SQLException, ClassNotFoundException {
        String query = "SELECT * FROM images WHERE otp=? LIMIT 1;";
        PreparedStatement ps = conn.prepareStatement(query);
        ps.setString(1, code);
        ResultSet rs = ps.executeQuery();
        return rs.next();
    }

    private BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        Image resultingImage = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
        BufferedImage outputImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = outputImage.createGraphics();
        g2d.drawImage(resultingImage, 0, 0, null);
        g2d.dispose();
        return outputImage;
    }
    
    @SuppressWarnings("unused")
    private String formatDate(LocalDate date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        return date.format(formatter);
    }

    private BufferedImage loadImage(String filePath) throws IOException {
        File imageFile = new File(filePath);
        if (!imageFile.exists()) {
            plugin.getLogger().log(Level.SEVERE, "Image file does not exist: {0}", filePath);
            return null;
        }
        return ImageIO.read(imageFile);
    }

    private String getFullPath(Connection conn, Date date, String imageUUID, String ext) throws SQLException, ClassNotFoundException {
        return getImageSaveFolder(conn) + "/" + date.toString().replace("-", "") + "/" + imageUUID + "." + ext;
    }

    private boolean isValidURL(String urlString) {
        try {
            URI uri = new URI(urlString);
            uri.toURL();
            return true;
        } catch (MalformedURLException | URISyntaxException e) {
            return false;
        }
    }
    
    private String getImageSaveFolder(Connection conn) throws SQLException, ClassNotFoundException {
        String query = "SELECT value FROM settings WHERE name=?;";
        PreparedStatement ps = conn.prepareStatement(query);
        ps.setString(1, "image_folder");
        ResultSet rs2 = ps.executeQuery();
        if (rs2.next()) {
            return rs2.getString("value");
        }
        return null;
    }

    private BufferedImage generateQRCodeImage(String text) throws WriterException, IOException {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        int width = 128;
        int height = 128;
        var bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, width, height);
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

    private void saveImageToFileSystem(Connection conn, BufferedImage image, String imageUUID, String ext) throws IOException, SQLException, ClassNotFoundException {
        if (getImageSaveFolder(conn) instanceof String folder) {
            Path dirPath = Paths.get(folder, LocalDate.now().toString().replace("-", ""));
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
            String fileName = imageUUID + "." + ext;
            Path filePath = dirPath.resolve(fileName);
            ImageIO.write(image, ext, filePath.toFile());
        } else {
            plugin.getLogger().severe("画像保存フォルダが見つかりません。");
        }
    }
}