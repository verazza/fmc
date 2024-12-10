package keyp.forev.fmc.spigot.cmd.sub.teleport;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.google.inject.Inject;

import keyp.forev.fmc.common.settings.PermSettings;
import keyp.forev.fmc.common.util.JavaUtils;
import keyp.forev.fmc.spigot.server.textcomponent.TCUtils;
import keyp.forev.fmc.spigot.server.textcomponent.TCUtils2;
import keyp.forev.fmc.spigot.util.RunnableTaskUtil;
import keyp.forev.fmc.spigot.util.interfaces.MessageRunnable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.md_5.bungee.api.ChatColor;
import keyp.forev.fmc.common.server.Luckperms;
import keyp.forev.fmc.common.server.interfaces.ServerHomeDir;
import keyp.forev.fmc.common.database.Database;
import org.slf4j.Logger;

public class RegisterTeleportPoint implements TabExecutor {
    private final Logger logger;
    private final Database db;
    private final Luckperms lp;
    private final RunnableTaskUtil rt;
    private final String thisServerName;
    @Inject
    public RegisterTeleportPoint(Logger logger, Database db, Luckperms lp, RunnableTaskUtil rt, ServerHomeDir shd) {
        this.logger = logger;
        this.db = db;
        this.lp = lp;
        this.rt = rt;
        this.thisServerName = shd.getServerName();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            String playerName = player.getName();
            String playerUUID = player.getUniqueId().toString();
            if (!lp.hasPermission(playerName, PermSettings.TELEPORT_REGISTER_POINT.get())) {
                player.sendMessage(ChatColor.RED + "権限がありません。");
                return true;
            }

            if (rt.checkIsOtherInputMode(player, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get())) {
                player.sendMessage(ChatColor.RED + "他でインプット中のため処理を続行できません。");
                return true;
            }

            Location loc = player.getLocation();
            if (loc == null) {
                player.sendMessage(ChatColor.RED + "座標の取得に失敗しました。");
                return true;
            }

            final double x = loc.getX();
            final double y = loc.getY();
            final double z = loc.getZ();
            final float yaw = loc.getYaw();
            final float pitch = loc.getPitch();

            if (loc.getWorld() instanceof World) {
                World playerWorld = loc.getWorld();
                final String worldName = playerWorld.getName();
                Component titleOrComment = Component.text("タイトル:コメント")
                .color(NamedTextColor.GOLD)
                .decorate(
                    TextDecoration.BOLD,
                    TextDecoration.ITALIC,
                    TextDecoration.UNDERLINED)
                .hoverEvent(HoverEvent.showText(Component.text("クリックして入力")))
                .clickEvent(ClickEvent.suggestCommand("タイトル:コメント"));

                Component example = Component.text("(例)")
                    .appendSpace()
                    .append(Component.text("おうち:いえにかえれるよ。"))
                    .color(NamedTextColor.GRAY);

                Component note = Component.text("※タイトルとコメントは「:」で区切ってください。")
                    .color(NamedTextColor.GRAY);
                
                Component note2 = Component.text("※「:」は全角でも可")
                    .color(NamedTextColor.GRAY);

                final double roundX = JavaUtils.roundToFirstDecimalPlace(x);
                final double roundY = JavaUtils.roundToFirstDecimalPlace(y);
                final double roundZ = JavaUtils.roundToFirstDecimalPlace(z);

                Component currentCoord = Component.text("現在地: " + worldName + "(" + roundX + ", " + roundY + ", " + roundZ + ")")
                    .color(NamedTextColor.GOLD)
                    .decorate(
                        TextDecoration.BOLD,
                        TextDecoration.UNDERLINED);

                TextComponent messages = Component.text()
                    .append(currentCoord)
                    .appendNewline()
                    .append(Component.text("現在地をテレポートポイントに登録しますか？"))
                    .appendNewline()
                    .append(Component.text("登録する場合は"))
                    .append(titleOrComment)
                    .append(Component.text("を入力してください。"))
                    .appendNewline()
                    .append(note)
                    .appendNewline()
                    .append(note2)
                    .appendNewline()
                    .append(example)
                    .appendNewline()
                    .appendNewline()
                    .append(Component.text("処理を中断する場合は"))
                    .append(TCUtils.ZERO.get())
                    .append(Component.text("と入力してください。"))
                    .appendNewline()
                    .append(TCUtils.INPUT_MODE.get())
                    .build();

                player.sendMessage(messages);
                
                Map<String, MessageRunnable> playerActions = new HashMap<>();
                playerActions.put(RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get(), (input) -> {
                    player.sendMessage(TCUtils2.getResponseComponent(input));
                    switch (input) {
                        case "0" -> {
                            rt.removeCancelTaskRunnable(player, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get());
                            Component message = Component.text("処理を中断しました。")
                                .color(NamedTextColor.RED)
                                .decorate(TextDecoration.BOLD);

                            player.sendMessage(message);
                        }
                        default -> {
                            // 「:」もしくは全角の「：」が含まれてるかどうか
                            if (input.contains(":") || input.contains("：")) {
                                // 「:」もしくは全角の「：」が複数ある場合は処理を中断
                                if (input.split("[:：]").length > 2) {
                                    Component errorMessage = Component.text("コメントの開始位置が決定できません。")
                                        .appendNewline()
                                        .append(Component.text("「:」もしくは全角の「：」は1つだけ入力してください。"))
                                        .color(NamedTextColor.RED);

                                    player.sendMessage(errorMessage);

                                    rt.extendTask(player, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get());
                                    return;
                                }

                                String[] titleAndComment = input.split("[:：]", 2);
                                String title = titleAndComment[0];
                                String comment = titleAndComment[1];
                                try {
                                    rt.removeCancelTaskRunnable(player, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get());
                                    
                                    TextComponent note3 = Component.text()
                                        .append(Component.text("プライベート")
                                            .decorate(TextDecoration.UNDERLINED))
                                        .append(Component.text("は自分だけが飛べるポイントを"))
                                        .appendNewline()
                                        .append(Component.text("パブリック")
                                            .decorate(TextDecoration.UNDERLINED))
                                        .append(Component.text("は全員が共有で飛べるポイントを指します。"))
                                        .color(NamedTextColor.GRAY)
                                        .decorate(TextDecoration.ITALIC)
                                        .build();

                                    TextComponent message = Component.text()
                                        .append(Component.text("このポイントを"))
                                        .append(Component.text("パブリック")
                                            .decorate(TextDecoration.UNDERLINED))
                                        .append(Component.text("にする場合は"))
                                        .append(TCUtils.ONE.get())
                                        .append(Component.text("と入力してください。"))
                                        .appendNewline()
                                        .append(Component.text("プライベート")
                                            .decorate(TextDecoration.UNDERLINED))
                                        .append(Component.text("にする場合は"))
                                        .append(TCUtils.TWO.get())
                                        .append(Component.text("と入力してください。"))
                                        .appendNewline()
                                        .append(note3)
                                        .appendNewline()
                                        .append(Component.text("処理を中断する場合は"))
                                        .append(TCUtils.ZERO.get())
                                        .append(Component.text("と入力してください。"))
                                        .appendNewline()
                                        .append(TCUtils.INPUT_MODE.get())
                                        .build();

                                    player.sendMessage(message);

                                    Map<String, MessageRunnable> playerActions2 = new HashMap<>();
                                    playerActions2.put(RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get(), (input2) -> {
                                        player.sendMessage(TCUtils2.getResponseComponent(input2));
                                        switch (input2) {
                                            case "0" -> {
                                                rt.removeCancelTaskRunnable(player, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get());
                                                Component message2 = Component.text("処理を中断しました。")
                                                    .color(NamedTextColor.RED)
                                                    .decorate(TextDecoration.BOLD);
                                                    
                                                player.sendMessage(message2);
                                                return;
                                            }
                                            case "1", "2" -> {
                                                //boolean isPublic = input2.equals("1");
                                                final String type;
                                                switch (input2) {
                                                    case "1" -> type = "public";
                                                    case "2" -> type = "private";
                                                    default -> {
                                                        rt.removeCancelTaskRunnable(player, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get());
                                                        player.sendMessage(ChatColor.RED + "処理中にエラーが発生しました。");
                                                        throw new IllegalArgumentException("Invalid input: " + input2);
                                                    }
                                                }
                                                try {
                                                    rt.removeCancelTaskRunnable(player, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get());
                                                    try (Connection conn = db.getConnection()) {
                                                        db.updateLog(conn, "INSERT INTO tp_points (name, uuid, title, comment, x, y, z, yaw, pitch, world, server, type, uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new Object[] {playerName, playerUUID, title, comment, x, y, z, yaw, pitch, worldName, thisServerName, type, playerUUID});

                                                        Component message3 = Component.text("テレポートポイントを登録しました。")
                                                            .color(NamedTextColor.GREEN)
                                                            .decorate(TextDecoration.BOLD);

                                                        Component here = Component.text("ココ")
                                                            .clickEvent(ClickEvent.runCommand("/fmc menu tp point " + type))
                                                            .hoverEvent(HoverEvent.showText(Component.text("クリックしてテレポート")))
                                                            .color(NamedTextColor.GOLD)
                                                            .decorate(
                                                                TextDecoration.BOLD,
                                                                TextDecoration.UNDERLINED);
                                                        
                                                        Component message4 = Component.text("をクリックすると、確認できるよ！")
                                                            .color(NamedTextColor.GOLD)
                                                            .decorate(TextDecoration.BOLD);
                                                        
                                                        Component content = Component.text("※メニューが開きます。")
                                                            .color(NamedTextColor.GRAY)
                                                            .decorate(TextDecoration.ITALIC);
                                                        
                                                        TextComponent lastMessages = Component.text()
                                                            .append(message3)
                                                            .appendNewline()
                                                            .append(here)
                                                            .append(message4)
                                                            .appendNewline()
                                                            .append(content)
                                                            .build();
                                                    
                                                        player.sendMessage(lastMessages);
                                                    } catch (SQLException | ClassNotFoundException e) {
                                                        player.sendMessage(ChatColor.RED + "座標の保存に失敗しました。");
                                                        logger.error("A SQLException | ClassNotFoundException error occurred: " + e.getMessage());
                                                        for (StackTraceElement element : e.getStackTrace()) {
                                                            logger.error(element.toString());
                                                        }
                                                    }
                                                } catch (Exception e) {
                                                    player.sendMessage("処理中にエラーが発生しました。");
                                                }
                                            }
                                            default -> {
                                                Component errorMessage2 = Component.text("無効な入力です。")
                                                    .color(NamedTextColor.RED);
                                                player.sendMessage(errorMessage2);
                                                rt.extendTask(player, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get());
                                            }
                                        }
                                    });
                                    rt.addTaskRunnable(player, playerActions2, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get());
                                } catch (Exception e) {
                                    player.sendMessage("処理中にエラーが発生しました。");
                                }
                            } else {
                                Component errorMessage = Component.text("無効な入力です。")
                                    .appendNewline()
                                    .append(Component.text("タイトル名とコメントは「:」で区切ってください。"))
                                    .color(NamedTextColor.RED);
                                
                                player.sendMessage(errorMessage);

                                rt.extendTask(player, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get());
                            }
                        }
                    }
                });
                rt.addTaskRunnable(player, playerActions, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get());
            } else {
                player.sendMessage(ChatColor.RED + "ワールドの取得に失敗しました。");
                rt.removeCancelTaskRunnable(player, RunnableTaskUtil.Key.TELEPORT_REGISTER_POINT.get());
                throw new NullPointerException("World is null.");
            }
        } else {
            Component errorMessage = Component.text("プレイヤーのみが実行できます。")
                .color(NamedTextColor.RED);
            sender.sendMessage(errorMessage);
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
