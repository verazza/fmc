package spigot;

import java.util.Objects;

import org.bukkit.ChatColor;
import org.bukkit.scheduler.BukkitRunnable;
import org.slf4j.Logger;

import com.google.inject.Inject;
import com.google.inject.Provider;

public class AutoShutdown {

	private final common.Main plugin;
	private final Logger logger;
	private final Provider<SocketSwitch> sswProvider;
	private final ServerHomeDir shd;
    private BukkitRunnable task = null;
    
    @Inject
	public AutoShutdown (common.Main plugin, Logger logger, Provider<SocketSwitch> sswProvider, ServerHomeDir shd) {
		this.plugin = plugin;
		this.logger = logger;
		this.sswProvider = sswProvider;
		this.shd = shd;
	}
	
	public void startCheckForPlayers() {
		if (!plugin.getConfig().getBoolean("AutoStop.Mode", false)) {	
			logger.info("auto-stop is disabled.");
			return;
		}
		
		plugin.getServer().getConsoleSender().sendMessage(ChatColor.GREEN+"Auto-Stopが有効になりました。");
		
		long NO_PLAYER_THRESHOLD = plugin.getConfig().getInt("AutoStop.Interval",3) * 60 * 20;
		
		task = new BukkitRunnable() {
	        @Override
	        public void run() {
				SocketSwitch ssw = sswProvider.get();
	            if (plugin.getServer().getOnlinePlayers().isEmpty()) {
	            	String serverName = shd.getServerName();
	            	ssw.sendVelocityServer("プレイヤー不在のため、"+serverName+"サーバーを停止させます。");
	            	
	                plugin.getServer().broadcastMessage(ChatColor.RED+"プレイヤー不在のため、"+serverName+"サーバーを5秒後に停止します。");
	                countdownAndShutdown(5);
	            }
	        }
	    };
	    task.runTaskTimer(plugin, NO_PLAYER_THRESHOLD, NO_PLAYER_THRESHOLD);
	}

    public void countdownAndShutdown(int seconds) {
        new BukkitRunnable() {
            int countdown = seconds;

            @Override
            public void run() {
                if (countdown <= 0) {
                    plugin.getServer().broadcastMessage(ChatColor.RED + "サーバーを停止します。");
					SocketSwitch ssw = sswProvider.get();
					ssw.sendVelocityServer(shd.getServerName() + "サーバーが停止しました。");
                    plugin.getServer().shutdown();
                    cancel();
                    return;
                }

                plugin.getServer().broadcastMessage(String.valueOf(countdown));
                countdown--;
            }
        }.runTaskTimer(plugin, 0, 20);
    }
    
    public void stopCheckForPlayers() {
    	if (Objects.nonNull(task) && !task.isCancelled()) {
            task.cancel();
        }
    }
}
