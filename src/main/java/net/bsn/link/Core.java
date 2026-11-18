package net.bsn.link;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The plugin's logic, independent of Bukkit so it can be tested on its own.
 * Everything here runs on async threads; anything that touches the game goes through {@link Host#sync}.
 */
final class Core {
    interface Host {
        /** Runs the task on the server thread and waits for the result. */
        <T> T sync(Callable<T> task) throws Exception;
        boolean isOnline(String name);
        boolean dispatch(String command);
        void tell(String name, String message);
        Snapshot snapshot(boolean withPlayers);
        void info(String msg);
        void warn(String msg);
    }

    static final class PlayerStat {
        final String name;
        final String uuid;
        final Map<String, String> stats = new LinkedHashMap<>();
        PlayerStat(String name, String uuid) { this.name = name; this.uuid = uuid; }
    }

    static final class Snapshot {
        int online;
        int max;
        String version = "";
        final List<PlayerStat> players = new ArrayList<>();
    }

    static final class Settings {
        boolean requireOnline = true;
        final Set<String> allowed = ConcurrentHashMap.newKeySet();
        String deliverMessage = "";
    }

    /** Remembers which orders were already executed, so a lost ack can never cause a double delivery. */
    static final class Ledger {
        private final Path file;
        private final Set<Long> ids = ConcurrentHashMap.newKeySet();

        Ledger(Path file) {
            this.file = file;
            try {
                if (Files.exists(file)) {
                    for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        try { ids.add(Long.parseLong(l.trim())); } catch (NumberFormatException ignored) { }
                    }
                }
            } catch (IOException ignored) { }
        }

        boolean has(long id) { return ids.contains(id); }

        void add(long id) throws IOException {
            if (ids.add(id)) {
                Files.write(file, (id + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }
    }

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.]{3,17}$");
    private static final int CHUNK = 100;

    private final Host host;
    private final Api api;
    private final Settings settings;
    private final Ledger ledger;

    Core(Host host, Api api, Settings settings, Ledger ledger) {
        this.host = host;
        this.api = api;
        this.settings = settings;
        this.ledger = ledger;
    }

    // ---------------------------------------------------------------- deliveries

    void pollOnce() throws Exception {
        Object list = api.get("/api/game/pending").get("orders");
        if (!(list instanceof List)) return;
        for (Object o : (List<?>) list) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            deliver(((Number) m.get("id")).longValue(), String.valueOf(m.get("code")), String.valueOf(m.get("player")), m.get("commands"));
        }
    }

    private void deliver(long id, String code, String player, Object rawCommands) throws Exception {
        if (ledger.has(id)) { // already ran earlier, only the ack got lost
            ack(id, true, "اتسلم قبل كده");
            return;
        }
        if (!NAME.matcher(player).matches()) { ack(id, false, "اسم لاعب غير صالح"); return; }
        if (settings.requireOnline && !host.sync(() -> host.isOnline(player))) return; // wait until he joins

        List<String> run = new ArrayList<>();
        if (rawCommands instanceof List) {
            for (Object c : (List<?>) rawCommands) {
                String cmd = String.valueOf(c).trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                cmd = cmd.replace("{player}", player).trim();
                if (cmd.isEmpty()) continue;
                String first = cmd.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                if (cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0 || !settings.allowed.contains(first)) {
                    host.warn("Order " + code + ": command '" + first + "' is not in allowed-commands (config.yml). Nothing was run.");
                    ack(id, false, "الأمر '" + first + "' مش مسموح في allowed-commands بتاعة الـ plugin، ومفيش حاجة اتنفذت");
                    return;
                }
                run.add(cmd);
            }
        }
        if (run.isEmpty()) { ack(id, true, "مفيش أوامر للتنفيذ"); return; }

        String failed = host.sync(() -> {
            for (String c : run) if (!host.dispatch(c)) return c;
            return null;
        });
        try { ledger.add(id); } catch (IOException e) { host.warn("Could not write delivered.log: " + e.getMessage()); }
        if (failed == null) {
            host.info("Delivered order " + code + " to " + player + " (" + run.size() + " command(s))");
            if (!settings.deliverMessage.isEmpty()) host.sync(() -> { host.tell(player, settings.deliverMessage); return null; });
            ack(id, true, "اتسلم تلقائيًا (" + run.size() + " أمر)");
        } else {
            host.warn("Order " + code + ": command failed: " + failed);
            ack(id, false, "الأمر ده فشل: " + failed + " (ممكن أوامر قبله اتنفذت، راجع في اللعبة)");
        }
    }

    private void ack(long id, boolean ok, String msg) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", id);
        b.put("ok", ok);
        b.put("message", msg);
        api.post("/api/game/ack", b);
    }

    // ---------------------------------------------------------------- heartbeat + stats

    void reportOnce(boolean withPlayers) throws Exception {
        Snapshot s = host.sync(() -> host.snapshot(withPlayers));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("online", s.online);
        body.put("max", s.max);
        body.put("version", s.version);
        if (!withPlayers || s.players.isEmpty()) {
            body.put("players", new ArrayList<>());
            api.post("/api/game/report", body);
            return;
        }
        for (int i = 0; i < s.players.size(); i += CHUNK) {
            List<Object> chunk = new ArrayList<>();
            for (PlayerStat p : s.players.subList(i, Math.min(i + CHUNK, s.players.size()))) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.name);
                pm.put("uuid", p.uuid);
                pm.put("stats", p.stats);
                chunk.add(pm);
            }
            body.put("players", chunk);
            api.post("/api/game/report", body);
        }
    }
}
