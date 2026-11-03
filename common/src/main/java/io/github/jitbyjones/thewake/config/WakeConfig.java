package io.github.jitbyjones.thewake.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jitbyjones.thewake.TheWake;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain JSON config shared by both loaders: {@code config/thewake.json}.
 * Every option is documented in the file itself through sibling {@code "//name"} keys.
 */
public final class WakeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static WakeConfig instance = new WakeConfig();

    // ---- pacing -------------------------------------------------------------------------
    public double pace = 1.0;
    public int quietNights = 1;

    // ---- lapse sources ------------------------------------------------------------------
    public boolean pauseEvents = true;
    public boolean afkEvents = true;
    public int afkSeconds = 150;
    public boolean hitchEvents = true;

    // ---- escalation ---------------------------------------------------------------------
    public boolean blinkEvents = true;
    public boolean stolenMornings = true;
    public boolean worldEdits = true;
    public boolean finale = true;
    public boolean finaleOnServers = false;

    // ---- presentation -------------------------------------------------------------------
    public boolean nightSkyShader = true;
    public float soundVolume = 1.0f;

    // ---- scope --------------------------------------------------------------------------
    public List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld"));

    private static final Map<String, String> HELP = new LinkedHashMap<>();

    static {
        HELP.put("pace", "Multiplier for how fast the Wake learns you. 2.0 = twice as fast, 0.5 = half.");
        HELP.put("quietNights", "Nights you can sleep through before anything at all happens.");
        HELP.put("pauseEvents", "Singleplayer: pauses over two minutes count as lost time, and once, something stands behind the menu.");
        HELP.put("afkEvents", "Standing still without input counts as lost time.");
        HELP.put("afkSeconds", "Seconds without input before a player counts as absent.");
        HELP.put("hitchEvents", "Real frame stalls over a second count as lost time (capped per day). Nothing is faked.");
        HELP.put("blinkEvents", "Late game: nights you stayed awake for are collected later as short blackouts in daylight.");
        HELP.put("stolenMornings", "Late game: some mornings you wake where it went instead of in bed. Never in hardcore.");
        HELP.put("worldEdits", "Act I chores near your bed: furnace output moved to a chest, crops ripened, doors closed, a torch placed. Nothing is ever removed.");
        HELP.put("finale", "Enable the Stopped, the finale.");
        HELP.put("finaleOnServers", "Allow the finale on multiplayer servers (per-player client-side freeze). Off: server players plateau before it.");
        HELP.put("nightSkyShader", "Client: render the Wake with its night-sky shader. Turn off if a shader pack breaks it.");
        HELP.put("soundVolume", "Client: volume multiplier for this mod's own sounds (0.0 - 1.0).");
        HELP.put("dimensions", "Dimensions the Wake can reach.");
    }

    public static WakeConfig get() {
        return instance;
    }

    public static void load(Path configDir) {
        Path file = configDir.resolve(TheWake.MOD_ID + ".json");
        WakeConfig loaded = new WakeConfig();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement json = JsonParser.parseReader(reader);
                WakeConfig parsed = GSON.fromJson(json, WakeConfig.class);
                if (parsed != null) {
                    loaded = parsed;
                }
            } catch (Exception e) {
                TheWake.LOG.warn("Could not read {}, using defaults: {}", file, e.toString());
            }
        }
        loaded.sanitize();
        instance = loaded;
        save(file, loaded);
    }

    private void sanitize() {
        if (!(pace > 0.0) || pace > 20.0) pace = 1.0;
        quietNights = Math.max(0, Math.min(quietNights, 100));
        afkSeconds = Math.max(30, Math.min(afkSeconds, 3600));
        soundVolume = Math.max(0.0f, Math.min(soundVolume, 1.0f));
        if (dimensions == null || dimensions.isEmpty()) {
            dimensions = new ArrayList<>(List.of("minecraft:overworld"));
        }
    }

    private static void save(Path file, WakeConfig config) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject values = GSON.toJsonTree(config).getAsJsonObject();
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                String help = HELP.get(entry.getKey());
                if (help != null) {
                    out.addProperty("//" + entry.getKey(), help);
                }
                out.add(entry.getKey(), entry.getValue());
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(out, writer);
            }
        } catch (IOException e) {
            TheWake.LOG.warn("Could not write {}: {}", file, e.toString());
        }
    }

    public boolean allowsDimension(String dimensionId) {
        return dimensions.contains(dimensionId);
    }
}
