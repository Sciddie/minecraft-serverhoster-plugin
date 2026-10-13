package de.aichele.worldporter;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One-time outer setup. Delete the plugin after done.marker exists. */
public final class WorldPorterPlugin extends JavaPlugin {
    @Override public void onEnable() {
        saveDefaultConfig();
        if (done()) { getLogger().info("Setup already finished (" + marker() + "). Remove WorldPorter."); return; }
        if (!getConfig().getBoolean("runOnEnable", true)) { getLogger().info("runOnEnable=false"); return; }
        execute();
        Bukkit.getScheduler().runTask(this, this::neuterLoadedLobby);
    }

    public String statusText() {
        return done() ? "erledigt " + marker() : "offen";
    }

    public String preview() { return run(true); }

    public String execute() { return run(false); }

    private String run(boolean dry) {
        reloadConfig();
        List<String> lines = new ArrayList<>();
        if (!dry && done()) return statusText();
        Path root = Paths.get("").toAbsolutePath().normalize();
        Path child = root.resolve(getConfig().getString("targetDir", "./child-server")).normalize();
        String level = getConfig().getString("levelName", "lobby_empty");
        if (dry) lines.add("would patch outer files, level-name=" + level);
        else {
            try { lines.add("patch " + OuterNeuter.patch(root, child, level)); }
            catch (IOException e) { lines.add("patch failed: " + e.getMessage()); }
        }
        Set<Path> loaded = new HashSet<>();
        for (World w : Bukkit.getWorlds()) loaded.add(w.getWorldFolder().toPath().toAbsolutePath().normalize());
        WorldMove.Result result;
        try {
            result = WorldMove.run(WorldMove.plan(root,
                    getConfig().getString("sourceDir", "."),
                    getConfig().getString("targetDir", "./child-server"),
                    getConfig().getString("direction", "spigot-to-vanilla"),
                    getConfig().getString("worlds.overworld", "world"),
                    getConfig().getString("worlds.nether", "world_nether"),
                    getConfig().getString("worlds.end", "world_the_end"),
                    getConfig().getString("targetLevelName", "world")), loaded, dry);
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        lines.addAll(result.lines());
        lines.add("ok=" + result.ok() + " skipped=" + result.skipped() + " failed=" + result.failed());
        if (!dry && result.finished()) {
            try {
                Files.createDirectories(getDataFolder().toPath());
                Files.writeString(marker(), new Date() + "\n");
                lines.add("marker " + marker());
            } catch (IOException e) { lines.add("marker failed: " + e.getMessage()); }
        } else if (!dry) {
            lines.add("kein marker: Spigot neu starten, dann erneut /worldporter run");
        }
        String text = String.join("\n", lines);
        getLogger().info(text);
        return text;
    }

    private void neuterLoadedLobby() {
        if (done()) return;
        Path root = Paths.get("").toAbsolutePath().normalize();
        String ow = getConfig().getString("worlds.overworld", "world");
        String ne = getConfig().getString("worlds.nether", "world_nether");
        String en = getConfig().getString("worlds.end", "world_the_end");
        for (World w : Bukkit.getWorlds()) {
            if (OuterNeuter.sourceWorld(w.getWorldFolder().toPath(), root, ow, ne, en)) continue;
            try { w.setSpawnFlags(false, false); } catch (Throwable ignored) {}
            for (String g : List.of("doMobSpawning", "spawn_mobs", "doPatrolSpawning", "doTraderSpawning", "doWardenSpawning", "doWeatherCycle", "doFireTick"))
                setRule(w, g, false);
            setRule(w, "spawnChunkRadius", 0);
            setRule(w, "spawn_chunk_radius", 0);
            setRule(w, "randomTickSpeed", 0);
            setRule(w, "random_tick_speed", 0);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setRule(World w, String name, Object v) {
        try {
            GameRule r = GameRule.getByName(name);
            if (r != null && r.getType().isInstance(v)) w.setGameRule(r, v);
        } catch (Throwable ignored) {}
    }

    private boolean done() { return Files.exists(marker()); }

    private Path marker() { return getDataFolder().toPath().resolve("done.marker"); }

    @Override public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("worldporter.admin")) { s.sendMessage("No permission."); return true; }
        if (a.length == 0) return false;
        String text = switch (a[0].toLowerCase(Locale.ROOT)) {
            case "status" -> statusText();
            case "plan", "dryrun" -> preview();
            case "run" -> execute();
            default -> null;
        };
        if (text == null) return false;
        for (String line : text.split("\n")) {
            getLogger().info(line);
            if (!(s instanceof ConsoleCommandSender)) s.sendMessage(line);
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length != 1) return List.of();
        return List.of("status", "plan", "run").stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
    }
}
