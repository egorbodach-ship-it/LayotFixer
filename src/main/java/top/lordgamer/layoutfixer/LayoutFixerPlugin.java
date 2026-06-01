package top.lordgamer.layoutfixer;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class LayoutFixerPlugin extends JavaPlugin implements Listener {

    // Maps each Russian (ЙЦУКЕН) character to the QWERTY character on the same physical key.
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        this.commandMap = resolveCommandMap();
        getLogger().info("LayoutFixer enabled.");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!getConfig().getBoolean("enabled", true)) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("layoutfixer.use")) return;

        String message = event.getMessage();
        if (message == null || message.length() < 2 || message.charAt(0) != '/') return;

        String body = message.substring(1);
        int space = body.indexOf(' ');
        String label = (space == -1) ? body : body.substring(0, space);
        String rest = (space == -1) ? "" : body.substring(space);

        // Only the command label is converted; arguments are left untouched
        // (so things like "/msg Steve привет" still work normally).
        if (!containsCyrillic(label)) return;

        String fixed = transliterate(label);
        if (fixed.equals(label)) return;

        if (getConfig().getBoolean("only-known-commands", true) && !commandExists(fixed)) return;

        event.setMessage("/" + fixed + rest);

        if (getConfig().getBoolean("notify", true)) {
            String msg = getConfig().getString("notify-message", "&7Команда исправлена: &f/%command%");
            if (msg != null && !msg.isEmpty()) {
                msg = msg.replace("%command%", fixed).replace('&', '\u00A7');
                player.sendMessage(msg);
            }
        }
    }

    private boolean containsCyrillic(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (RU_TO_EN.containsKey(s.charAt(i))) return true;
        }
        return false;
    }

    private String transliterate(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character mapped = RU_TO_EN.get(c);
            sb.append(mapped != null ? mapped : c);
        }
        return sb.toString();
    }

    private boolean commandExists(String label) {
        if (commandMap == null) return true; // fail open if we can't read the map
        return commandMap.getCommand(label.toLowerCase()) != null;
    }

    private CommandMap resolveCommandMap() {
        try {
            Method m = getServer().getClass().getMethod("getCommandMap");
            return (CommandMap) m.invoke(getServer());
        } catch (Throwable t) {
            getLogger().warning("Could not access CommandMap; 'only-known-commands' will be ignored.");
            return null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("\u00A7aLayoutFixer: конфиг перезагружен.");
            return true;
        }
        sender.sendMessage("\u00A77/layoutfixer reload");
        return true;
    }
}
