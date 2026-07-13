#!/usr/bin/env python3
"""ZoneWars security phase 1. Run from repository root."""
from pathlib import Path
import sys

root = Path.cwd()
java_path = root / "src/main/java/ru/zonewars/forge/ZoneWarsForge.java"
mods_path = root / "src/main/resources/META-INF/mods.toml"
workflow_path = root / ".github/workflows/build.yml"
if not java_path.is_file() or not mods_path.is_file():
    sys.exit("Запустите скрипт из корня stalzone-forge-1.20.1")

java = java_path.read_text(encoding="utf-8")
mods = mods_path.read_text(encoding="utf-8")

edits = [
("""    private final GuiMenus menus = new GuiMenus();
    private int secondTicker;
""", """    private final GuiMenus menus = new GuiMenus();
    private final Map<UUID, Long> actionCooldowns = new HashMap<>();
    private final Map<UUID, Long> stateRequestCooldowns = new HashMap<>();
    private int secondTicker;
"""),
("""        stats.loadTotals(storage.loadPlayerStats());
        storage.saveArena(matches.arena());
        storage.saveClans(clans.snapshot());
        storage.savePlayerStats(stats.totalsSnapshot());
        storage.ensureMatchHistory();
""", """        stats.loadTotals(storage.loadPlayerStats());
        // Never overwrite unreadable persistence files with defaults at startup.
        storage.ensureMatchHistory();
"""),
("""        MinecraftServer server = player.server;
        matches.leave(player); respawns.removePlayer(server, player.getUUID()); squads.leave(player.getUUID()); clientBridge.removePlayer(player);
""", """        MinecraftServer server = player.server;
        actionCooldowns.remove(player.getUUID());
        stateRequestCooldowns.remove(player.getUUID());
        matches.leave(player); respawns.removePlayer(server, player.getUUID()); squads.leave(player.getUUID()); clientBridge.removePlayer(player);
"""),
("""    private void handleClientAction(ServerPlayer player, String rawAction) {
        String action = rawAction == null ? "" : rawAction.trim();
        if (action.equalsIgnoreCase("request_state")) {
""", """    private void handleClientAction(ServerPlayer player, String rawAction) {
        String action = rawAction == null ? "" : rawAction.trim();
        if (action.isEmpty() || action.length() > 256 || !allowClientAction(player, action)) {
            return;
        }
        if (action.equalsIgnoreCase("request_state")) {
"""),
("""        if (action.toLowerCase(Locale.ROOT).startsWith("waypoint:")) {
            clientBridge.addWaypoint(player, action.substring("waypoint:".length()));
            clientBridge.sendState(player);
        }
    }

    private boolean allowDamage""", """        if (action.toLowerCase(Locale.ROOT).startsWith("waypoint:")) {
            clientBridge.addWaypoint(player, action.substring("waypoint:".length()));
            clientBridge.sendState(player);
        }
    }

    private boolean allowClientAction(ServerPlayer player, String action) {
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        boolean stateRequest = action.equalsIgnoreCase("request_state");
        Map<UUID, Long> limits = stateRequest ? stateRequestCooldowns : actionCooldowns;
        long allowedAt = limits.getOrDefault(id, 0L);
        if (now < allowedAt) return false;
        limits.put(id, now + (stateRequest ? 2_000L : 250L));
        return true;
    }

    private boolean allowDamage"""),
("""        if (!(entity instanceof ServerPlayer victim)) {
            return;
        }
        respawns.markDeath(victim.getUUID());
""", """        if (!(entity instanceof ServerPlayer victim)) {
            return;
        }
        if (!matches.isParticipant(victim.getUUID())) {
            return;
        }
        respawns.markDeath(victim.getUUID());
"""),
("""        if (target == null) {
            error(source, "Player not found.");
            return 0;
        }
        try {
            squads.invite(player.getUUID(), target.getUUID());
""", """        if (target == null) {
            error(source, "Player not found.");
            return 0;
        }
        Optional<TeamColor> inviterTeam = matches.teamOf(player.getUUID());
        Optional<TeamColor> targetTeam = matches.teamOf(target.getUUID());
        if (inviterTeam.isPresent() && (targetTeam.isEmpty() || inviterTeam.get() != targetTeam.get())) {
            error(source, "Target must be on your team.");
            return 0;
        }
        try {
            squads.invite(player.getUUID(), target.getUUID());
"""),
("""                if (team.isEmpty()) {
                    continue;
                }
                JsonObject object = new JsonObject();
""", """                if (team.isEmpty()) {
                    continue;
                }
                if (viewerTeam.isEmpty() || team.get() != viewerTeam.get()) {
                    continue;
                }
                JsonObject object = new JsonObject();
"""),
("""        private void respawn(ServerPlayer player) {
            awaitingRespawn.remove(player.getUUID());
            RespawnPoint point = resolve(player).orElse(null);
            if (point == null) {
                matches.teleportToBase(player);
                return;
            }
            long now = System.currentTimeMillis();
            if (point.availableAt() > now) {
                matches.teleportToBase(player);
                return;
            }
            matches.teleport(player, point.location());
            cooldownUntil.put(player.getUUID(), now + (point.kind() == RespawnKind.TENT ? 10_000L : 50_000L));
        }
""", """        private boolean respawn(ServerPlayer player) {
            if (!matches.isParticipant(player.getUUID()) || !awaitingRespawn.remove(player.getUUID())) {
                return false;
            }
            RespawnPoint point = resolve(player).orElse(null);
            if (point == null) {
                matches.teleportToBase(player);
                return true;
            }
            long now = System.currentTimeMillis();
            if (point.availableAt() > now) {
                matches.teleportToBase(player);
                return true;
            }
            matches.teleport(player, point.location());
            cooldownUntil.put(player.getUUID(), now + (point.kind() == RespawnKind.TENT ? 10_000L : 50_000L));
            return true;
        }
"""),
("""            try {
                Files.createDirectories(dir);
                try (Writer writer = Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8)) {
                    GSON.toJson(root, writer);
                }
            } catch (IOException exception) {
""", """            try {
                Files.createDirectories(dir);
                Path target = dir.resolve(name);
                Path temporary = dir.resolve(name + ".tmp");
                Path backup = dir.resolve(name + ".bak");
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    GSON.toJson(root, writer);
                }
                if (Files.isRegularFile(target)) {
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
""")
]

