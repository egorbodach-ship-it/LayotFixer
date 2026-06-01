package top.lordgamer.layoutfixer;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LayoutFixerPlugin extends JavaPlugin implements Listener {

    // Russian (ЙЦУКЕН) char -> QWERTY char on the same physical key.
    private static final Map<Character, Character> RU_TO_EN = new HashMap<>();
    static {
        String ruLower = "йцукенгшщзхъфывапролджэячсмитьбюё";
        String enLower = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`";
        String ruUpper = "ЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮЁ";
        String enUpper = "QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>~";
        for (int i = 0; i < ruLower.length(); i++) RU_TO_EN.put(ruLower.charAt(i), enLower.charAt(i));
        for (int i = 0; i < ruUpper.length(); i++) RU_TO_EN.put(ruUpper.charAt(i), enUpper.charAt(i));
    }

    private CommandMap commandMap;
    private final Set<String> dangerous = new HashSet<>();
    private Set<Character> chatPrefixes = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        this.commandMap = resolveCommandMap();
        getLogger().info("LayoutFixer v" + getDescription().getVersion() + " enabled.");
    }

    private void reloadSettings() {
        dangerous.clear();
        for (String s : getConfig().getStringList("dangerous-commands")) {
            dangerous.add(s.toLowerCase(Locale.ROOT));
        }
        chatPrefixes.clear();
        for (String s : getConfig().getStringList("command-prefixes")) {
            if (s != null && !s.isEmpty()) chatPrefixes.add(s.charAt(0));
        }
        if (chatPrefixes.isEmpty()) chatPrefixes.add('.');
    }

    // ---- Slash commands: "/..." ----
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreprocess(PlayerCommandPreprocessEvent event) {
        if (!getConfig().getBoolean("enabled", true)) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("layoutfixer.use")) return;

        String message = event.getMessage();
        if (message == null || message.length() < 2 || message.charAt(0) != '/') return;

        String body = message.substring(1);
        String resolved = resolve(player, body, false);
        if (resolved != null && !resolved.equals(body)) {
            event.setMessage("/" + resolved);
            notify(player, firstToken(resolved));
        }
    }

    // ---- Chat with a command-prefix ("." = slash in RU layout) ----
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!getConfig().getBoolean("enabled", true)) return;
        if (!getConfig().getBoolean("accept-dot-prefix", true)) return;
        final Player player = event.getPlayer();
        if (!player.hasPermission("layoutfixer.use")) return;

        String message = event.getMessage();
        if (message == null || message.length() < 2) return;
        if (!chatPrefixes.contains(message.charAt(0))) return;

        String body = message.substring(1).trim();
        if (body.isEmpty()) return;

        final String resolved = resolve(player, body, true);
        if (resolved == null) return; // not a confident command -> leave as normal chat

        event.setCancelled(true);
        final String label = firstToken(resolved);
        getServer().getScheduler().runTask(this, () -> {
            notify(player, label);
            player.performCommand(resolved);
        });
    }

    /**
     * Returns the corrected command line (without leading slash) or null.
     * When suggestOnAmbiguous is true and no confident match is found, sends
     * clickable suggestions and still returns null.
     */
    private String resolve(Player player, String body, boolean suggestOnAmbiguous) {
        int space = indexOfSpace(body);
        String label = (space == -1) ? body : body.substring(0, space);
        String rest = (space == -1) ? "" : body.substring(space);
        if (label.isEmpty()) return null;

        // 1) keyboard-layout fix on the label only (args untouched)
        String fixed = containsCyrillic(label) ? transliterate(label) : label;

        // 2) exact command after layout fix -> use it
        if (commandExists(fixed)) {
            return fixed + rest;
        }

        // 3) fuzzy / T9 correction
        if (!getConfig().getBoolean("fuzzy-correct", true)) {
            return null;
        }
        List<Scored> scored = rank(fixed, player);
        if (scored.isEmpty()) return null;

        Scored best = scored.get(0);
        int second = scored.size() > 1 ? scored.get(1).dist : Integer.MAX_VALUE;
        int len = fixed.length();
        int maxAuto = len <= 3 ? 1 : (len <= 6 ? 2 : 3);
        int margin = getConfig().getInt("ambiguity-margin", 1);
        boolean unambiguous = (second - best.dist) >= margin;

        if (best.dist <= maxAuto && unambiguous && !dangerous.contains(best.name)) {
            return best.name + rest; // confident auto-correct
        }

        if (suggestOnAmbiguous && getConfig().getBoolean("suggest", true)) {
            sendSuggestions(player, scored, rest);
        }
        return null;
    }

    private List<Scored> rank(String input, Player player) {
        String in = input.toLowerCase(Locale.ROOT);
        boolean requirePerm = getConfig().getBoolean("require-permission", true);
        List<Scored> out = new ArrayList<>();
        Map<String, Command> known = getKnownCommands();
        if (known == null) return out;
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Command> e : known.entrySet()) {
            String key = e.getKey();
            if (key == null || key.indexOf(':') >= 0) continue;
            Command c = e.getValue();
            if (c == null) continue;
            if (requirePerm) {
                try { if (!c.testPermissionSilent(player)) continue; } catch (Throwable ignored) {}
            }
            String low = key.toLowerCase(Locale.ROOT);
            if (!seen.add(low)) continue;
            int d = distance(in, low);
            if (low.startsWith(in) || in.startsWith(low)) d = Math.max(0, d - 1); // prefix bonus
            out.add(new Scored(low, d));
        }
        out.sort(Comparator.comparingInt(s -> s.dist));
        return out;
    }

    private void sendSuggestions(Player player, List<Scored> scored, String rest) {
        int max = Math.max(1, getConfig().getInt("max-suggestions", 3));
        int threshold = getConfig().getInt("suggest-threshold", 4);
        List<Scored> top = new ArrayList<>();
        for (Scored s : scored) {
            if (s.dist <= threshold) { top.add(s); if (top.size() >= max) break; }
        }
        if (top.isEmpty()) return;
        String prefix = color(getConfig().getString("suggest-message", "&7Может быть вы имели в виду: "));
        ComponentBuilder cb = new ComponentBuilder(prefix);
        boolean first = true;
        for (Scored s : top) {
            if (!first) cb.append(color("&7, "));
            first = false;
            TextComponent tc = new TextComponent(color("&a/" + s.name));
            tc.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + s.name + rest));
            cb.append(tc);
        }
        player.spigot().sendMessage(cb.create());
    }

    private void notify(Player player, String fixedLabel) {
        if (!getConfig().getBoolean("notify", true)) return;
        String msg = getConfig().getString("notify-message", "&7[&aLayoutFixer&7] Исправлено на: &f/%command%");
        if (msg == null || msg.isEmpty()) return;
        player.sendMessage(color(msg.replace("%command%", fixedLabel)));
    }

    // ---- helpers ----
    private static int indexOfSpace(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ' ') return i;
        return -1;
    }

    private static String firstToken(String s) {
        int i = indexOfSpace(s);
        return i == -1 ? s : s.substring(0, i);
    }

    private boolean containsCyrillic(String s) {
        for (int i = 0; i < s.length(); i++) if (RU_TO_EN.containsKey(s.charAt(i))) return true;
        return false;
    }

    private String transliterate(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            Character mapped = RU_TO_EN.get(s.charAt(i));
            sb.append(mapped != null ? mapped : s.charAt(i));
        }
        return sb.toString();
    }

    private boolean commandExists(String label) {
        if (commandMap == null) return false;
        return commandMap.getCommand(label.toLowerCase(Locale.ROOT)) != null;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static int distance(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[m];
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> getKnownCommands() {
        if (commandMap == null) return null;
        try {
            Method gk = commandMap.getClass().getMethod("getKnownCommands");
            return (Map<String, Command>) gk.invoke(commandMap);
        } catch (Throwable t) {
            return null;
        }
    }

    private CommandMap resolveCommandMap() {
        try {
            Method m = getServer().getClass().getMethod("getCommandMap");
            return (CommandMap) m.invoke(getServer());
        } catch (Throwable t) {
            getLogger().warning("Could not access CommandMap; fuzzy correction disabled.");
            return null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            reloadSettings();
            sender.sendMessage(color("&aLayoutFixer: конфиг перезагружен."));
            return true;
        }
        sender.sendMessage(color("&7/layoutfixer reload"));
        return true;
    }

    private static final class Scored {
        final String name;
        final int dist;
        Scored(String name, int dist) { this.name = name; this.dist = dist; }
    }
}
