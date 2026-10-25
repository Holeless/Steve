package com.steveeverything.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.steveeverything.SteveEverything;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-mob overrides from assets/steveeverything/mob_styles.json (a resource pack can replace or extend it).
 * Each key is an entity id; see the file itself for the options.
 */
final class MobStyles {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation FILE =
            ResourceLocation.fromNamespaceAndPath(SteveEverything.MOD_ID, "mob_styles.json");
    private static Map<String, SteveRemapper.Style> styles;

    private MobStyles() {}

    static void reset() {
        styles = null;
    }

    static SteveRemapper.Style get(ResourceManager resources, String entityId) {
        if (styles == null) styles = load(resources);
        return styles.getOrDefault(entityId, SteveRemapper.Style.DEFAULT);
    }

    private static Map<String, SteveRemapper.Style> load(ResourceManager resources) {
        Map<String, SteveRemapper.Style> out = new HashMap<>();
        // lowest priority pack first, so packs on top override the mod's own file
        for (Resource res : resources.getResourceStack(FILE)) {
            try (Reader in = res.openAsReader()) {
                JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    if (!e.getValue().isJsonObject()) continue;
                    out.put(e.getKey(), parse(e.getValue().getAsJsonObject()));
                }
            } catch (Exception ex) {
                LOGGER.warn("Could not read {}", FILE, ex);
            }
        }
        return out;
    }

    private static SteveRemapper.Style parse(JsonObject o) {
        boolean mini = "mini".equals(str(o, "head"));
        boolean front = "front".equals(str(o, "head"));
        int body = switch (str(o, "body")) {
            case "shirt_butt" -> 1;
            case "plain" -> 2;
            case "belly_pants" -> 3;
            default -> 0;
        };
        Map<String, SteveRemapper.Kind> parts = new LinkedHashMap<>();
        if (o.has("parts") && o.get("parts").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("parts").entrySet()) {
                String v = e.getValue().getAsString().toLowerCase(Locale.ROOT);
                try {
                    SteveRemapper.Kind k = v.equals("sleeve") ? SteveRemapper.Kind.ARM
                            : SteveRemapper.Kind.valueOf(v.toUpperCase(Locale.ROOT));
                    parts.put(e.getKey().toLowerCase(Locale.ROOT), k);
                } catch (IllegalArgumentException bad) {
                    LOGGER.warn("Unknown part type '{}' for '{}'", v, e.getKey());
                }
            }
        }
        return new SteveRemapper.Style(mini, body, parts, front);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