for number, (old, new) in enumerate(edits, 1):
    count = java.count(old)
    if count != 1:
        sys.exit(f"Проверка #{number} не пройдена: ожидалось 1 совпадение, найдено {count}. Файлы не изменены.")
    java = java.replace(old, new, 1)

for old, new in {
    'loaderVersion="[47,)"': 'loaderVersion="[47,48)"',
    'versionRange="[47,)"': 'versionRange="[47,48)"',
    'versionRange="[1.20.1,1.21)"': 'versionRange="[1.20.1]"',
    'versionRange="[1.1.8,)"': 'versionRange="[1.1.8,1.2)"',
}.items():
    if mods.count(old) != 1:
        sys.exit(f"mods.toml не соответствует проверенной версии: {old}")
    mods = mods.replace(old, new, 1)

workflow = """name: Build
on:
  push:
    branches: [main]
  pull_request:
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle
      - run: chmod +x gradlew
      - run: ./gradlew clean build --stacktrace
      - uses: actions/upload-artifact@v4
        if: success()
        with:
          name: zonewars-jar
          path: build/libs/*.jar
          if-no-files-found: error
"""

java_path.write_text(java, encoding="utf-8")
mods_path.write_text(mods, encoding="utf-8")
workflow_path.parent.mkdir(parents=True, exist_ok=True)
workflow_path.write_text(workflow, encoding="utf-8")
print("Готово. Запустите: gradlew.bat clean build")
