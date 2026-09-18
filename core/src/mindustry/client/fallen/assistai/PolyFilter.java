package mindustry.client.fallen.assistai;

import arc.Core;
import arc.struct.ObjectSet;
import arc.util.Strings;
import mindustry.gen.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PolyFilter {
    public static final ObjectSet<String> whitelist = new ObjectSet<>();
    public static final ObjectSet<String> blacklist = new ObjectSet<>();

    private static final Pattern TAG_PATTERN = Pattern.compile("<([^>]+)>");

    public static int minLevel = Core.settings.getInt("poly-min-lvl", 0);
    public static boolean onlyWhitelist = Core.settings.getBool("poly-only-whitelist", false);
    public static boolean allowCustomLvl = Core.settings.getBool("poly-allow-custom-lvl", true);

    public static class PlayerInfo {
        public int level = 0;
        public boolean isCustomLevel = false;
        public String customTag = "";
        public boolean isAfk = false;
    }

    /** Уникальный ключ игрока (чистый ник без цветов) */
    public static String getKey(Player player) {
        if (player == null) return "";
        return Strings.stripColors(player.name).trim();
    }

    public static PlayerInfo parsePlayer(Player player) {
        PlayerInfo info = new PlayerInfo();
        if (player == null) return info;

        String stripped = Strings.stripColors(player.name);
        Matcher matcher = TAG_PATTERN.matcher(stripped);

        while (matcher.find()) {
            String content = matcher.group(1).trim();
            if (content.equalsIgnoreCase("AFK")) {
                info.isAfk = true;
            } else {
                try {
                    info.level = Integer.parseInt(content);
                } catch (NumberFormatException e) {
                    info.isCustomLevel = true;
                    info.customTag = content;
                    info.level = 50;
                }
            }
        }
        return info;
    }

    public static boolean canAssist(Player player) {
        if (player == null || player.isLocal()) return false;

        String key = getKey(player);

        // 1. Черный список
        if (blacklist.contains(key)) return false;

        // 2. Белый список
        if (whitelist.contains(key)) return true;

        // 3. Только белый список
        if (onlyWhitelist) return false;

        // 4. Проверка уровня
        PlayerInfo info = parsePlayer(player);

        if (info.isCustomLevel) {
            return allowCustomLvl;
        }

        return info.level >= minLevel;
    }
}