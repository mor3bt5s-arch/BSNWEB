package net.bsn.link;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** BSN website link: delivers approved store orders and reports player count + PlaceholderAPI stats. */
public final class BsnLink extends JavaPlugin implements Core.Host, CommandExecutor {
    private static final String[] STAT_KEYS = {"money", "kills", "deaths", "shards"};

    private interface Job { void run() throws Exception; }

    private volatile Core core;
    private volatile long lastStats = 0;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean reporting = new AtomicBoolean();
    private final Map<String, Long> lastWarn = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("bsnlink").setExecutor(this);
        start();
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
    }

    private void start() {
        getServer().getScheduler().cancelTasks(this);
        reloadConfig();
        FileConfiguration c = getConfig();
        String url = c.getString("api-url", "").trim();
        String key = c.getString("api-key", "").trim();
        if (url.isEmpty() || key.isEmpty() || url.contains("YOUR") || key.startsWith("PASTE")) {
            core = null;
            getLogger().warning("api-url / api-key are not set in plugins/BSNLink/config.yml. Set them, then run /bsnlink reload.");
            return;
        }
        Core.Settings s = new Core.Settings();
        s.requireOnline = c.getBoolean("require-online", true);
        s.deliverMessage = c.getString("deliver-message", "");
        for (String x : c.getStringList("allowed-commands")) s.allowed.add(x.trim().toLowerCase(Locale.ROOT));
        core = new Core(this, new Api(url, key), s, new Core.Ledger(getDataFolder().toPath().resolve("delivered.log")));

        long pollTicks = Math.max(5, c.getInt("poll-seconds", 10)) * 20L;
        long beatTicks = Math.max(15, c.getInt("heartbeat-seconds", 60)) * 20L;
        final long statsMs = Math.max(60, c.getInt("stats-seconds", 300)) * 1000L;
        lastStats = 0;
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> run(polling, "deliveries", () -> core.pollOnce()), 100L, pollTicks);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> run(reporting, "report", () -> {
            boolean withPlayers = System.currentTimeMillis() - lastStats >= statsMs;
            core.reportOnce(withPlayers);
            if (withPlayers) lastStats = System.currentTimeMillis();
        }), 40L, beatTicks);
        getLogger().info("BSNLink started (" + url + ")");
    }

    private void run(AtomicBoolean busy, String what, Job job) {
        if (core == null || !busy.compareAndSet(false, true)) return;
        try {
            job.run();
            lastWarn.remove(what);
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            Long last = lastWarn.get(what);
            if (last == null || now - last > 300000L) { // don't spam the console while the site is unreachable
                lastWarn.put(what, now);
                getLogger().warning(what + " failed: " + e.getMessage());
            }
        } finally {
            busy.set(false);
        }
    }

    // ---------------------------------------------------------------- Core.Host (server-thread side)

    @Override
    public <T> T sync(Callable<T> task) throws Exception {
        return getServer().getScheduler().callSyncMethod(this, task).get(15, TimeUnit.SECONDS);
    }

    @Override
    public boolean isOnline(String name) {
        return Bukkit.getPlayerExact(name) != null;
    }

    @Override
    public boolean dispatch(String command) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    @Override
    public void tell(String name, String message) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) p.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    @Override
    public Core.Snapshot snapshot(boolean withPlayers) {
        Core.Snapshot s = new Core.Snapshot();
        s.online = Bukkit.getOnlinePlayers().size();
        s.max = Bukkit.getMaxPlayers();
        String v = Bukkit.getBukkitVersion();
        s.version = v.contains("-") ? v.substring(0, v.indexOf('-')) : v;
        if (!withPlayers) return s;
        FileConfiguration c = getConfig();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Core.PlayerStat ps = new Core.PlayerStat(p.getName(), p.getUniqueId().toString());
            for (String k : STAT_KEYS) {
                String tpl = c.getString("stats." + k, "");
                if (!tpl.isEmpty()) ps.stats.put(k, resolve(p, tpl));
            }
            s.players.add(ps);
        }
        return s;
    }

    /** Resolves a PlaceholderAPI template for a player; null if it could not be resolved. */
    private String resolve(Player p, String template) {
        try {
            String r = PlaceholderAPI.setPlaceholders(p, template);
            if (r == null || r.contains("%")) return null; // unknown placeholder is returned untouched
            return ChatColor.stripColor(r).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void info(String msg) {
        getLogger().info(msg);
    }

    @Override
    public void warn(String msg) {
        getLogger().warning(msg);
    }

    // ---------------------------------------------------------------- /bsnlink

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("reload")) {
            start();
            sender.sendMessage("BSNLink: reloaded" + (core == null ? " (api-url / api-key not set yet)" : ""));
            return true;
        }
        if (sub.equals("test")) {
            final Core cur = core;
            if (cur == null) {
                sender.sendMessage("BSNLink: api-url / api-key are not set in config.yml");
                return true;
            }
            sender.sendMessage("BSNLink: testing the connection...");
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                String result;
                try {
                    cur.reportOnce(false);
                    result = "OK. Connected to the site and this server now shows as online.";
                } catch (Exception e) {
                    result = "FAILED: " + e.getMessage();
                }
                final String out = result;
                getServer().getScheduler().runTask(this, () -> sender.sendMessage("BSNLink: " + out));
            });
            return true;
        }
        sender.sendMessage("Usage: /bsnlink <reload|test>");
        return true;
    }
}
