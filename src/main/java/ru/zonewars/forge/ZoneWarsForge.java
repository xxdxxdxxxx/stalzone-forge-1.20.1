package ru.zonewars.forge;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.lang.reflect.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

@Mod(ZoneWarsForge.MOD_ID)
public final class ZoneWarsForge {
    public static final String MOD_ID = "zonewars";
    static ZoneWarsForge INSTANCE;
    private static final String PREFIX = "[ZoneWars] ";
    private static final String DEFAULT_KIT = "ak101";
    private static final Map<String, GunKit> GUN_KITS = gunKits();
    private static final Map<String, ShopItem> SHOP_ITEMS = shopItems();

    private final PersistentStore storage = new PersistentStore();
    private final EconomyService economy = new EconomyService(1200, 5, 150, 100);
    private final SquadService squads = new SquadService();
    private final ClanService clans = new ClanService(storage);
    private final MatchStatsService stats = new MatchStatsService(clans, storage);
    private final ForgeMatchService matches = new ForgeMatchService(economy, stats);
    private final RespawnService respawns = new RespawnService(matches, squads);
    private final ForgeClientBridge clientBridge = new ForgeClientBridge(matches, squads, stats, respawns);
    private final GuiMenus menus = new GuiMenus();
    private final Map<UUID, Long> actionCooldowns = new HashMap<>();
    private final Map<UUID, Long> stateRequestCooldowns = new HashMap<>();
    private int secondTicker;
    private int stateTicker;

    public ZoneWarsForge() {
        INSTANCE = this;
        storage.open();
        storage.loadArena().ifPresent(matches::loadArena);
        clans.load(storage.loadClans());
        stats.loadTotals(storage.loadPlayerStats());
        // Never overwrite unreadable persistence files with defaults at startup.
        storage.ensureMatchHistory();
        ZoneWarsNetwork.register(this::handleClientAction);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onLogout);
        MinecraftForge.EVENT_BUS.addListener(this::onRespawn);
        MinecraftForge.EVENT_BUS.addListener(this::onLivingAttack);
        MinecraftForge.EVENT_BUS.addListener(this::onLivingHurt);
        MinecraftForge.EVENT_BUS.addListener(this::onLivingDeath);
        MinecraftForge.EVENT_BUS.addListener(this::onLeftClickBlock);
        TaczEvents.register(this::onTaczShoot, this::onTaczHurtPre, this::onTaczHurtPost, this::onTaczKill);
    }

    private void registerCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
            dispatcher.register(Commands.literal("zw")
                .then(Commands.literal("join")
                    .then(Commands.literal("red").executes(context -> join(context.getSource(), TeamColor.RED)))
                    .then(Commands.literal("blue").executes(context -> join(context.getSource(), TeamColor.BLUE)))
                    .then(Commands.literal("auto").executes(context -> joinAuto(context.getSource())))
                    .then(Commands.argument("team", StringArgumentType.word()).executes(context ->
                        joinNamed(context.getSource(), StringArgumentType.getString(context, "team")))))
                .then(Commands.literal("leave").executes(context -> leave(context.getSource())))
                .then(Commands.literal("start").requires(source -> source.hasPermission(2)).executes(context -> start(context.getSource())))
                .then(Commands.literal("stop").requires(source -> source.hasPermission(2)).executes(context -> stop(context.getSource())))
                .then(Commands.literal("state").executes(context -> state(context.getSource())))
                .then(Commands.literal("balance").executes(context -> balance(context.getSource())))
                .then(Commands.literal("stats").executes(context -> playerStats(context.getSource())))
                .then(Commands.literal("shop").executes(context -> shop(context.getSource())))
                .then(Commands.literal("buy")
                    .then(Commands.argument("item", StringArgumentType.word()).executes(context ->
                        buy(context.getSource(), StringArgumentType.getString(context, "item")))))
                .then(Commands.literal("kit")
                    .requires(source -> source.hasPermission(2))
                    .executes(context -> kit(context.getSource(), DEFAULT_KIT))
                    .then(Commands.argument("name", StringArgumentType.word()).executes(context ->
                        kit(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("placetent").executes(context -> placeTent(context.getSource(), true)))
                .then(Commands.literal("placeoutpost").executes(context -> placeOutpost(context.getSource(), true)))
                .then(Commands.literal("respawn")
                    .executes(context -> respawnMenu(context.getSource()))
                    .then(Commands.literal("base").executes(context -> selectRespawn(context.getSource(), RespawnKind.BASE)))
                    .then(Commands.literal("tent").executes(context -> selectRespawn(context.getSource(), RespawnKind.TENT)))
                    .then(Commands.literal("outpost").executes(context -> selectRespawn(context.getSource(), RespawnKind.OUTPOST)))
                    .then(Commands.literal("confirm").executes(context -> confirmRespawn(context.getSource()))))
                .then(Commands.literal("validatekits").requires(source -> source.hasPermission(2)).executes(context -> validateKits(context.getSource())))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2)).executes(context -> reload(context.getSource()))));

            dispatcher.register(Commands.literal("squad")
                .executes(context -> squadMenu(context.getSource()))
                .then(Commands.literal("menu").executes(context -> squadMenu(context.getSource())))
                .then(Commands.literal("create").executes(context -> squadCreate(context.getSource())))
                .then(Commands.literal("invite")
                    .then(Commands.argument("player", StringArgumentType.word()).executes(context ->
                        squadInvite(context.getSource(), StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("join").executes(context -> squadJoin(context.getSource())))
                .then(Commands.literal("leave").executes(context -> squadLeave(context.getSource())))
                .then(Commands.literal("info").executes(context -> squadInfo(context.getSource()))));

            dispatcher.register(Commands.literal("sc")
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(context ->
                    squadChat(context.getSource(), StringArgumentType.getString(context, "message")))));

            dispatcher.register(Commands.literal("clan")
                .executes(context -> clanInfo(context.getSource()))
                .then(Commands.literal("create")
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .then(Commands.argument("color", StringArgumentType.word()).executes(context ->
                            clanCreate(context.getSource(), StringArgumentType.getString(context, "tag"), StringArgumentType.getString(context, "color"))))))
                .then(Commands.literal("join")
                    .then(Commands.argument("tag", StringArgumentType.word()).executes(context ->
                        clanJoin(context.getSource(), StringArgumentType.getString(context, "tag")))))
                .then(Commands.literal("leave").executes(context -> clanLeave(context.getSource())))
                .then(Commands.literal("stats").executes(context -> clanStats(context.getSource())))
                .then(Commands.literal("info").executes(context -> clanInfo(context.getSource()))));

            dispatcher.register(Commands.literal("cc")
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(context ->
                    clanChat(context.getSource(), StringArgumentType.getString(context, "message")))));

            dispatcher.register(Commands.literal("zwa")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("set")
                    .then(Commands.literal("redspawn").executes(context -> arenaSetSpawn(context.getSource(), TeamColor.RED)))
                    .then(Commands.literal("bluespawn").executes(context -> arenaSetSpawn(context.getSource(), TeamColor.BLUE))))
                .then(Commands.literal("point")
                    .then(Commands.literal("add")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("radius", StringArgumentType.word()).executes(context ->
                                    arenaAddPoint(context.getSource(), StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "name"), StringArgumentType.getString(context, "radius"))))))))
                .then(Commands.literal("shop")
                    .then(Commands.literal("add").executes(context -> arenaAddShop(context.getSource()))))
                .then(Commands.literal("capturedebug").executes(context -> captureDebug(context.getSource())))
                .then(Commands.literal("state").executes(context -> state(context.getSource()))));
    }

    private void onServerStarted(ServerStartedEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels())
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, event.getServer());
    }
    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        matches.fastTick(server, 0.05); respawns.tick();
        if (++secondTicker >= 20) { secondTicker = 0; matches.tick(server); }
        if (++stateTicker >= 10) { stateTicker = 0; clientBridge.broadcastState(server); }
    }
    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.server;
        actionCooldowns.remove(player.getUUID());
        stateRequestCooldowns.remove(player.getUUID());
        matches.leave(player); respawns.removePlayer(server, player.getUUID()); squads.leave(player.getUUID()); clientBridge.removePlayer(player);
    }
    private void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) { respawns.respawn(player); clientBridge.sendState(player); }
    }
    private void onLivingAttack(LivingAttackEvent event) {
        if (!allowDamage(event.getEntity(), event.getSource(), event.getAmount())) event.setCanceled(true);
    }
    private void onLivingHurt(LivingHurtEvent event) {
        if (!event.isCanceled()) afterDamage(event.getEntity(), event.getSource(), event.getAmount(), event.getAmount(), false);
    }
    private void onLivingDeath(LivingDeathEvent event) { afterDeath(event.getEntity(), event.getSource()); }
    private void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        RespawnService.DamageResult result = respawns.damageObject(player, level, event.getPos(), 25);
        if (result != RespawnService.DamageResult.NOT_OBJECT) { event.setCanceled(true); clientBridge.broadcastState(player.server); }
    }

    private void handleClientAction(ServerPlayer player, String rawAction) {
        String action = rawAction == null ? "" : rawAction.trim();
        if (action.isEmpty() || action.length() > 256 || !allowClientAction(player, action)) {
            return;
        }
        if (action.equalsIgnoreCase("request_state")) {
            clientBridge.sendState(player);
            return;
        }
        if (action.equalsIgnoreCase("squad:menu")) {
            menus.openSquadMain(player);
            return;
        }
        if (action.equalsIgnoreCase("respawn:confirm")) {
            respawns.respawn(player);
            clientBridge.sendState(player);
            return;
        }
        if (action.toLowerCase(Locale.ROOT).startsWith("respawn:")) {
            respawns.select(player.getUUID(), RespawnKind.parse(action.substring("respawn:".length())));
            clientBridge.sendState(player);
            return;
        }
        if (action.toLowerCase(Locale.ROOT).startsWith("ping:")) {
            clientBridge.addMarker(player, action.substring("ping:".length()));
            clientBridge.sendState(player);
            return;
        }
        if (action.toLowerCase(Locale.ROOT).startsWith("waypoint:")) {
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

    private boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer victim)) {
            return true;
        }
        if (source.getEntity() instanceof ServerPlayer attacker && matches.isFriendly(attacker, victim)) {
            return false;
        }
        return true;
    }

    private void afterDamage(LivingEntity entity, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
        if (blocked || !(entity instanceof ServerPlayer victim) || !(source.getEntity() instanceof ServerPlayer attacker)) {
            return;
        }
        recordDamage(attacker, victim, Math.max(1, Math.round(damageTaken)));
    }

    private void afterDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof ServerPlayer victim)) {
            return;
        }
        if (!matches.isParticipant(victim.getUUID())) {
            return;
        }
        respawns.markDeath(victim.getUUID());
        clientBridge.sendState(victim);
        if (source.getEntity() instanceof ServerPlayer attacker) {
            recordKill(attacker, victim, cleanWeapon(attacker.getMainHandItem()));
        }
    }

    private void onTaczShoot(Object event) {
        if (!TaczEvents.isServer(event)) {
            return;
        }
        Entity shooter = TaczEvents.entity(event, "getShooter");
        if (shooter instanceof ServerPlayer player && !matches.canShoot(player.getUUID())) {
            TaczEvents.cancel(event);
            tell(player, "Weapons are enabled only after the match starts.");
        }
    }

    private void onTaczHurtPre(Object event) {
        if (!TaczEvents.isServer(event)) {
            return;
        }
        Entity attacker = TaczEvents.entity(event, "getAttacker");
        Entity victim = TaczEvents.entity(event, "getHurtEntity");
        if (attacker instanceof ServerPlayer player && !matches.canShoot(player.getUUID())) {
            TaczEvents.cancel(event);
            tell(player, "Weapons are enabled only after the match starts.");
            return;
        }
        if (attacker instanceof ServerPlayer first && victim instanceof ServerPlayer second && matches.isFriendly(first, second)) {
            TaczEvents.cancel(event);
        }
    }

    private void onTaczHurtPost(Object event) {
        if (!TaczEvents.isServer(event)) {
            return;
        }
        Entity attacker = TaczEvents.entity(event, "getAttacker");
        Entity victim = TaczEvents.entity(event, "getHurtEntity");
        if (attacker instanceof ServerPlayer first && victim instanceof ServerPlayer second) {
            recordDamage(first, second, Math.max(1, Math.round(TaczEvents.floatValue(event, "getAmount"))));
        }
    }

    private void onTaczKill(Object event) {
        if (!TaczEvents.isServer(event)) {
            return;
        }
        Entity attacker = TaczEvents.entity(event, "getAttacker");
        Entity victim = TaczEvents.entity(event, "getKilledEntity");
        if (victim instanceof ServerPlayer second) {
            respawns.markDeath(second.getUUID());
            clientBridge.sendState(second);
            if (attacker instanceof ServerPlayer first) {
                recordKill(first, second, String.valueOf(TaczEvents.invokeOrNull(event, "getGunId")));
            }
        }
    }

    private void recordDamage(ServerPlayer attacker, ServerPlayer victim, int amount) {
        if (matches.isFriendly(attacker, victim)) {
            return;
        }
        stats.recordDamage(attacker, victim, amount);
        clientBridge.sendState(attacker);
    }

    private void recordKill(ServerPlayer killer, ServerPlayer victim, String weapon) {
        if (matches.isFriendly(killer, victim)) {
            return;
        }
        if (stats.recordKill(killer, victim, weapon, matches)) {
            economy.add(killer.getUUID(), economy.killReward());
            clientBridge.sendState(killer);
        }
    }

    private int join(CommandSourceStack source, TeamColor team) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ForgeMatchService.JoinResult result = matches.join(player, team);
        switch (result) {
            case JOINED -> {
                feedback(source, "Joined " + team.name().toLowerCase(Locale.ROOT) + " team. Balance: " + economy.balance(player.getUUID()));
                giveKit(player, DEFAULT_KIT, false);
                clientBridge.sendState(player);
                return 1;
            }
            case MATCH_ACTIVE -> error(source, "Match is already running.");
            case TEAM_FULL -> error(source, "That team is full.");
        }
        return 0;
    }

    private int joinAuto(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return join(source, matches.autoTeam());
    }

    private int joinNamed(CommandSourceStack source, String rawTeam) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try {
            return join(source, TeamColor.parse(rawTeam));
        } catch (IllegalArgumentException exception) {
            error(source, "Unknown team. Use red, blue or auto.");
            return 0;
        }
    }

    private int leave(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        matches.leave(player);
        respawns.removePlayer(player.server, player.getUUID());
        squads.leave(player.getUUID());
        feedback(source, "Left the match.");
        clientBridge.sendState(player);
        return 1;
    }

    private int start(CommandSourceStack source) {
        ForgeMatchService.StartResult result = matches.start(source.getServer());
        switch (result) {
            case STARTED -> {
                stats.resetMatch();
                respawns.resetAll(source.getServer());
                feedback(source, "Match started.");
                giveDefaultKits(source.getServer());
                clientBridge.broadcastState(source.getServer());
                return 1;
            }
            case MATCH_RUNNING -> error(source, "Match is already running.");
            case NOT_ENOUGH_PLAYERS -> error(source, "Not enough players.");
        }
        return 0;
    }

    private int stop(CommandSourceStack source) {
        matches.stop(source.getServer());
        respawns.resetAll(source.getServer());
        feedback(source, "Match stopped.");
        clientBridge.broadcastState(source.getServer());
        return 1;
    }

    private int state(CommandSourceStack source) {
        feedback(source, matches.summary());
        return 1;
    }

    private int reload(CommandSourceStack source) {
        Optional<ArenaData> loaded = storage.loadArena();
        if (loaded.isEmpty()) {
            error(source, "arena.json was not found or could not be read.");
            return 0;
        }
        matches.loadArena(loaded.get());
        feedback(source, "Arena configuration reloaded.");
        clientBridge.broadcastState(source.getServer());
        return 1;
    }

    private int balance(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        feedback(source, "Balance: " + economy.balance(player.getUUID()));
        return 1;
    }

    private int playerStats(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerTotalStats total = stats.totalStats(player.getUUID(), player.getName().getString());
        feedback(source, "Stats: " + total.kills() + "/" + total.deaths() + ", damage=" + total.damage() + ", W/L=" + total.wins() + "/" + total.losses());
        return 1;
    }

    private int shop(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            if (!matches.isNearShop(player, 6.0)) {
                error(source, "Move closer to a saved shop location.");
                return 0;
            }
            menus.openShop(player);
            return 1;
        }
        List<String> rows = new ArrayList<>();
        for (ShopItem item : SHOP_ITEMS.values()) {
            rows.add(item.id() + "=" + item.price());
        }
        feedback(source, "Shop: /zw buy <item> | " + String.join(", ", rows));
        return 1;
    }

    private int buy(CommandSourceStack source, String rawItem) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return buyForPlayer(player, rawItem) ? 1 : 0;
    }

    private boolean buyForPlayer(ServerPlayer player, String rawItem) {
        ShopItem item = SHOP_ITEMS.get(rawItem.toLowerCase(Locale.ROOT));
        if (item == null) {
            tell(player, "Unknown shop item. Use /zw shop.");
            return false;
        }
        if (!matches.isParticipant(player.getUUID())) {
            tell(player, "Join a match first.");
            return false;
        }
        if (!matches.isNearShop(player, 6.0)) {
            tell(player, "Move closer to a saved shop location.");
            return false;
        }
        if (item.kind() == ShopKind.MEDKIT && player.getHealth() >= player.getMaxHealth()) {
            tell(player, "You are already at full health.");
            return false;
        }
        if (!economy.spend(player.getUUID(), item.price())) {
            tell(player, "Not enough money. Balance: " + economy.balance(player.getUUID()) + ", price: " + item.price());
            return false;
        }
        switch (item.kind()) {
            case KIT -> giveKit(player, item.payload(), true);
            case AMMO -> giveAmmo(player, item.payload(), 180);
            case TENT -> {
                Optional<String> issue = respawns.placeTent(player);
                if (issue.isPresent()) {
                    economy.add(player.getUUID(), item.price());
                    tell(player, issue.get());
                    return false;
                }
                tell(player, "Tent placed.");
            }
            case OUTPOST -> {
                Optional<String> issue = respawns.placeOutpost(player);
                if (issue.isPresent()) {
                    economy.add(player.getUUID(), item.price());
                    tell(player, issue.get());
                    return false;
                }
                tell(player, "Outpost placed.");
            }
            case MEDKIT -> {
                player.heal(8.0f);
                tell(player, "Used medkit.");
            }
        }
        tell(player, "Bought " + item.id() + ". Balance: " + economy.balance(player.getUUID()));
        clientBridge.sendState(player);
        return true;
    }

    private int kit(CommandSourceStack source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (giveKit(player, name, true)) {
            return 1;
        }
        error(source, "Unknown TaCZ kit: " + name + ". Use /zw shop.");
        return 0;
    }

    private int placeTent(CommandSourceStack source, boolean charge) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (charge && !economy.spend(player.getUUID(), 500)) {
            error(source, "Not enough money for tent.");
            return 0;
        }
        Optional<String> issue = respawns.placeTent(player);
        if (issue.isPresent()) {
            if (charge) {
                economy.add(player.getUUID(), 500);
            }
            error(source, issue.get());
            return 0;
        }
        feedback(source, "Tent placed.");
        clientBridge.broadcastState(player.server);
        return 1;
    }

    private int placeOutpost(CommandSourceStack source, boolean charge) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (charge && !economy.spend(player.getUUID(), 1000)) {
            error(source, "Not enough money for outpost.");
            return 0;
        }
        Optional<String> issue = respawns.placeOutpost(player);
        if (issue.isPresent()) {
            if (charge) {
                economy.add(player.getUUID(), 1000);
            }
            error(source, issue.get());
            return 0;
        }
        feedback(source, "Outpost placed.");
        clientBridge.broadcastState(player.server);
        return 1;
    }

    private int selectRespawn(CommandSourceStack source, RespawnKind kind) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        respawns.select(player.getUUID(), kind);
        feedback(source, "Selected respawn: " + kind.name());
        clientBridge.sendState(player);
        return 1;
    }

    private int respawnMenu(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        menus.openRespawn(player);
        return 1;
    }

    private int confirmRespawn(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        respawns.respawn(player);
        feedback(source, "Respawned at selected point.");
        clientBridge.sendState(player);
        return 1;
    }

    private int squadMenu(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        menus.openSquadMain(player);
        return 1;
    }

    private int squadCreate(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        squads.create(player.getUUID());
        feedback(source, "Squad created.");
        return 1;
    }

    private int squadInvite(CommandSourceStack source, String targetName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) {
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
            tell(target, player.getName().getString() + " invited you to squad. Use /squad join.");
            feedback(source, "Invite sent.");
            return 1;
        } catch (IllegalStateException ex) {
            error(source, ex.getMessage());
            return 0;
        }
    }

    private int squadJoin(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SquadService.JoinResult result = squads.acceptInvite(player.getUUID());
        if (result == SquadService.JoinResult.JOINED) {
            feedback(source, "Joined squad.");
            return 1;
        }
        error(source, "Could not join squad: " + result.name());
        return 0;
    }

    private int squadLeave(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        squads.leave(player.getUUID());
        feedback(source, "Left squad.");
        return 1;
    }

    private int squadInfo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        feedback(source, squadSummary(player));
        return 1;
    }

    private int squadChat(CommandSourceStack source, String message) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<Squad> squad = squads.squadOf(player.getUUID());
        if (squad.isEmpty()) {
            error(source, "You are not in a squad.");
            return 0;
        }
        for (UUID memberId : squad.get().members()) {
            ServerPlayer member = source.getServer().getPlayerList().getPlayer(memberId);
            if (member != null) {
                member.sendSystemMessage(Component.literal("[Squad] " + player.getName().getString() + ": " + message));
            }
        }
        return 1;
    }

    private int clanCreate(CommandSourceStack source, String tag, String color) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ClanService.CreateResult result = clans.create(player, tag, color);
        if (result == ClanService.CreateResult.CREATED) {
            feedback(source, "Clan created.");
            return 1;
        }
        error(source, "Could not create clan: " + result.name());
        return 0;
    }

    private int clanJoin(CommandSourceStack source, String tag) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (clans.join(player, tag)) {
            feedback(source, "Joined clan " + tag + ".");
            return 1;
        }
        error(source, "Clan not found.");
        return 0;
    }

    private int clanLeave(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        clans.leave(player.getUUID());
        feedback(source, "Left clan.");
        return 1;
    }

    private int clanInfo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        feedback(source, clans.clanOf(player.getUUID()).map(Clan::summary).orElse("No clan."));
        return 1;
    }

    private int clanStats(CommandSourceStack source) {
        feedback(source, clans.statsSummary());
        return 1;
    }

    private int clanChat(CommandSourceStack source, String message) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<Clan> clan = clans.clanOf(player.getUUID());
        if (clan.isEmpty()) {
            error(source, "You are not in a clan.");
            return 0;
        }
        for (ServerPlayer online : source.getServer().getPlayerList().getPlayers()) {
            if (clans.clanOf(online.getUUID()).map(value -> value.tag().equalsIgnoreCase(clan.get().tag())).orElse(false)) {
                online.sendSystemMessage(Component.literal("[Clan " + clan.get().tag() + "] " + player.getName().getString() + ": " + message));
            }
        }
        return 1;
    }

    private int arenaSetSpawn(CommandSourceStack source, TeamColor team) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        matches.setSpawn(team, LocationSpec.from(player));
        storage.saveArena(matches.arena());
        feedback(source, team.name() + " spawn saved.");
        return 1;
    }

    private int arenaAddPoint(CommandSourceStack source, String id, String name, String rawRadius) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        double radius;
        try {
            radius = Double.parseDouble(rawRadius);
        } catch (NumberFormatException ex) {
            error(source, "Bad radius.");
            return 0;
        }
        if (!Double.isFinite(radius) || radius < 1.0 || radius > 128.0) {
            error(source, "Radius must be between 1 and 128 blocks.");
            return 0;
        }
        matches.addPoint(new CapturePointData(id, name, LocationSpec.from(player), radius));
        storage.saveArena(matches.arena());
        feedback(source, "Capture point saved: " + id);
        return 1;
    }

    private int captureDebug(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        feedback(source, "Capture debug: phase=" + matches.phase().name() + ", team="
            + matches.teamOf(player.getUUID()).map(Enum::name).orElse("NONE"));
        for (CapturePoint point : matches.points()) {
            LocationSpec location = point.data().location();
            double dx = player.getX() - location.x();
            double dy = player.getY() - location.y();
            double dz = player.getZ() - location.z();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            boolean sameWorld = CapturePoint.worldMatches(player.serverLevel(), location.world());
            boolean inside = sameWorld && horizontal <= point.data().radius()
                && Math.abs(dy) <= Math.max(6.0, point.data().radius());
            feedback(source, point.data().id() + ": world=" + sameWorld
                + ", horizontal=" + String.format(Locale.ROOT, "%.1f", horizontal)
                + ", dy=" + String.format(Locale.ROOT, "%.1f", dy)
                + ", radius=" + point.data().radius()
                + ", inside=" + inside
                + ", status=" + point.status().name()
                + ", progress=" + Math.round(point.progress()));
        }
        return 1;
    }

    private int arenaAddShop(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        matches.addShop(LocationSpec.from(player));
        storage.saveArena(matches.arena());
        feedback(source, "Shop location saved.");
        return 1;
    }

    private int validateKits(CommandSourceStack source) {
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, GunKit> entry : GUN_KITS.entrySet()) {
            GunKit kit = entry.getValue();
            boolean gunOk = !TaczItems.gun(kit.gunId(), kit.magazineSize(), source.getServer().registryAccess()).isEmpty();
            boolean ammoOk = !TaczItems.ammo(kit.ammoId(), 1).isEmpty();
            if (!gunOk || !ammoOk) {
                failed.add(entry.getKey() + "(gun=" + gunOk + ",ammo=" + ammoOk + ")");
            }
        }
        if (failed.isEmpty()) {
            feedback(source, "All TaCZ kits built successfully: " + String.join(", ", GUN_KITS.keySet()));
            return 1;
        }
        error(source, "Broken TaCZ kits: " + String.join(", ", failed));
        return 0;
    }

    private void giveDefaultKits(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (matches.isParticipant(player.getUUID())) {
                giveKit(player, DEFAULT_KIT, false);
            }
        }
    }

    private boolean giveKit(ServerPlayer player, String rawName, boolean notify) {
        GunKit kit = GUN_KITS.get(rawName.toLowerCase(Locale.ROOT));
        if (kit == null) {
            return false;
        }

        ItemStack gun = TaczItems.gun(kit.gunId(), kit.magazineSize(), player.server.registryAccess());
        if (gun.isEmpty()) {
            tell(player, "TaCZ kit could not be built yet: " + kit.displayName() + ".");
            return true;
        }
        give(player, gun);
        giveAmmo(player, kit.ammoId(), kit.magazineSize() * kit.reserveMagazines());

        if (notify) {
            tell(player, "Issued TaCZ kit: " + kit.displayName() + " (" + kit.reserveMagazines() + " reserve mags).");
        }
        return true;
    }

    private void giveAmmo(ServerPlayer player, String ammoId, int rounds) {
        int reserveRounds = rounds;
        while (reserveRounds > 0) {
            int stackSize = Math.min(64, reserveRounds);
            give(player, TaczItems.ammo(ammoId, stackSize));
            reserveRounds -= stackSize;
        }
    }

    private void give(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (!player.addItem(stack)) {
            player.drop(stack, false);
        }
    }

    private String squadSummary(ServerPlayer player) {
        Optional<Squad> squad = squads.squadOf(player.getUUID());
        if (squad.isEmpty()) {
            return "No squad. Use /squad create or accept an invite with /squad join.";
        }
        return "Squad " + squad.get().id().toString().substring(0, 8) + " members=" + squad.get().members().size() + " leader=" + squad.get().leader().toString().substring(0, 8);
    }

    private static String cleanWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "weapon";
        }
        return stack.getHoverName().getString();
    }

    private static void feedback(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(PREFIX + message), false);
    }

    private static void error(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(PREFIX + message));
    }

    private static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(PREFIX + message));
    }

    private final class GuiMenus {
        private void openShop(ServerPlayer player) {
            SimpleContainer inventory = new SimpleContainer(27);
            Map<Integer, Consumer<ServerPlayer>> actions = new HashMap<>();
            int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
            int index = 0;
            for (ShopItem item : SHOP_ITEMS.values()) {
                if (index >= slots.length) {
                    break;
                }
                int slot = slots[index++];
                put(inventory, actions, slot, shopIcon(player, item), buyer -> {
                    buyForPlayer(buyer, item.id());
                    openShop(buyer);
                });
            }
            open(player, "ZoneWars Shop", 3, inventory, actions);
        }

        private void openRespawn(ServerPlayer player) {
            SimpleContainer inventory = new SimpleContainer(9);
            Map<Integer, Consumer<ServerPlayer>> actions = new HashMap<>();
            put(inventory, actions, 2, menuItem(Items.RED_BED, "Base respawn"), selected -> selectFromMenu(selected, RespawnKind.BASE));
            put(inventory, actions, 4, menuItem(respawns.isAvailable(player, RespawnKind.TENT) ? Items.CAMPFIRE : Items.GRAY_DYE, "Tent respawn"), selected -> selectFromMenu(selected, RespawnKind.TENT));
            put(inventory, actions, 6, menuItem(respawns.isAvailable(player, RespawnKind.OUTPOST) ? Items.LODESTONE : Items.GRAY_DYE, "Outpost respawn"), selected -> selectFromMenu(selected, RespawnKind.OUTPOST));
            open(player, "Choose Respawn", 1, inventory, actions);
        }

        private void openSquadMain(ServerPlayer player) {
            SimpleContainer inventory = new SimpleContainer(27);
            Map<Integer, Consumer<ServerPlayer>> actions = new HashMap<>();
            Optional<Squad> squad = squads.squadOf(player.getUUID());
            inventory.setItem(4, menuItem(squad.isPresent() ? Items.NETHER_STAR : Items.PAPER, squad.isPresent() ? "Your Squad" : "No Squad"));
            if (squad.isEmpty()) {
                put(inventory, actions, 11, menuItem(Items.EMERALD_BLOCK, "Create Squad"), actor -> {
                    squads.create(actor.getUUID());
                    tell(actor, "Squad created.");
                    openSquadMain(actor);
                });
                put(inventory, actions, 15, menuItem(squads.hasPendingInvite(player.getUUID()) ? Items.LIME_DYE : Items.GRAY_DYE, squads.hasPendingInvite(player.getUUID()) ? "Accept Invite" : "No Invite"), actor -> {
                    SquadService.JoinResult result = squads.acceptInvite(actor.getUUID());
                    tell(actor, result == SquadService.JoinResult.JOINED ? "Joined squad." : "Could not join squad: " + result.name());
                    openSquadMain(actor);
                });
            } else {
                Squad current = squad.get();
                inventory.setItem(10, menuItem(Items.PLAYER_HEAD, "Members: " + current.members().size() + "/" + SquadService.MAX_SQUAD_SIZE));
                put(inventory, actions, 12, menuItem(canInvite(player, current) ? Items.WRITABLE_BOOK : Items.BOOK, "Invite Player"), actor -> openSquadInvite(actor));
                put(inventory, actions, 14, menuItem(Items.RED_BED, "Leave Squad"), actor -> {
                    squads.leave(actor.getUUID());
                    tell(actor, "Left squad.");
                    openSquadMain(actor);
                });
                put(inventory, actions, 16, menuItem(Items.COMPASS, "Refresh"), this::openSquadMain);
            }
            open(player, "Squad", 3, inventory, actions);
        }

        private void openSquadInvite(ServerPlayer player) {
            Optional<Squad> squad = squads.squadOf(player.getUUID());
            if (squad.isEmpty() || !canInvite(player, squad.get())) {
                tell(player, "Only squad leader can invite, and the squad must have free slots.");
                openSquadMain(player);
                return;
            }

            SimpleContainer inventory = new SimpleContainer(54);
            Map<Integer, Consumer<ServerPlayer>> actions = new HashMap<>();
            int slot = 0;
            for (ServerPlayer candidate : inviteCandidates(player)) {
                if (slot >= 45) {
                    break;
                }
                UUID targetId = candidate.getUUID();
                put(inventory, actions, slot, menuItem(Items.PLAYER_HEAD, candidate.getName().getString()), actor -> inviteFromMenu(actor, targetId));
                slot++;
            }
            if (slot == 0) {
                inventory.setItem(22, menuItem(Items.GRAY_DYE, "No Teammates"));
            }
            put(inventory, actions, 49, menuItem(Items.ARROW, "Back"), this::openSquadMain);
            open(player, "Invite Teammate", 6, inventory, actions);
        }

        private void selectFromMenu(ServerPlayer player, RespawnKind kind) {
            if (!respawns.isAvailable(player, kind)) {
                tell(player, "That respawn is not available yet.");
                openRespawn(player);
                return;
            }
            respawns.select(player.getUUID(), kind);
            tell(player, "Selected respawn: " + kind.name());
            clientBridge.sendState(player);
            player.closeContainer();
        }

        private void inviteFromMenu(ServerPlayer inviter, UUID targetId) {
            ServerPlayer target = inviter.server.getPlayerList().getPlayer(targetId);
            if (target == null) {
                tell(inviter, "Player is no longer online.");
                openSquadInvite(inviter);
                return;
            }
            Optional<String> blocked = inviteBlockReason(inviter, target);
            if (blocked.isPresent()) {
                tell(inviter, blocked.get());
                openSquadInvite(inviter);
                return;
            }
            try {
                squads.invite(inviter.getUUID(), target.getUUID());
                tell(inviter, "Invite sent to " + target.getName().getString() + ".");
                tell(target, inviter.getName().getString() + " invited you to squad. Use /squad join or open /squad.");
            } catch (IllegalStateException ex) {
                tell(inviter, ex.getMessage());
            }
            openSquadInvite(inviter);
        }

        private List<ServerPlayer> inviteCandidates(ServerPlayer inviter) {
            return inviter.server.getPlayerList().getPlayers().stream()
                .filter(candidate -> !candidate.getUUID().equals(inviter.getUUID()))
                .filter(candidate -> squads.squadOf(candidate.getUUID()).isEmpty())
                .filter(candidate -> inviteBlockReason(inviter, candidate).isEmpty())
                .sorted(Comparator.comparing(candidate -> candidate.getName().getString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        }

        private Optional<String> inviteBlockReason(ServerPlayer inviter, ServerPlayer target) {
            Optional<Squad> squad = squads.squadOf(inviter.getUUID());
            if (squad.isEmpty()) {
                return Optional.of("Create a squad first.");
            }
            if (!squad.get().leader().equals(inviter.getUUID())) {
                return Optional.of("Only squad leader can invite.");
            }
            if (squad.get().isFull(SquadService.MAX_SQUAD_SIZE)) {
                return Optional.of("Squad is full.");
            }
            if (squads.squadOf(target.getUUID()).isPresent()) {
                return Optional.of(target.getName().getString() + " is already in a squad.");
            }
            Optional<TeamColor> inviterTeam = matches.teamOf(inviter.getUUID());
            Optional<TeamColor> targetTeam = matches.teamOf(target.getUUID());
            if (inviterTeam.isPresent() && targetTeam.isEmpty()) {
                return Optional.of(target.getName().getString() + " must join your team first.");
            }
            if (inviterTeam.isPresent() && targetTeam.isPresent() && inviterTeam.get() != targetTeam.get()) {
                return Optional.of(target.getName().getString() + " is on another team.");
            }
            return Optional.empty();
        }

        private boolean canInvite(ServerPlayer player, Squad squad) {
            return squad.leader().equals(player.getUUID()) && !squad.isFull(SquadService.MAX_SQUAD_SIZE);
        }

        private ItemStack shopIcon(ServerPlayer player, ShopItem item) {
            ItemStack stack = switch (item.kind()) {
                case KIT -> kitIcon(player, item.payload());
                case AMMO -> TaczItems.ammo(item.payload(), 1);
                case MEDKIT -> new ItemStack(Items.GOLDEN_APPLE);
                case TENT -> new ItemStack(Items.CAMPFIRE);
                case OUTPOST -> new ItemStack(Items.LODESTONE);
            };
            if (stack.isEmpty()) {
                stack = new ItemStack(Items.CHEST);
            }
            stack.setCount(Math.max(1, Math.min(64, item.price() / 50)));
            stack.setHoverName(Component.literal(item.id() + " $" + item.price()));
            return stack;
        }

        private ItemStack kitIcon(ServerPlayer player, String kitName) {
            GunKit kit = GUN_KITS.get(kitName);
            if (kit == null) {
                return new ItemStack(Items.CROSSBOW);
            }
            return TaczItems.gun(kit.gunId(), kit.magazineSize(), player.server.registryAccess());
        }

        private ItemStack menuItem(net.minecraft.world.item.Item item, String name) {
            ItemStack stack = new ItemStack(item);
            stack.setHoverName(Component.literal(name));
            return stack;
        }

        private void put(SimpleContainer inventory, Map<Integer, Consumer<ServerPlayer>> actions, int slot, ItemStack stack, Consumer<ServerPlayer> action) {
            inventory.setItem(slot, stack);
            actions.put(slot, action);
        }

        private void open(ServerPlayer player, String title, int rows, SimpleContainer inventory, Map<Integer, Consumer<ServerPlayer>> actions) {
            player.openMenu(new SimpleMenuProvider(
                (syncId, playerInventory, ignored) -> new ZoneMenuScreenHandler(syncId, playerInventory, inventory, rows, actions),
                Component.literal(title)
            ));
        }
    }

    private static final class ZoneMenuScreenHandler extends ChestMenu {
        private final int menuSize;
        private final Map<Integer, Consumer<ServerPlayer>> actions;

        private ZoneMenuScreenHandler(int syncId, Inventory playerInventory, SimpleContainer inventory, int rows, Map<Integer, Consumer<ServerPlayer>> actions) {
            super(typeForRows(rows), syncId, playerInventory, inventory, rows);
            this.menuSize = rows * 9;
            this.actions = Map.copyOf(actions);
        }

        @Override
        public void clicked(int slotIndex, int button, ClickType actionType, Player player) {
            if (slotIndex >= 0 && slotIndex < menuSize && player instanceof ServerPlayer serverPlayer) {
                Consumer<ServerPlayer> action = actions.get(slotIndex);
                if (action != null) {
                    action.accept(serverPlayer);
                }
            }
            broadcastChanges();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        private static MenuType<ChestMenu> typeForRows(int rows) {
            return switch (rows) {
                case 1 -> MenuType.GENERIC_9x1;
                case 2 -> MenuType.GENERIC_9x2;
                case 4 -> MenuType.GENERIC_9x4;
                case 5 -> MenuType.GENERIC_9x5;
                case 6 -> MenuType.GENERIC_9x6;
                default -> MenuType.GENERIC_9x3;
            };
        }
    }

    private static final class ForgeClientBridge {
        private final ForgeMatchService matches;
        private final SquadService squads;
        private final MatchStatsService stats;
        private final RespawnService respawns;
        private final List<TacticalMarker> markers = new ArrayList<>();

        private ForgeClientBridge(ForgeMatchService matches, SquadService squads, MatchStatsService stats, RespawnService respawns) {
            this.matches = matches;
            this.squads = squads;
            this.stats = stats;
            this.respawns = respawns;
        }

        private void sendState(ServerPlayer player) {
            ZoneWarsNetwork.sendState(player, stateFor(player).toString());
        }

        private void broadcastState(MinecraftServer server) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (matches.isParticipant(player.getUUID())) {
                    sendState(player);
                }
            }
            markers.removeIf(TacticalMarker::expired);
        }

        private void addMarker(ServerPlayer player, String payload) {
            String[] parts = payload.split(":");
            if (parts.length < 3) {
                return;
            }
            try {
                String type = parts[0].toUpperCase(Locale.ROOT);
                int x = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) {
                    return;
                }
                Optional<TeamColor> team = matches.teamOf(player.getUUID());
                Optional<Squad> squad = squads.squadOf(player.getUUID());
                if (team.isEmpty()) {
                    return;
                }
                markers.removeIf(marker -> marker.label().equals(player.getName().getString())
                    && !marker.type().equals("WAYPOINT"));
                markers.add(new TacticalMarker(type, team.get(), squad.map(Squad::id), player.getName().getString(), x, z, System.currentTimeMillis() + 35_000L));
            } catch (NumberFormatException ignored) {
            }
        }

        private void removePlayer(ServerPlayer player) {
            markers.removeIf(marker -> marker.label().equals(player.getName().getString()));
        }

        private void addWaypoint(ServerPlayer player, String payload) {
            String[] parts = payload.split(":");
            if (parts.length < 2) {
                return;
            }
            try {
                int x = Integer.parseInt(parts[0]);
                int z = Integer.parseInt(parts[1]);
                Optional<TeamColor> team = matches.teamOf(player.getUUID());
                Optional<Squad> squad = squads.squadOf(player.getUUID());
                if (team.isEmpty() || squad.isEmpty() || !squad.get().leader().equals(player.getUUID())) {
                    return;
                }
                UUID squadId = squad.get().id();
                markers.removeIf(marker -> marker.type().equals("WAYPOINT") && marker.squadId().map(squadId::equals).orElse(false));
                markers.add(new TacticalMarker("WAYPOINT", team.get(), Optional.of(squadId), player.getName().getString(), x, z, Long.MAX_VALUE));
            } catch (NumberFormatException ignored) {
            }
        }

        private JsonObject stateFor(ServerPlayer viewer) {
            ArenaData arena = matches.arena();
            Optional<TeamColor> viewerTeam = matches.teamOf(viewer.getUUID());
            Optional<Squad> viewerSquad = squads.squadOf(viewer.getUUID());

            JsonObject root = new JsonObject();
            root.addProperty("phase", matches.phase().name());
            root.addProperty("team", viewerTeam.map(Enum::name).orElse("NONE"));
            root.addProperty("redScore", matches.score(TeamColor.RED));
            root.addProperty("blueScore", matches.score(TeamColor.BLUE));
            root.addProperty("seconds", matches.phaseSecondsRemaining());
            root.addProperty("selfYaw", Math.round(normalizeYaw(viewer.getYRot())));
            root.addProperty("selectedRespawn", respawns.selected(viewer.getUUID()).name());
            root.addProperty("respawnPrompt", respawns.isAwaitingRespawn(viewer.getUUID()));
            root.addProperty("mapTexture", "zonewars:textures/gui/maps/refinery.png");

            JsonArray bounds = new JsonArray();
            bounds.add(-80);
            bounds.add(-56);
            bounds.add(80);
            bounds.add(56);
            root.add("bounds", bounds);

            JsonArray bases = new JsonArray();
            bases.add(baseJson("RED", "Red Base", arena.redSpawn()));
            bases.add(baseJson("BLUE", "Blue Base", arena.blueSpawn()));
            root.add("bases", bases);

            JsonArray points = new JsonArray();
            for (CapturePoint point : matches.points()) {
                JsonObject object = new JsonObject();
                object.addProperty("id", point.data().id());
                object.addProperty("name", point.data().displayName());
                object.addProperty("owner", point.owner().map(Enum::name).orElse("NEUTRAL"));
                object.addProperty("progress", Math.round(point.progress()));
                object.addProperty("x", Math.round(point.data().location().x()));
                object.addProperty("z", Math.round(point.data().location().z()));
                object.addProperty("status", point.status().name());
                object.addProperty("capturingTeam", point.capturingTeam() == null ? "NONE" : point.capturingTeam().name());
                points.add(object);
            }
            root.add("points", points);

            JsonArray players = new JsonArray();
            for (ServerPlayer player : viewer.server.getPlayerList().getPlayers()) {
                Optional<TeamColor> team = matches.teamOf(player.getUUID());
                if (team.isEmpty()) {
                    continue;
                }
                if (viewerTeam.isEmpty() || team.get() != viewerTeam.get()) {
                    continue;
                }
                JsonObject object = new JsonObject();
                object.addProperty("name", player.getName().getString());
                object.addProperty("team", team.get().name());
                object.addProperty("squad", viewerSquad.map(value -> value.contains(player.getUUID())).orElse(false));
                object.addProperty("self", player.getUUID().equals(viewer.getUUID()));
                object.addProperty("x", Math.round(player.getX()));
                object.addProperty("z", Math.round(player.getZ()));
                object.addProperty("yaw", Math.round(normalizeYaw(player.getYRot())));
                object.addProperty("health", Math.round(healthPercent(player)));
                players.add(object);
            }
            root.add("players", players);

            root.add("respawns", respawns.jsonFor(viewer));

            JsonArray markerJson = new JsonArray();
            long now = System.currentTimeMillis();
            for (TacticalMarker marker : markers) {
                if (viewerTeam.isPresent() && marker.team() != viewerTeam.get()) {
                    continue;
                }
                if (marker.squadId().isPresent() && viewerSquad.map(value -> !value.id().equals(marker.squadId().get())).orElse(true)) {
                    continue;
                }
                JsonObject object = new JsonObject();
                object.addProperty("type", marker.type());
                object.addProperty("team", marker.team().name());
                object.addProperty("label", marker.label());
                object.addProperty("x", marker.x());
                object.addProperty("z", marker.z());
                object.addProperty("seconds", marker.expiresAt() == Long.MAX_VALUE ? -1 : Math.max(0, (marker.expiresAt() - now) / 1000));
                object.addProperty("own", marker.label().equals(viewer.getName().getString()));
                markerJson.add(object);
            }
            root.add("markers", markerJson);

            JsonArray killFeed = new JsonArray();
            for (KillFeedEntry entry : stats.killFeed()) {
                JsonObject object = new JsonObject();
                object.addProperty("killer", entry.killer());
                object.addProperty("killerTeam", entry.killerTeam().name());
                object.addProperty("victim", entry.victim());
                object.addProperty("victimTeam", entry.victimTeam().name());
                object.addProperty("weapon", entry.weapon());
                object.addProperty("seconds", Duration.between(entry.createdAt(), Instant.now()).toSeconds());
                killFeed.add(object);
            }
            root.add("killFeed", killFeed);

            PlayerMatchStats self = stats.stats(viewer.getUUID());
            JsonObject selfStats = new JsonObject();
            selfStats.addProperty("kills", self.kills());
            selfStats.addProperty("deaths", self.deaths());
            selfStats.addProperty("damage", self.damage());
            root.add("selfStats", selfStats);

            HitMarker marker = stats.hitMarker(viewer.getUUID());
            JsonObject hit = new JsonObject();
            hit.addProperty("sequence", marker.sequence());
            hit.addProperty("damage", marker.damage());
            hit.addProperty("kill", marker.kill());
            root.add("hit", hit);

            return root;
        }

        private static JsonObject baseJson(String team, String name, LocationSpec location) {
            JsonObject object = new JsonObject();
            object.addProperty("team", team);
            object.addProperty("name", name);
            object.addProperty("x", Math.round(location.x()));
            object.addProperty("z", Math.round(location.z()));
            return object;
        }

        private static float normalizeYaw(float yaw) {
            float value = yaw % 360.0f;
            return value < 0 ? value + 360.0f : value;
        }

        private static int healthPercent(ServerPlayer player) {
            return (int) Math.max(0, Math.min(100, (player.getHealth() / player.getMaxHealth()) * 100.0));
        }
    }

    private static final class ForgeMatchService {
        private final EconomyService economy;
        private final MatchStatsService stats;
        private final Map<UUID, TeamColor> teams = new HashMap<>();
        private final EnumMap<TeamColor, Integer> scores = new EnumMap<>(TeamColor.class);
        private final List<CapturePoint> points = new ArrayList<>();
        private ArenaData arena = ArenaData.refinery();
        private MatchPhase phase = MatchPhase.WAITING;
        private int phaseSecondsRemaining;

        private ForgeMatchService(EconomyService economy, MatchStatsService stats) {
            this.economy = economy;
            this.stats = stats;
            resetScores();
            resetPoints();
        }

        private ArenaData arena() {
            return arena;
        }

        private void loadArena(ArenaData loadedArena) {
            arena = loadedArena;
            resetPoints();
        }

        private MatchPhase phase() {
            return phase;
        }

        private int score(TeamColor team) {
            return scores.getOrDefault(team, 0);
        }

        private int phaseSecondsRemaining() {
            return phaseSecondsRemaining;
        }

        private List<CapturePoint> points() {
            return List.copyOf(points);
        }

        private boolean isParticipant(UUID playerId) {
            return teams.containsKey(playerId);
        }

        private Collection<UUID> players() {
            return List.copyOf(teams.keySet());
        }

        private Optional<TeamColor> teamOf(UUID playerId) {
            return Optional.ofNullable(teams.get(playerId));
        }

        private boolean isFriendly(ServerPlayer first, ServerPlayer second) {
            Optional<TeamColor> firstTeam = teamOf(first.getUUID());
            Optional<TeamColor> secondTeam = teamOf(second.getUUID());
            return firstTeam.isPresent() && firstTeam.equals(secondTeam);
        }

        private boolean canShoot(UUID playerId) {
            return isParticipant(playerId) && (phase == MatchPhase.ACTIVE || phase == MatchPhase.OVERTIME);
        }

        private TeamColor autoTeam() {
            return teamSize(TeamColor.RED) <= teamSize(TeamColor.BLUE) ? TeamColor.RED : TeamColor.BLUE;
        }

        private JoinResult join(ServerPlayer player, TeamColor team) {
            if (phase == MatchPhase.ACTIVE || phase == MatchPhase.OVERTIME || phase == MatchPhase.ENDED) {
                return JoinResult.MATCH_ACTIVE;
            }
            if (teamSize(team) >= arena.maxPlayersPerTeam()) {
                return JoinResult.TEAM_FULL;
            }

            teams.put(player.getUUID(), team);
            economy.resetPlayer(player.getUUID());
            teleportToBase(player);
            return JoinResult.JOINED;
        }

        private void leave(ServerPlayer player) {
            teams.remove(player.getUUID());
        }

        private boolean isNearShop(ServerPlayer player, double maxDistance) {
            if (arena.shopLocations().isEmpty()) {
                return true;
            }
            double maxDistanceSquared = maxDistance * maxDistance;
            for (LocationSpec shop : arena.shopLocations()) {
                if (!CapturePoint.worldMatches(player.serverLevel(), shop.world())) {
                    continue;
                }
                double dx = player.getX() - shop.x();
                double dy = player.getY() - shop.y();
                double dz = player.getZ() - shop.z();
                if (dx * dx + dy * dy + dz * dz <= maxDistanceSquared) {
                    return true;
                }
            }
            return false;
        }

        private StartResult start(MinecraftServer server) {
            if (phase != MatchPhase.WAITING) {
                return StartResult.MATCH_RUNNING;
            }
            if (teamSize(TeamColor.RED) < arena.minPlayersPerTeam() || teamSize(TeamColor.BLUE) < arena.minPlayersPerTeam()) {
                return StartResult.NOT_ENOUGH_PLAYERS;
            }
            resetScores();
            resetPoints();
            phase = MatchPhase.PREPARING;
            phaseSecondsRemaining = arena.preparationSeconds();
            teleportAllToBases(server);
            broadcast(server, "Preparation started.");
            return StartResult.STARTED;
        }

        private void stop(MinecraftServer server) {
            resetToWaiting(server);
            broadcast(server, "Arena reset.");
        }

        private void tick(MinecraftServer server) {
            if (phase == MatchPhase.ENDED) {
                phaseSecondsRemaining--;
                if (phaseSecondsRemaining <= 0) {
                    resetToWaiting(server);
                }
                return;
            }
            if (phase != MatchPhase.PREPARING && phase != MatchPhase.ACTIVE && phase != MatchPhase.OVERTIME) {
                return;
            }

            phaseSecondsRemaining--;
            if (phase == MatchPhase.PREPARING && phaseSecondsRemaining <= 0) {
                phase = MatchPhase.ACTIVE;
                phaseSecondsRemaining = arena.matchSeconds();
                broadcast(server, "Match started. Capture the points.");
                return;
            }

            if (phase == MatchPhase.ACTIVE || phase == MatchPhase.OVERTIME) {
                tickIncome();
                if (phaseSecondsRemaining <= 0) {
                    if (phase == MatchPhase.ACTIVE && leadingTeam().isEmpty() && arena.overtimeSeconds() > 0) {
                        phase = MatchPhase.OVERTIME;
                        phaseSecondsRemaining = arena.overtimeSeconds();
                        broadcast(server, "Overtime started.");
                    } else {
                        finishByScore(server);
                    }
                } else if (phase == MatchPhase.OVERTIME && leadingTeam().isPresent()) {
                    finishByScore(server);
                }
            }
        }

        private void fastTick(MinecraftServer server, double secondsPerTick) {
            if (phase != MatchPhase.ACTIVE && phase != MatchPhase.OVERTIME) {
                return;
            }
            Collection<ServerPlayer> online = server.getPlayerList().getPlayers();
            for (CapturePoint point : points) {
                CapturePoint.TickResult result = point.tick(online, teams, arena.captureSeconds(), secondsPerTick);
                if (result.statusChanged()) {
                    announcePoint(server, point, result.status());
                }
                result.capturedBy().ifPresent(team -> {
                    rewardCapture(server, point, team);
                    broadcast(server, team.name() + " captured " + point.data().displayName() + ".");
                });
            }
        }

        private String summary() {
            return phase.name() + " | RED " + score(TeamColor.RED) + " - BLUE " + score(TeamColor.BLUE) + " | " + teams.size() + " players";
        }

        private void setSpawn(TeamColor team, LocationSpec location) {
            arena = team == TeamColor.RED ? arena.withRedSpawn(location) : arena.withBlueSpawn(location);
        }

        private void addPoint(CapturePointData point) {
            List<CapturePointData> newPoints = new ArrayList<>(arena.capturePoints());
            newPoints.add(point);
            arena = arena.withCapturePoints(newPoints);
            resetPoints();
        }

        private void addShop(LocationSpec location) {
            List<LocationSpec> shops = new ArrayList<>(arena.shopLocations());
            shops.add(location);
            arena = arena.withShopLocations(shops);
        }

        private long teamSize(TeamColor team) {
            return teams.values().stream().filter(existing -> existing == team).count();
        }

        private void tickIncome() {
            for (CapturePoint point : points) {
                if (point.status() != CapturePointStatus.OWNED) {
                    continue;
                }
                point.owner().ifPresent(owner -> {
                    scores.merge(owner, arena.pointsPerSecond(), Integer::sum);
                    for (Map.Entry<UUID, TeamColor> entry : teams.entrySet()) {
                        if (entry.getValue() == owner) {
                            economy.add(entry.getKey(), economy.captureIncomePerSecond());
                        }
                    }
                });
            }
        }

        private void rewardCapture(MinecraftServer server, CapturePoint point, TeamColor team) {
            double radiusSquared = point.data().radius() * point.data().radius();
            for (UUID playerId : teams.keySet()) {
                if (teams.get(playerId) != team) {
                    continue;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !point.worldMatches(player.serverLevel(), point.data().location().world())) {
                    continue;
                }
                double dx = player.getX() - point.data().location().x();
                double dz = player.getZ() - point.data().location().z();
                if (dx * dx + dz * dz <= radiusSquared) {
                    economy.add(playerId, economy.captureReward());
                    tell(player, "Capture reward: +" + economy.captureReward());
                }
            }
        }

        private Optional<TeamColor> leadingTeam() {
            int red = score(TeamColor.RED);
            int blue = score(TeamColor.BLUE);
            if (red == blue) {
                return Optional.empty();
            }
            return Optional.of(red > blue ? TeamColor.RED : TeamColor.BLUE);
        }

        private void finishByScore(MinecraftServer server) {
            phase = MatchPhase.ENDED;
            phaseSecondsRemaining = arena.endScreenSeconds();
            Optional<TeamColor> winner = leadingTeam();
            stats.handleMatchEnd(winner, score(TeamColor.RED), score(TeamColor.BLUE), Map.copyOf(teams), server);
            broadcast(server, winner.map(team -> team.name() + " wins.").orElse("Draw."));
        }

        private void resetToWaiting(MinecraftServer server) {
            phase = MatchPhase.WAITING;
            phaseSecondsRemaining = 0;
            resetScores();
            resetPoints();
            teleportAllToBases(server);
        }

        private void resetScores() {
            scores.put(TeamColor.RED, 0);
            scores.put(TeamColor.BLUE, 0);
        }

        private void resetPoints() {
            points.clear();
            arena.capturePoints().forEach(point -> points.add(new CapturePoint(point)));
        }

        private void teleportAllToBases(MinecraftServer server) {
            for (Map.Entry<UUID, TeamColor> entry : teams.entrySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    teleportToBase(player);
                }
            }
        }

        private void teleportToBase(ServerPlayer player) {
            TeamColor team = teamOf(player.getUUID()).orElse(TeamColor.RED);
            LocationSpec spawn = team == TeamColor.RED ? arena.redSpawn() : arena.blueSpawn();
            teleport(player, spawn);
        }

        private void teleport(ServerPlayer player, LocationSpec location) {
            ServerLevel world = resolveLevel(player.server, location.world()).orElse(player.serverLevel());
            player.teleportTo(world, location.x(), location.y(), location.z(), location.yaw(), location.pitch());
        }

        private Optional<ServerLevel> resolveLevel(MinecraftServer server, String worldName) {
            if (worldName == null || worldName.isBlank() || worldName.equals("world")) {
                return Optional.of(server.overworld());
            }
            ResourceLocation worldId = ResourceLocation.tryParse(worldName);
            if (worldId == null) {
                return Optional.empty();
            }
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, worldId);
            return Optional.ofNullable(server.getLevel(key));
        }

        private void announcePoint(MinecraftServer server, CapturePoint point, CapturePointStatus status) {
            switch (status) {
                case CONTESTED -> broadcast(server, point.data().displayName() + " is contested.");
                case NEUTRALIZING -> broadcast(server, point.data().displayName() + " is being neutralized.");
                case OWNED -> point.owner().ifPresent(team -> broadcast(server, team.name() + " controls " + point.data().displayName() + "."));
                default -> {
                }
            }
        }

        private void broadcast(MinecraftServer server, String message) {
            for (UUID playerId : teams.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    tell(player, message);
                }
            }
        }

        private enum JoinResult {
            JOINED,
            TEAM_FULL,
            MATCH_ACTIVE
        }

        private enum StartResult {
            STARTED,
            NOT_ENOUGH_PLAYERS,
            MATCH_RUNNING
        }
    }

    private static final class EconomyService {
        private static final int MAX_BALANCE = 1_000_000;
        private final int startingMoney;
        private final int captureIncomePerSecond;
        private final int killReward;
        private final int captureReward;
        private final Map<UUID, Integer> balances = new HashMap<>();

        private EconomyService(int startingMoney, int captureIncomePerSecond, int killReward, int captureReward) {
            this.startingMoney = startingMoney;
            this.captureIncomePerSecond = captureIncomePerSecond;
            this.killReward = killReward;
            this.captureReward = captureReward;
        }

        private void resetPlayer(UUID playerId) {
            balances.put(playerId, startingMoney);
        }

        private int balance(UUID playerId) {
            return balances.getOrDefault(playerId, 0);
        }

        private void add(UUID playerId, int amount) {
            long next = (long) balance(playerId) + Math.max(0, amount);
            balances.put(playerId, (int) Math.min(MAX_BALANCE, next));
        }

        private boolean spend(UUID playerId, int amount) {
            int current = balance(playerId);
            if (current < amount) {
                return false;
            }
            balances.put(playerId, current - amount);
            return true;
        }

        private int captureIncomePerSecond() {
            return captureIncomePerSecond;
        }

        private int killReward() {
            return killReward;
        }

        private int captureReward() {
            return captureReward;
        }
    }

    private static final class MatchStatsService {
        private static final int FEED_LIMIT = 6;
        private static final int FEED_TTL_SECONDS = 12;
        private static final int DAMAGE_DEDUP_WINDOW_MILLIS = 75;

        private final ClanService clans;
        private final PersistentStore storage;
        private final Map<UUID, PlayerMatchStats> stats = new HashMap<>();
        private final Map<UUID, PlayerTotalStats> totals = new HashMap<>();
        private final Map<UUID, HitMarker> hitMarkers = new HashMap<>();
        private final Map<UUID, Integer> hitSequences = new HashMap<>();
        private final Map<String, Instant> recentDamageHits = new HashMap<>();
        private final Map<UUID, Instant> recentVictimKills = new HashMap<>();
        private final LinkedList<KillFeedEntry> killFeed = new LinkedList<>();

        private MatchStatsService(ClanService clans, PersistentStore storage) {
            this.clans = clans;
            this.storage = storage;
        }

        private void resetMatch() {
            stats.clear();
            hitMarkers.clear();
            hitSequences.clear();
            recentDamageHits.clear();
            recentVictimKills.clear();
            killFeed.clear();
        }

        private void recordDamage(ServerPlayer attacker, ServerPlayer victim, int damage) {
            if (attacker.equals(victim)) {
                return;
            }
            String hitKey = attacker.getUUID() + ">" + victim.getUUID();
            Instant now = Instant.now();
            recentDamageHits.entrySet().removeIf(entry ->
                Duration.between(entry.getValue(), now).toMillis() > 2_000L);
            Instant recent = recentDamageHits.get(hitKey);
            if (recent != null && Duration.between(recent, now).toMillis() < DAMAGE_DEDUP_WINDOW_MILLIS) {
                return;
            }
            recentDamageHits.put(hitKey, now);
            stats(attacker.getUUID()).addDamage(damage);
            int nextSequence = hitSequences.merge(attacker.getUUID(), 1, Integer::sum);
            hitMarkers.put(attacker.getUUID(), new HitMarker(nextSequence, damage, false));
        }

        private boolean recordKill(ServerPlayer killer, ServerPlayer victim, String weapon, ForgeMatchService matches) {
            if (killer.equals(victim)) {
                return false;
            }
            Instant now = Instant.now();
            recentVictimKills.entrySet().removeIf(entry ->
                Duration.between(entry.getValue(), now).toSeconds() > 10L);
            Instant recent = recentVictimKills.get(victim.getUUID());
            if (recent != null && Duration.between(recent, now).toMillis() < 900) {
                return false;
            }
            recentVictimKills.put(victim.getUUID(), now);

            stats(killer.getUUID()).addKill();
            stats(victim.getUUID()).addDeath();
            int nextSequence = hitSequences.merge(killer.getUUID(), 1, Integer::sum);
            hitMarkers.put(killer.getUUID(), new HitMarker(nextSequence, 0, true));

            TeamColor killerTeam = matches.teamOf(killer.getUUID()).orElse(TeamColor.RED);
            TeamColor victimTeam = matches.teamOf(victim.getUUID()).orElse(TeamColor.BLUE);
            killFeed.addFirst(new KillFeedEntry(killer.getName().getString(), killerTeam, victim.getName().getString(), victimTeam, cleanWeapon(weapon), now));
            while (killFeed.size() > FEED_LIMIT) {
                killFeed.removeLast();
            }
            return true;
        }

        private List<KillFeedEntry> killFeed() {
            Instant now = Instant.now();
            killFeed.removeIf(entry -> Duration.between(entry.createdAt(), now).toSeconds() > FEED_TTL_SECONDS);
            return List.copyOf(killFeed);
        }

        private HitMarker hitMarker(UUID playerId) {
            return hitMarkers.getOrDefault(playerId, new HitMarker(0, 0, false));
        }

        private PlayerMatchStats stats(UUID playerId) {
            return stats.computeIfAbsent(playerId, ignored -> new PlayerMatchStats());
        }

        private void loadTotals(Map<UUID, PlayerTotalStats> loadedTotals) {
            totals.clear();
            totals.putAll(loadedTotals);
        }

        private PlayerTotalStats totalStats(UUID playerId, String fallbackName) {
            return totals.getOrDefault(playerId, new PlayerTotalStats(fallbackName, 0, 0, 0, 0, 0));
        }

        private Map<UUID, PlayerTotalStats> totalsSnapshot() {
            return Map.copyOf(totals);
        }

        private void handleMatchEnd(Optional<TeamColor> winner, int redScore, int blueScore, Map<UUID, TeamColor> teams, MinecraftServer server) {
            Map<UUID, Boolean> winnersByPlayer = new HashMap<>();
            for (Map.Entry<UUID, TeamColor> entry : teams.entrySet()) {
                boolean won = winner.map(value -> value == entry.getValue()).orElse(false);
                winnersByPlayer.put(entry.getKey(), won);
                PlayerMatchStats matchStats = stats(entry.getKey());
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                String name = player == null ? entry.getKey().toString().substring(0, 8) : player.getName().getString();
                totals.merge(
                    entry.getKey(),
                    PlayerTotalStats.fromMatch(name, matchStats, won),
                    (oldStats, addStats) -> oldStats.plus(name, matchStats, won)
                );
            }
            clans.recordMatchResults(winnersByPlayer);
            storage.savePlayerStats(totals);
            storage.recordMatch(winner, redScore, blueScore);
            broadcastSummary(teams, server);
        }

        private void broadcastSummary(Map<UUID, TeamColor> teams, MinecraftServer server) {
            List<Map.Entry<UUID, PlayerMatchStats>> leaders = new ArrayList<>(stats.entrySet());
            leaders.sort(Comparator
                .<Map.Entry<UUID, PlayerMatchStats>>comparingInt(entry -> entry.getValue().kills()).reversed()
                .thenComparing(entry -> entry.getValue().deaths()));
            StringBuilder line = new StringBuilder("Top match stats: ");
            for (int i = 0; i < Math.min(3, leaders.size()); i++) {
                Map.Entry<UUID, PlayerMatchStats> entry = leaders.get(i);
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                String name = player == null ? entry.getKey().toString().substring(0, 8) : player.getName().getString();
                if (i > 0) {
                    line.append(" | ");
                }
                line.append(name).append(" ").append(entry.getValue().kills()).append("/").append(entry.getValue().deaths());
            }
            for (UUID playerId : teams.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    tell(player, line.toString());
                }
            }
        }

        private String cleanWeapon(String weapon) {
            if (weapon == null || weapon.isBlank() || weapon.equals("null")) {
                return "weapon";
            }
            int colon = weapon.indexOf(':');
            return (colon >= 0 ? weapon.substring(colon + 1) : weapon).replace('_', ' ');
        }
    }

    private static final class PlayerMatchStats {
        private int kills;
        private int deaths;
        private int damage;

        private void addKill() {
            kills++;
        }

        private void addDeath() {
            deaths++;
        }

        private void addDamage(int amount) {
            long next = (long) damage + Math.max(0, amount);
            damage = (int) Math.min(Integer.MAX_VALUE, next);
        }

        private int kills() {
            return kills;
        }

        private int deaths() {
            return deaths;
        }

        private int damage() {
            return damage;
        }
    }

    private record PlayerTotalStats(String name, int kills, int deaths, int damage, int wins, int losses) {
        private static PlayerTotalStats fromMatch(String name, PlayerMatchStats matchStats, boolean won) {
            return new PlayerTotalStats(name, matchStats.kills(), matchStats.deaths(), matchStats.damage(), won ? 1 : 0, won ? 0 : 1);
        }

        private PlayerTotalStats plus(String name, PlayerMatchStats matchStats, boolean won) {
            return new PlayerTotalStats(
                name,
                kills + matchStats.kills(),
                deaths + matchStats.deaths(),
                damage + matchStats.damage(),
                wins + (won ? 1 : 0),
                losses + (won ? 0 : 1)
            );
        }
    }

    private static final class SquadService {
        private static final int MAX_SQUAD_SIZE = 5;
        private final Map<UUID, Squad> squads = new HashMap<>();
        private final Map<UUID, UUID> squadByPlayer = new HashMap<>();
        private final Map<UUID, Invitation> invitations = new HashMap<>();

        private Squad create(UUID leader) {
            leave(leader);
            Squad squad = new Squad(UUID.randomUUID(), leader);
            squads.put(squad.id(), squad);
            squadByPlayer.put(leader, squad.id());
            return squad;
        }

        private Optional<Squad> squadOf(UUID playerId) {
            UUID squadId = squadByPlayer.get(playerId);
            return squadId == null ? Optional.empty() : Optional.ofNullable(squads.get(squadId));
        }

        private void invite(UUID inviter, UUID target) {
            if (inviter.equals(target)) {
                throw new IllegalStateException("You cannot invite yourself.");
            }
            Squad squad = squadOf(inviter).orElseThrow(() -> new IllegalStateException("Create a squad first."));
            if (!squad.leader().equals(inviter)) {
                throw new IllegalStateException("Only squad leader can invite.");
            }
            if (squad.isFull(MAX_SQUAD_SIZE)) {
                throw new IllegalStateException("Squad is full.");
            }
            if (squadByPlayer.containsKey(target)) {
                throw new IllegalStateException("Target is already in a squad.");
            }
            invitations.put(target, new Invitation(squad.id(), Instant.now().plus(Duration.ofSeconds(60))));
        }

        private boolean hasPendingInvite(UUID playerId) {
            Invitation invite = invitations.get(playerId);
            if (invite == null) {
                return false;
            }
            if (invite.expiresAt().isBefore(Instant.now()) || !squads.containsKey(invite.squadId())) {
                invitations.remove(playerId);
                return false;
            }
            return true;
        }

        private JoinResult acceptInvite(UUID playerId) {
            Invitation invite = invitations.remove(playerId);
            if (invite == null || invite.expiresAt().isBefore(Instant.now())) {
                return JoinResult.NO_INVITE;
            }
            Squad squad = squads.get(invite.squadId());
            if (squad == null) {
                return JoinResult.NO_SQUAD;
            }
            if (squad.isFull(MAX_SQUAD_SIZE)) {
                return JoinResult.FULL;
            }
            leave(playerId);
            squad.add(playerId, MAX_SQUAD_SIZE);
            squadByPlayer.put(playerId, squad.id());
            return JoinResult.JOINED;
        }

        private void leave(UUID playerId) {
            UUID squadId = squadByPlayer.remove(playerId);
            if (squadId == null) {
                return;
            }
            Squad squad = squads.get(squadId);
            if (squad == null) {
                return;
            }
            squad.remove(playerId);
            if (squad.isEmpty()) {
                squads.remove(squadId);
            }
        }

        private enum JoinResult {
            JOINED,
            NO_INVITE,
            NO_SQUAD,
            FULL
        }

        private record Invitation(UUID squadId, Instant expiresAt) {
        }
    }

    private static final class Squad {
        private final UUID id;
        private UUID leader;
        private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

        private Squad(UUID id, UUID leader) {
            this.id = id;
            this.leader = leader;
            this.members.add(leader);
        }

        private UUID id() {
            return id;
        }

        private UUID leader() {
            return leader;
        }

        private Set<UUID> members() {
            return Set.copyOf(members);
        }

        private boolean contains(UUID playerId) {
            return members.contains(playerId);
        }

        private boolean isFull(int maxSize) {
            return members.size() >= maxSize;
        }

        private boolean add(UUID playerId, int maxSize) {
            if (isFull(maxSize)) {
                return false;
            }
            return members.add(playerId);
        }

        private void remove(UUID playerId) {
            boolean removed = members.remove(playerId);
            if (removed && playerId.equals(leader) && !members.isEmpty()) {
                leader = members.iterator().next();
            }
        }

        private boolean isEmpty() {
            return members.isEmpty();
        }
    }

    private static final class ClanService {
        private final PersistentStore storage;
        private final Map<String, Clan> clans = new HashMap<>();
        private final Map<UUID, String> playerClans = new HashMap<>();

        private ClanService(PersistentStore storage) {
            this.storage = storage;
        }

        private void load(PersistentStore.ClanState state) {
            clans.clear();
            clans.putAll(state.clans());
            playerClans.clear();
            playerClans.putAll(state.playerClans());
        }

        private CreateResult create(ServerPlayer player, String rawTag, String rawColor) {
            String tag = sanitizeTag(rawTag);
            String color = rawColor == null ? "WHITE" : rawColor.toUpperCase(Locale.ROOT);
            if (tag.isBlank() || tag.length() > 12) {
                return CreateResult.BAD_TAG;
            }
            if (clans.containsKey(tag.toLowerCase(Locale.ROOT))) {
                return CreateResult.EXISTS;
            }
            Clan clan = new Clan(tag, color, 0, 0);
            clans.put(tag.toLowerCase(Locale.ROOT), clan);
            playerClans.put(player.getUUID(), tag.toLowerCase(Locale.ROOT));
            save();
            return CreateResult.CREATED;
        }

        private boolean join(ServerPlayer player, String rawTag) {
            String tag = sanitizeTag(rawTag).toLowerCase(Locale.ROOT);
            if (!clans.containsKey(tag)) {
                return false;
            }
            playerClans.put(player.getUUID(), tag);
            save();
            return true;
        }

        private void leave(UUID playerId) {
            playerClans.remove(playerId);
            save();
        }

        private Optional<Clan> clanOf(UUID playerId) {
            String tag = playerClans.get(playerId);
            return tag == null ? Optional.empty() : Optional.ofNullable(clans.get(tag));
        }

        private void recordMatchResults(Map<UUID, Boolean> winnersByPlayer) {
            Set<String> wonClans = new HashSet<>();
            Set<String> lostClans = new HashSet<>();
            for (Map.Entry<UUID, Boolean> entry : winnersByPlayer.entrySet()) {
                Optional<Clan> clan = clanOf(entry.getKey());
                if (clan.isEmpty()) {
                    continue;
                }
                if (entry.getValue()) {
                    wonClans.add(clan.get().tag().toLowerCase(Locale.ROOT));
                } else {
                    lostClans.add(clan.get().tag().toLowerCase(Locale.ROOT));
                }
            }
            wonClans.forEach(tag -> clans.computeIfPresent(tag, (key, clan) -> clan.win()));
            lostClans.stream().filter(tag -> !wonClans.contains(tag)).forEach(tag -> clans.computeIfPresent(tag, (key, clan) -> clan.loss()));
            save();
        }

        private String statsSummary() {
            if (clans.isEmpty()) {
                return "No clans yet.";
            }
            List<Clan> rows = new ArrayList<>(clans.values());
            rows.sort(Comparator.comparingInt(Clan::wins).reversed());
            List<String> rendered = new ArrayList<>();
            for (Clan clan : rows) {
                rendered.add(clan.tag() + " " + clan.wins() + "/" + clan.losses());
            }
            return String.join(" | ", rendered);
        }

        private String sanitizeTag(String tag) {
            return tag == null ? "" : tag.replaceAll("[^A-Za-z0-9_-]", "");
        }

        private PersistentStore.ClanState snapshot() {
            return new PersistentStore.ClanState(Map.copyOf(clans), Map.copyOf(playerClans));
        }

        private void save() {
            storage.saveClans(snapshot());
        }

        private enum CreateResult {
            CREATED,
            BAD_TAG,
            EXISTS
        }
    }

    private record Clan(String tag, String color, int wins, int losses) {
        private Clan win() {
            return new Clan(tag, color, wins + 1, losses);
        }

        private Clan loss() {
            return new Clan(tag, color, wins, losses + 1);
        }

        private String summary() {
            return "[" + tag + "] color=" + color + " W/L=" + wins + "/" + losses;
        }
    }

    private static final class PersistentStore {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private Path dir;

        private void open() {
            dir = FMLPaths.CONFIGDIR.get().resolve("zonewars");
            try {
                Files.createDirectories(dir);
            } catch (IOException exception) {
                System.err.println(PREFIX + "Could not create storage directory: " + exception.getMessage());
            }
        }

        private Optional<ArenaData> loadArena() {
            ArenaData fallback = ArenaData.refinery();
            return readObject("arena.json").map(root -> {
                List<LocationSpec> shops = new ArrayList<>();
                for (JsonElement element : array(root, "shopLocations")) {
                    if (element.isJsonObject()) {
                        shops.add(location(element.getAsJsonObject(), new LocationSpec("world", 0.5, 63.0, 0.5, 0.0f, 0.0f)));
                    }
                }
                if (shops.isEmpty()) {
                    shops = fallback.shopLocations();
                }

                List<CapturePointData> points = new ArrayList<>();
                for (JsonElement element : array(root, "capturePoints")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject point = element.getAsJsonObject();
                    points.add(new CapturePointData(
                        stringValue(point, "id", "point" + points.size()),
                        stringValue(point, "displayName", stringValue(point, "id", "Point")),
                        location(object(point, "location"), fallback.capturePoints().get(Math.min(points.size(), fallback.capturePoints().size() - 1)).location()),
                        boundedDouble(point, "radius", 9.0, 1.0, 128.0)
                    ));
                }
                if (points.isEmpty()) {
                    points = fallback.capturePoints();
                }
                int maxPlayers = boundedInt(root, "maxPlayersPerTeam", fallback.maxPlayersPerTeam(), 1, 100);
                int minPlayers = Math.min(maxPlayers,
                    boundedInt(root, "minPlayersPerTeam", fallback.minPlayersPerTeam(), 0, 100));
                return new ArenaData(
                    minPlayers,
                    maxPlayers,
                    boundedInt(root, "preparationSeconds", fallback.preparationSeconds(), 0, 600),
                    boundedInt(root, "matchSeconds", fallback.matchSeconds(), 60, 21_600),
                    boundedInt(root, "overtimeSeconds", fallback.overtimeSeconds(), 0, 3_600),
                    boundedInt(root, "endScreenSeconds", fallback.endScreenSeconds(), 0, 300),
                    boundedInt(root, "captureSeconds", fallback.captureSeconds(), 1, 600),
                    boundedInt(root, "pointsPerSecond", fallback.pointsPerSecond(), 0, 1_000),
                    location(object(root, "redSpawn"), fallback.redSpawn()),
                    location(object(root, "blueSpawn"), fallback.blueSpawn()),
                    List.copyOf(shops),
                    List.copyOf(points)
                );
            });
        }

        private void saveArena(ArenaData arena) {
            JsonObject root = new JsonObject();
            root.addProperty("minPlayersPerTeam", arena.minPlayersPerTeam());
            root.addProperty("maxPlayersPerTeam", arena.maxPlayersPerTeam());
            root.addProperty("preparationSeconds", arena.preparationSeconds());
            root.addProperty("matchSeconds", arena.matchSeconds());
            root.addProperty("overtimeSeconds", arena.overtimeSeconds());
            root.addProperty("endScreenSeconds", arena.endScreenSeconds());
            root.addProperty("captureSeconds", arena.captureSeconds());
            root.addProperty("pointsPerSecond", arena.pointsPerSecond());
            root.add("redSpawn", locationJson(arena.redSpawn()));
            root.add("blueSpawn", locationJson(arena.blueSpawn()));
            JsonArray shops = new JsonArray();
            for (LocationSpec shop : arena.shopLocations()) {
                shops.add(locationJson(shop));
            }
            root.add("shopLocations", shops);
            JsonArray points = new JsonArray();
            for (CapturePointData point : arena.capturePoints()) {
                JsonObject row = new JsonObject();
                row.addProperty("id", point.id());
                row.addProperty("displayName", point.displayName());
                row.addProperty("radius", point.radius());
                row.add("location", locationJson(point.location()));
                points.add(row);
            }
            root.add("capturePoints", points);
            writeObject("arena.json", root);
        }

        private ClanState loadClans() {
            Map<String, Clan> loadedClans = new HashMap<>();
            Map<UUID, String> loadedPlayerClans = new HashMap<>();
            readObject("clans.json").ifPresent(root -> {
                for (Map.Entry<String, JsonElement> entry : object(root, "clans").entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject row = entry.getValue().getAsJsonObject();
                    Clan clan = new Clan(
                        stringValue(row, "tag", entry.getKey()),
                        stringValue(row, "color", "WHITE"),
                        intValue(row, "wins", 0),
                        intValue(row, "losses", 0)
                    );
                    loadedClans.put(clan.tag().toLowerCase(Locale.ROOT), clan);
                }
                for (Map.Entry<String, JsonElement> entry : object(root, "playerClans").entrySet()) {
                    try {
                        loadedPlayerClans.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString().toLowerCase(Locale.ROOT));
                    } catch (RuntimeException ignored) {
                    }
                }
            });
            return new ClanState(Map.copyOf(loadedClans), Map.copyOf(loadedPlayerClans));
        }

        private void saveClans(ClanState state) {
            JsonObject root = new JsonObject();
            JsonObject clanRows = new JsonObject();
            for (Map.Entry<String, Clan> entry : state.clans().entrySet()) {
                Clan clan = entry.getValue();
                JsonObject row = new JsonObject();
                row.addProperty("tag", clan.tag());
                row.addProperty("color", clan.color());
                row.addProperty("wins", clan.wins());
                row.addProperty("losses", clan.losses());
                clanRows.add(entry.getKey().toLowerCase(Locale.ROOT), row);
            }
            JsonObject playerRows = new JsonObject();
            for (Map.Entry<UUID, String> entry : state.playerClans().entrySet()) {
                playerRows.addProperty(entry.getKey().toString(), entry.getValue());
            }
            root.add("clans", clanRows);
            root.add("playerClans", playerRows);
            writeObject("clans.json", root);
        }

        private Map<UUID, PlayerTotalStats> loadPlayerStats() {
            Map<UUID, PlayerTotalStats> rows = new HashMap<>();
            readObject("player_stats.json").ifPresent(root -> {
                for (Map.Entry<String, JsonElement> entry : object(root, "players").entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    try {
                        UUID playerId = UUID.fromString(entry.getKey());
                        JsonObject row = entry.getValue().getAsJsonObject();
                        rows.put(playerId, new PlayerTotalStats(
                            stringValue(row, "name", playerId.toString().substring(0, 8)),
                            intValue(row, "kills", 0),
                            intValue(row, "deaths", 0),
                            intValue(row, "damage", 0),
                            intValue(row, "wins", 0),
                            intValue(row, "losses", 0)
                        ));
                    } catch (RuntimeException ignored) {
                    }
                }
            });
            return Map.copyOf(rows);
        }

        private void savePlayerStats(Map<UUID, PlayerTotalStats> stats) {
            JsonObject root = new JsonObject();
            JsonObject players = new JsonObject();
            for (Map.Entry<UUID, PlayerTotalStats> entry : stats.entrySet()) {
                PlayerTotalStats total = entry.getValue();
                JsonObject row = new JsonObject();
                row.addProperty("name", total.name());
                row.addProperty("kills", total.kills());
                row.addProperty("deaths", total.deaths());
                row.addProperty("damage", total.damage());
                row.addProperty("wins", total.wins());
                row.addProperty("losses", total.losses());
                players.add(entry.getKey().toString(), row);
            }
            root.add("players", players);
            writeObject("player_stats.json", root);
        }

        private void recordMatch(Optional<TeamColor> winner, int redScore, int blueScore) {
            JsonObject root = readObject("matches.json").orElseGet(JsonObject::new);
            JsonArray rows = array(root, "matches");
            JsonObject match = new JsonObject();
            match.addProperty("winner", winner.map(Enum::name).orElse("DRAW"));
            match.addProperty("redScore", redScore);
            match.addProperty("blueScore", blueScore);
            match.addProperty("createdAt", Instant.now().toString());
            rows.add(match);
            while (rows.size() > 100) {
                rows.remove(0);
            }
            root.add("matches", rows);
            writeObject("matches.json", root);
        }

        private void ensureMatchHistory() {
            if (readObject("matches.json").isPresent()) {
                return;
            }
            JsonObject root = new JsonObject();
            root.add("matches", new JsonArray());
            writeObject("matches.json", root);
        }

        private Optional<JsonObject> readObject(String name) {
            if (dir == null) {
                return Optional.empty();
            }
            Path primary = dir.resolve(name);
            Optional<JsonObject> loaded = readObjectPath(primary);
            if (loaded.isPresent()) {
                return loaded;
            }
            Path backup = dir.resolve(name + ".bak");
            Optional<JsonObject> recovered = readObjectPath(backup);
            if (recovered.isPresent()) {
                System.err.println(PREFIX + "Recovered " + name + " from backup.");
            }
            return recovered;
        }

        private Optional<JsonObject> readObjectPath(Path path) {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement element = JsonParser.parseReader(reader);
                return element != null && element.isJsonObject() ? Optional.of(element.getAsJsonObject()) : Optional.empty();
            } catch (RuntimeException | IOException exception) {
                System.err.println(PREFIX + "Could not read " + path + ": " + exception.getMessage());
                return Optional.empty();
            }
        }

        private void writeObject(String name, JsonObject root) {
            if (dir == null) {
                return;
            }
            try {
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
                System.err.println(PREFIX + "Could not write " + name + ": " + exception.getMessage());
            }
        }

        private static JsonObject locationJson(LocationSpec location) {
            JsonObject object = new JsonObject();
            object.addProperty("world", location.world());
            object.addProperty("x", location.x());
            object.addProperty("y", location.y());
            object.addProperty("z", location.z());
            object.addProperty("yaw", location.yaw());
            object.addProperty("pitch", location.pitch());
            return object;
        }

        private static LocationSpec location(JsonObject object, LocationSpec fallback) {
            if (object == null) {
                return fallback;
            }
            return new LocationSpec(
                stringValue(object, "world", fallback.world()),
                boundedDouble(object, "x", fallback.x(), -30_000_000.0, 30_000_000.0),
                boundedDouble(object, "y", fallback.y(), -2_048.0, 2_048.0),
                boundedDouble(object, "z", fallback.z(), -30_000_000.0, 30_000_000.0),
                (float) boundedDouble(object, "yaw", fallback.yaw(), -360.0, 360.0),
                (float) boundedDouble(object, "pitch", fallback.pitch(), -90.0, 90.0)
            );
        }

        private static JsonObject object(JsonObject root, String key) {
            JsonElement element = root.get(key);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        }

        private static JsonArray array(JsonObject root, String key) {
            JsonElement element = root.get(key);
            return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
        }

        private static String stringValue(JsonObject root, String key, String fallback) {
            JsonElement element = root.get(key);
            return element == null || element.isJsonNull() ? fallback : element.getAsString();
        }

        private static int intValue(JsonObject root, String key, int fallback) {
            JsonElement element = root.get(key);
            return element == null || element.isJsonNull() ? fallback : element.getAsInt();
        }

        private static double doubleValue(JsonObject root, String key, double fallback) {
            JsonElement element = root.get(key);
            return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
        }

        private static int boundedInt(JsonObject root, String key, int fallback, int minimum, int maximum) {
            try {
                return Math.max(minimum, Math.min(maximum, intValue(root, key, fallback)));
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }

        private static double boundedDouble(JsonObject root, String key, double fallback, double minimum, double maximum) {
            try {
                double value = doubleValue(root, key, fallback);
                if (!Double.isFinite(value)) {
                    return fallback;
                }
                return Math.max(minimum, Math.min(maximum, value));
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }

        private record ClanState(Map<String, Clan> clans, Map<UUID, String> playerClans) {
        }
    }

    private static final class RespawnService {
        private final ForgeMatchService matches;
        private final SquadService squads;
        private final Map<UUID, RespawnPoint> tentsByPlayer = new HashMap<>();
        private final Map<UUID, RespawnPoint> outpostsBySquad = new HashMap<>();
        private final Map<UUID, RespawnKind> selected = new HashMap<>();
        private final Map<UUID, Long> cooldownUntil = new HashMap<>();
        private final Set<UUID> awaitingRespawn = new HashSet<>();

        private RespawnService(ForgeMatchService matches, SquadService squads) {
            this.matches = matches;
            this.squads = squads;
        }

        private Optional<String> placeTent(ServerPlayer player) {
            Optional<TeamColor> team = matches.teamOf(player.getUUID());
            if (team.isEmpty()) {
                return Optional.of("Join a team first.");
            }
            Optional<String> issue = placementIssue(player);
            if (issue.isPresent()) {
                return issue;
            }
            BlockPos blockPos = player.blockPosition().relative(player.getDirection());
            if (!player.serverLevel().getBlockState(blockPos).isAir()) {
                return Optional.of("Need empty space in front of you.");
            }
            RespawnPoint previous = tentsByPlayer.get(player.getUUID());
            if (previous != null) {
                removeBlock(player.server, previous);
            }
            player.serverLevel().setBlock(blockPos, Blocks.CAMPFIRE.defaultBlockState(), 3);
            spawnModel(player.server, player.serverLevel(), blockPos, 9102, 1.7f);
            RespawnPoint point = new RespawnPoint(
                RespawnKind.TENT,
                team.get(),
                player.getName().getString() + "'s Tent",
                LocationSpec.fromBlock(player, blockPos.above()),
                LocationSpec.fromBlock(player, blockPos),
                150,
                150,
                System.currentTimeMillis() + 10_000L
            );
            tentsByPlayer.put(player.getUUID(), point);
            selected.put(player.getUUID(), RespawnKind.TENT);
            return Optional.empty();
        }

        private Optional<String> placeOutpost(ServerPlayer player) {
            Optional<TeamColor> team = matches.teamOf(player.getUUID());
            Optional<Squad> squad = squads.squadOf(player.getUUID());
            if (team.isEmpty()) {
                return Optional.of("Join a team first.");
            }
            if (squad.isEmpty()) {
                return Optional.of("Create or join a squad first.");
            }
            if (!squad.get().leader().equals(player.getUUID())) {
                return Optional.of("Only the squad leader can place an outpost.");
            }
            Optional<String> issue = placementIssue(player);
            if (issue.isPresent()) {
                return issue;
            }
            BlockPos blockPos = player.blockPosition().relative(player.getDirection());
            if (!player.serverLevel().getBlockState(blockPos).isAir()) {
                return Optional.of("Need empty space in front of you.");
            }
            RespawnPoint previous = outpostsBySquad.get(squad.get().id());
            if (previous != null) {
                removeBlock(player.server, previous);
            }
            player.serverLevel().setBlock(blockPos, Blocks.LODESTONE.defaultBlockState(), 3);
            spawnModel(player.server, player.serverLevel(), blockPos, 9103, 2.0f);
            RespawnPoint point = new RespawnPoint(
                RespawnKind.OUTPOST,
                team.get(),
                "Squad Outpost",
                LocationSpec.fromBlock(player, blockPos.above()),
                LocationSpec.fromBlock(player, blockPos),
                300,
                300,
                System.currentTimeMillis() + 50_000L
            );
            outpostsBySquad.put(squad.get().id(), point);
            selected.put(player.getUUID(), RespawnKind.OUTPOST);
            return Optional.empty();
        }

        private Optional<String> placementIssue(ServerPlayer player) {
            ArenaData arena = matches.arena();
            double baseDistance = Math.min(distance2d(player, arena.redSpawn()), distance2d(player, arena.blueSpawn()));
            if (baseDistance < 18.0) {
                return Optional.of("Too close to base.");
            }
            for (CapturePointData point : arena.capturePoints()) {
                if (distance2d(player, point.location()) < 12.0) {
                    return Optional.of("Too close to capture point.");
                }
            }
            for (UUID playerId : matches.players()) {
                ServerPlayer other = player.server.getPlayerList().getPlayer(playerId);
                if (other != null && !other.getUUID().equals(player.getUUID()) && !matches.isFriendly(player, other) && distance2d(player, LocationSpec.from(other)) < 16.0) {
                    return Optional.of("Too close to enemy.");
                }
            }
            return Optional.empty();
        }

        private void markDeath(UUID playerId) {
            awaitingRespawn.add(playerId);
        }

        private boolean isAwaitingRespawn(UUID playerId) {
            return awaitingRespawn.contains(playerId);
        }

        private void removePlayer(MinecraftServer server, UUID playerId) {
            RespawnPoint tent = tentsByPlayer.remove(playerId);
            if (tent != null) {
                removeBlock(server, tent);
            }
            selected.remove(playerId);
            cooldownUntil.remove(playerId);
            awaitingRespawn.remove(playerId);
        }

        private void select(UUID playerId, RespawnKind kind) {
            selected.put(playerId, kind);
        }

        private RespawnKind selected(UUID playerId) {
            return selected.getOrDefault(playerId, RespawnKind.BASE);
        }

        private boolean respawn(ServerPlayer player) {
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

        private boolean isAvailable(ServerPlayer player, RespawnKind kind) {
            if (kind == RespawnKind.BASE) {
                return true;
            }
            return pointFor(player, kind).filter(this::available).isPresent();
        }

        private Optional<RespawnPoint> resolve(ServerPlayer player) {
            RespawnKind kind = selected(player.getUUID());
            return pointFor(player, kind);
        }

        private Optional<RespawnPoint> pointFor(ServerPlayer player, RespawnKind kind) {
            Optional<TeamColor> team = matches.teamOf(player.getUUID());
            if (team.isEmpty() || kind == RespawnKind.BASE) {
                return Optional.empty();
            }
            if (kind == RespawnKind.TENT) {
                RespawnPoint point = tentsByPlayer.get(player.getUUID());
                return point != null && point.team() == team.get() ? Optional.of(point) : Optional.empty();
            }
            Optional<Squad> squad = squads.squadOf(player.getUUID());
            if (squad.isEmpty()) {
                return Optional.empty();
            }
            RespawnPoint point = outpostsBySquad.get(squad.get().id());
            return point != null && point.team() == team.get() ? Optional.of(point) : Optional.empty();
        }

        private JsonArray jsonFor(ServerPlayer viewer) {
            JsonArray rows = new JsonArray();
            Optional<TeamColor> team = matches.teamOf(viewer.getUUID());
            ArenaData arena = matches.arena();
            TeamColor ownTeam = team.orElse(TeamColor.RED);
            rows.add(json(new RespawnPoint(RespawnKind.BASE, ownTeam, "Base", ownTeam == TeamColor.RED ? arena.redSpawn() : arena.blueSpawn(), ownTeam == TeamColor.RED ? arena.redSpawn() : arena.blueSpawn(), 0, 0, 0), true, 0));
            if (team.isEmpty()) {
                return rows;
            }
            RespawnPoint tent = tentsByPlayer.get(viewer.getUUID());
            if (tent != null && tent.team() == team.get()) {
                rows.add(json(tent, available(tent), secondsUntil(tent)));
            }
            squads.squadOf(viewer.getUUID()).map(Squad::id).map(outpostsBySquad::get).ifPresent(point -> {
                if (point.team() == team.get()) {
                    rows.add(json(point, available(point), secondsUntil(point)));
                }
            });
            return rows;
        }

        private boolean available(RespawnPoint point) {
            return point.availableAt() <= System.currentTimeMillis();
        }

        private int secondsUntil(RespawnPoint point) {
            return (int) Math.max(0, (point.availableAt() - System.currentTimeMillis()) / 1000);
        }

        private JsonObject json(RespawnPoint point, boolean available, int seconds) {
            JsonObject object = new JsonObject();
            object.addProperty("kind", point.kind().name());
            object.addProperty("team", point.team().name());
            object.addProperty("name", point.name());
            object.addProperty("x", Math.round(point.location().x()));
            object.addProperty("z", Math.round(point.location().z()));
            object.addProperty("available", available);
            object.addProperty("seconds", seconds);
            object.addProperty("health", point.health());
            object.addProperty("maxHealth", point.maxHealth());
            return object;
        }

        private DamageResult damageObject(ServerPlayer attacker, ServerLevel world, BlockPos pos, int damage) {
            ObjectRef ref = objectAt(world, pos).orElse(null);
            if (ref == null) {
                return DamageResult.NOT_OBJECT;
            }
            TeamColor attackerTeam = matches.teamOf(attacker.getUUID()).orElse(null);
            if (attackerTeam == null) {
                tell(attacker, "Join a match before attacking respawn objects.");
                return DamageResult.NOT_IN_MATCH;
            }
            if (attackerTeam == ref.point().team()) {
                tell(attacker, "This is your team's respawn object.");
                return DamageResult.FRIENDLY;
            }
            int health = Math.max(0, ref.point().health() - Math.max(1, damage));
            RespawnPoint damaged = ref.point().withHealth(health);
            if (health == 0) {
                if (ref.outpost()) {
                    outpostsBySquad.remove(ref.key());
                } else {
                    tentsByPlayer.remove(ref.key());
                }
                world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                removeModel(attacker.server, world, pos);
                matches.broadcast(attacker.server, ref.point().team().name() + " " + ref.point().name() + " was destroyed.");
                return DamageResult.DESTROYED;
            }
            if (ref.outpost()) {
                outpostsBySquad.put(ref.key(), damaged);
            } else {
                tentsByPlayer.put(ref.key(), damaged);
            }
            tell(attacker, ref.point().name() + " HP: " + health + "/" + ref.point().maxHealth());
            return DamageResult.DAMAGED;
        }

        private Optional<ObjectRef> objectAt(ServerLevel world, BlockPos pos) {
            for (Map.Entry<UUID, RespawnPoint> entry : tentsByPlayer.entrySet()) {
                if (sameBlock(world, pos, entry.getValue().blockLocation())) {
                    return Optional.of(new ObjectRef(entry.getKey(), entry.getValue(), false));
                }
            }
            for (Map.Entry<UUID, RespawnPoint> entry : outpostsBySquad.entrySet()) {
                if (sameBlock(world, pos, entry.getValue().blockLocation())) {
                    return Optional.of(new ObjectRef(entry.getKey(), entry.getValue(), true));
                }
            }
            return Optional.empty();
        }

        private boolean sameBlock(ServerLevel world, BlockPos pos, LocationSpec location) {
            return CapturePoint.worldMatches(world, location.world())
                && pos.getX() == (int) Math.floor(location.x())
                && pos.getY() == (int) Math.floor(location.y())
                && pos.getZ() == (int) Math.floor(location.z());
        }

        private void removeBlock(MinecraftServer server, RespawnPoint point) {
            matches.resolveLevel(server, point.blockLocation().world()).ifPresent(world -> {
                BlockPos pos = BlockPos.containing(point.blockLocation().x(), point.blockLocation().y(), point.blockLocation().z());
                boolean expectedBlock = point.kind() == RespawnKind.TENT
                    ? world.getBlockState(pos).is(Blocks.CAMPFIRE)
                    : world.getBlockState(pos).is(Blocks.LODESTONE);
                if (expectedBlock) {
                    world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
                removeModel(server, world, pos);
            });
        }

        private void spawnModel(MinecraftServer server, ServerLevel world, BlockPos pos, int customModelData, float scale) {
            removeModel(server, world, pos);
            String tag = modelTag(world, pos);
            String dimension = world.dimension().location().toString();
            String command = "execute in " + dimension + " run summon minecraft:item_display "
                + (pos.getX() + 0.5) + " " + pos.getY() + " " + (pos.getZ() + 0.5)
                + " {Tags:[\"" + tag + "\"],item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:custom_model_data\":" + customModelData
                + "}},item_display:\"fixed\",transformation:{scale:[" + scale + "f," + scale + "f," + scale + "f]}}";
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        }

        private void removeModel(MinecraftServer server, ServerLevel world, BlockPos pos) {
            String dimension = world.dimension().location().toString();
            String command = "execute in " + dimension + " run kill @e[type=minecraft:item_display,tag=" + modelTag(world, pos) + "]";
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        }

        private String modelTag(ServerLevel world, BlockPos pos) {
            String raw = world.dimension().location().getPath() + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
            return "zwm_" + Integer.toUnsignedString(raw.hashCode(), 36);
        }

        private void tick() {
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Long> entry : List.copyOf(cooldownUntil.entrySet())) {
                if (entry.getValue() <= now) {
                    cooldownUntil.remove(entry.getKey());
                }
            }
        }

        private void resetAll(MinecraftServer server) {
            List<RespawnPoint> points = new ArrayList<>(tentsByPlayer.values());
            points.addAll(outpostsBySquad.values());
            for (RespawnPoint point : points) {
                removeBlock(server, point);
            }
            tentsByPlayer.clear();
            outpostsBySquad.clear();
            selected.clear();
            cooldownUntil.clear();
            awaitingRespawn.clear();
        }

        private enum DamageResult {
            NOT_OBJECT,
            NOT_IN_MATCH,
            FRIENDLY,
            DAMAGED,
            DESTROYED
        }

        private record ObjectRef(UUID key, RespawnPoint point, boolean outpost) {
        }

        private double distance2d(ServerPlayer player, LocationSpec location) {
            double dx = player.getX() - location.x();
            double dz = player.getZ() - location.z();
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    private static final class CapturePoint {
        private final CapturePointData data;
        private TeamColor owner;
        private TeamColor capturingTeam;
        private double progress;
        private CapturePointStatus status = CapturePointStatus.NEUTRAL;

        private CapturePoint(CapturePointData data) {
            this.data = data;
        }

        private CapturePointData data() {
            return data;
        }

        private Optional<TeamColor> owner() {
            return Optional.ofNullable(owner);
        }

        private double progress() {
            return progress;
        }

        private TeamColor capturingTeam() {
            return capturingTeam;
        }

        private CapturePointStatus status() {
            return status;
        }

        private TickResult tick(Collection<ServerPlayer> players, Map<UUID, TeamColor> teams, int captureSeconds, double secondsPerTick) {
            Map<TeamColor, Integer> present = new EnumMap<>(TeamColor.class);
            double radiusSquared = data.radius() * data.radius();

            for (ServerPlayer player : players) {
                TeamColor team = teams.get(player.getUUID());
                if (team == null || !worldMatches(player.serverLevel(), data.location().world())) {
                    continue;
                }
                if (!player.isAlive() || player.isSpectator()) {
                    continue;
                }
                double dx = player.getX() - data.location().x();
                double dy = player.getY() - data.location().y();
                double dz = player.getZ() - data.location().z();
                // Capture zones are vertical cylinders. A 3D sphere made valid players
                // fall outside the zone when terrain height differed from the saved Y.
                double verticalLimit = Math.max(6.0, data.radius());
                if (dx * dx + dz * dz <= radiusSquared && Math.abs(dy) <= verticalLimit) {
                    present.merge(team, 1, Integer::sum);
                }
            }

            TeamColor oldOwner = owner;
            CapturePointStatus oldStatus = status;
            TeamColor oldCapturingTeam = capturingTeam;
            double baseStep = (100.0 / Math.max(1, captureSeconds)) * Math.max(0.0, secondsPerTick);

            if (present.isEmpty()) {
                if (owner != null && capturingTeam != owner && progress > 0.0) {
                    progress = Math.max(0.0, progress - baseStep * 0.5);
                    status = CapturePointStatus.NEUTRALIZING;
                    if (progress == 0.0) {
                        capturingTeam = owner;
                        progress = 100.0;
                        status = CapturePointStatus.OWNED;
                    }
                    return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
                }
                if (owner == null && progress > 0.0) {
                    progress = Math.max(0.0, progress - baseStep * 0.5);
                    if (progress == 0.0) {
                        capturingTeam = null;
                        status = CapturePointStatus.NEUTRAL;
                    } else {
                        status = CapturePointStatus.NEUTRALIZING;
                    }
                    return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
                }
                status = owner == null ? CapturePointStatus.NEUTRAL : CapturePointStatus.OWNED;
                capturingTeam = owner;
                progress = owner == null ? 0.0 : 100.0;
                return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
            }

            int red = present.getOrDefault(TeamColor.RED, 0);
            int blue = present.getOrDefault(TeamColor.BLUE, 0);
            if (red == blue) {
                status = CapturePointStatus.CONTESTED;
                return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
            }

            TeamColor team = red > blue ? TeamColor.RED : TeamColor.BLUE;
            int advantage = Math.abs(red - blue);
            double step = baseStep * Math.max(1, advantage);
            if (team == owner && capturingTeam == owner) {
                capturingTeam = team;
                progress = 100.0;
                status = CapturePointStatus.OWNED;
                return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
            }

            if (team == owner) {
                status = CapturePointStatus.NEUTRALIZING;
                progress = Math.max(0.0, progress - step);
                if (progress <= 0.0) {
                    capturingTeam = owner;
                    progress = 100.0;
                    status = CapturePointStatus.OWNED;
                }
                return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
            }

            if (capturingTeam != team) {
                if (owner != null && capturingTeam == owner) {
                    capturingTeam = team;
                    progress = 0.0;
                } else if (progress <= step) {
                    capturingTeam = team;
                    progress = 0.0;
                } else {
                    status = CapturePointStatus.NEUTRALIZING;
                    progress = Math.max(0.0, progress - step);
                    return new TickResult(Optional.empty(), changed(oldOwner, oldStatus, oldCapturingTeam), status);
                }
            }

            status = CapturePointStatus.CAPTURING;
            progress = Math.min(100.0, progress + step);

            Optional<TeamColor> captured = Optional.empty();
            if (progress >= 100.0) {
                owner = team;
                progress = 100.0;
                status = CapturePointStatus.OWNED;
                captured = Optional.of(team);
            }
            return new TickResult(captured, changed(oldOwner, oldStatus, oldCapturingTeam), status);
        }

        private boolean changed(TeamColor oldOwner, CapturePointStatus oldStatus, TeamColor oldCapturingTeam) {
            return oldStatus != status || oldOwner != owner || oldCapturingTeam != capturingTeam;
        }

        private static boolean worldMatches(ServerLevel world, String configuredLevel) {
            if (configuredLevel == null || configuredLevel.isBlank() || configuredLevel.equals("world")) {
                return world.dimension() == Level.OVERWORLD;
            }
            ResourceLocation id = world.dimension().location();
            return id.toString().equals(configuredLevel) || id.getPath().equals(configuredLevel);
        }

        private record TickResult(Optional<TeamColor> capturedBy, boolean statusChanged, CapturePointStatus status) {
        }
    }

    private record ArenaData(
        int minPlayersPerTeam,
        int maxPlayersPerTeam,
        int preparationSeconds,
        int matchSeconds,
        int overtimeSeconds,
        int endScreenSeconds,
        int captureSeconds,
        int pointsPerSecond,
        LocationSpec redSpawn,
        LocationSpec blueSpawn,
        List<LocationSpec> shopLocations,
        List<CapturePointData> capturePoints
    ) {
        private static ArenaData refinery() {
            return new ArenaData(
                0,
                15,
                10,
                1800,
                180,
                12,
                10,
                1,
                new LocationSpec("world", -48.5, 63.0, 0.5, 90.0f, 0.0f),
                new LocationSpec("world", 48.5, 63.0, 0.5, -90.0f, 0.0f),
                List.of(),
                List.of(
                    new CapturePointData("alpha", "Alpha", new LocationSpec("world", -32.5, 63.0, -12.5, 0.0f, 0.0f), 9.0),
                    new CapturePointData("bravo", "Bravo", new LocationSpec("world", 0.5, 63.0, 0.5, 0.0f, 0.0f), 9.0),
                    new CapturePointData("charlie", "Charlie", new LocationSpec("world", 32.5, 63.0, 12.5, 0.0f, 0.0f), 9.0)
                )
            );
        }

        private ArenaData withRedSpawn(LocationSpec location) {
            return new ArenaData(minPlayersPerTeam, maxPlayersPerTeam, preparationSeconds, matchSeconds, overtimeSeconds, endScreenSeconds, captureSeconds, pointsPerSecond, location, blueSpawn, shopLocations, capturePoints);
        }

        private ArenaData withBlueSpawn(LocationSpec location) {
            return new ArenaData(minPlayersPerTeam, maxPlayersPerTeam, preparationSeconds, matchSeconds, overtimeSeconds, endScreenSeconds, captureSeconds, pointsPerSecond, redSpawn, location, shopLocations, capturePoints);
        }

        private ArenaData withShopLocations(List<LocationSpec> shops) {
            return new ArenaData(minPlayersPerTeam, maxPlayersPerTeam, preparationSeconds, matchSeconds, overtimeSeconds, endScreenSeconds, captureSeconds, pointsPerSecond, redSpawn, blueSpawn, List.copyOf(shops), capturePoints);
        }

        private ArenaData withCapturePoints(List<CapturePointData> points) {
            return new ArenaData(minPlayersPerTeam, maxPlayersPerTeam, preparationSeconds, matchSeconds, overtimeSeconds, endScreenSeconds, captureSeconds, pointsPerSecond, redSpawn, blueSpawn, shopLocations, List.copyOf(points));
        }
    }

    private record CapturePointData(String id, String displayName, LocationSpec location, double radius) {
    }

    private record LocationSpec(String world, double x, double y, double z, float yaw, float pitch) {
        private static LocationSpec from(ServerPlayer player) {
            String world = player.serverLevel().dimension() == Level.OVERWORLD ? "world" : player.serverLevel().dimension().location().toString();
            return new LocationSpec(world, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }

        private static LocationSpec fromBlock(ServerPlayer player, BlockPos pos) {
            String world = player.serverLevel().dimension() == Level.OVERWORLD ? "world" : player.serverLevel().dimension().location().toString();
            return new LocationSpec(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), player.getXRot());
        }
    }

    private record RespawnPoint(RespawnKind kind, TeamColor team, String name, LocationSpec location, LocationSpec blockLocation, int health, int maxHealth, long availableAt) {
        private RespawnPoint withHealth(int value) {
            return new RespawnPoint(kind, team, name, location, blockLocation, value, maxHealth, availableAt);
        }
    }

    private record TacticalMarker(String type, TeamColor team, Optional<UUID> squadId, String label, int x, int z, long expiresAt) {
        private boolean expired() {
            return expiresAt != Long.MAX_VALUE && System.currentTimeMillis() > expiresAt;
        }
    }

    private record HitMarker(int sequence, int damage, boolean kill) {
    }

    private record KillFeedEntry(String killer, TeamColor killerTeam, String victim, TeamColor victimTeam, String weapon, Instant createdAt) {
    }

    private enum TeamColor {
        RED,
        BLUE;

        private static TeamColor parse(String value) {
            for (TeamColor team : values()) {
                if (team.name().equalsIgnoreCase(value)) {
                    return team;
                }
            }
            throw new IllegalArgumentException("Unknown team: " + value);
        }
    }

    private enum MatchPhase {
        WAITING,
        PREPARING,
        ACTIVE,
        OVERTIME,
        ENDED
    }

    private enum CapturePointStatus {
        NEUTRAL,
        OWNED,
        CAPTURING,
        NEUTRALIZING,
        CONTESTED
    }

    private enum RespawnKind {
        BASE,
        TENT,
        OUTPOST;

        private static RespawnKind parse(String value) {
            for (RespawnKind kind : values()) {
                if (kind.name().equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            return BASE;
        }
    }

    private enum ShopKind {
        KIT,
        AMMO,
        MEDKIT,
        TENT,
        OUTPOST
    }

    private record GunKit(String displayName, String gunId, String ammoId, int magazineSize, int reserveMagazines) {
    }

    private record ShopItem(String id, int price, ShopKind kind, String payload) {
    }

    private static Map<String, GunKit> gunKits() {
        Map<String, GunKit> kits = new LinkedHashMap<>();
        kits.put("ak101", new GunKit("AK-101", "stalker:ak101", "tacz:556x45", 30, 6));
        kits.put("ak102", new GunKit("AK-102", "stalker:ak102", "tacz:556x45", 30, 6));
        kits.put("ak103", new GunKit("AK-103", "stalker:ak103", "tacz:762x39", 30, 6));
        kits.put("ak203", new GunKit("AK-203", "stalker:ak203", "tacz:762x39", 30, 6));
        kits.put("ak200", new GunKit("AK-200", "stalker:ak200", "stalker:545x39", 30, 6));
        kits.put("aks74", new GunKit("AKS-74", "stalker:aks74", "stalker:545x39", 30, 6));
        kits.put("aks74u", new GunKit("AKS-74U", "stalker:aks74u", "stalker:545x39", 30, 6));
        kits.put("rpk16", new GunKit("RPK-16", "stalker:rpk16", "stalker:545x39", 95, 4));
        kits.put("pkp", new GunKit("PKP", "stalker:pkp", "tacz:762x54", 70, 4));
        return Collections.unmodifiableMap(kits);
    }

    private static Map<String, ShopItem> shopItems() {
        Map<String, ShopItem> items = new LinkedHashMap<>();
        items.put("rifle", new ShopItem("rifle", 650, ShopKind.KIT, "ak101"));
        items.put("ak101", new ShopItem("ak101", 650, ShopKind.KIT, "ak101"));
        items.put("ak103", new ShopItem("ak103", 700, ShopKind.KIT, "ak103"));
        items.put("pkp", new ShopItem("pkp", 1000, ShopKind.KIT, "pkp"));
        items.put("ammo", new ShopItem("ammo", 100, ShopKind.AMMO, "tacz:556x45"));
        items.put("medkit", new ShopItem("medkit", 150, ShopKind.MEDKIT, ""));
        items.put("tent", new ShopItem("tent", 500, ShopKind.TENT, ""));
        items.put("outpost", new ShopItem("outpost", 1000, ShopKind.OUTPOST, ""));
        return Collections.unmodifiableMap(items);
    }

    private static final class TaczItems {
        private static ItemStack gun(String gunId, int ammoCount, Object registryLookup) {
            try {
                ResourceLocation id = ResourceLocation.tryParse(gunId);
                if (id == null) {
                    return ItemStack.EMPTY;
                }
                Object builder = create("com.tacz.guns.api.item.builder.GunItemBuilder");
                invoke(builder, "setId", id);
                invoke(builder, "setAmmoCount", ammoCount);
                invoke(builder, "setAmmoInBarrel", true);
                invoke(builder, "setFireMode", enumValue("com.tacz.guns.api.item.gun.FireMode", "AUTO"));
                return (ItemStack) invoke(builder, "forceBuild");
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return ItemStack.EMPTY;
            }
        }

        private static ItemStack ammo(String ammoId, int count) {
            try {
                ResourceLocation id = ResourceLocation.tryParse(ammoId);
                if (id == null) {
                    return ItemStack.EMPTY;
                }
                Object builder = create("com.tacz.guns.api.item.builder.AmmoItemBuilder");
                invoke(builder, "setId", id);
                invoke(builder, "setCount", count);
                return (ItemStack) invoke(builder, "build");
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return new ItemStack(Items.GUNPOWDER, Math.max(1, count));
            }
        }

        private static Object create(String className) throws ReflectiveOperationException {
            Class<?> type = Class.forName(className);
            return type.getMethod("create").invoke(null);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static Object enumValue(String className, String value) throws ReflectiveOperationException {
            return Enum.valueOf((Class<? extends Enum>) Class.forName(className), value);
        }
    }

    private static final class TaczEvents {
        private static void register(Consumer<Object> shoot, Consumer<Object> hurtPre, Consumer<Object> hurtPost, Consumer<Object> kill) {
            registerForgeEvent("com.tacz.guns.api.event.common.GunShootEvent", shoot);
            registerForgeEvent("com.tacz.guns.api.event.common.GunFireEvent", shoot);
            registerForgeEvent("com.tacz.guns.api.event.common.EntityHurtByGunEvent$Pre", hurtPre);
            registerForgeEvent("com.tacz.guns.api.event.common.EntityHurtByGunEvent$Post", hurtPost);
            registerForgeEvent("com.tacz.guns.api.event.common.EntityKillByGunEvent", kill);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static void registerForgeEvent(String eventClassName, Consumer<Object> consumer) {
            try {
                Class<?> eventClass = Class.forName(eventClassName);
                Method addListener = Arrays.stream(MinecraftForge.EVENT_BUS.getClass().getMethods())
                    .filter(method -> method.getName().equals("addListener"))
                    .filter(method -> method.getParameterCount() == 4)
                    .filter(method -> method.getParameterTypes()[2] == Class.class)
                    .findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("Forge EventBus addListener overload"));
                Class<? extends Enum> priorityType = (Class<? extends Enum>) addListener.getParameterTypes()[0].asSubclass(Enum.class);
                Object normalPriority = Enum.valueOf(priorityType, "NORMAL");
                addListener.invoke(MinecraftForge.EVENT_BUS, normalPriority, true, eventClass, consumer);
                System.out.println(PREFIX + "Registered TaCZ Forge event: " + eventClassName);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                System.err.println(PREFIX + "Could not register TaCZ event " + eventClassName + ": " + exception.getMessage());
            }
        }

        private static boolean isServer(Object event) {
            Object side = invokeOrNull(event, "getLogicalSide");
            Object value = side == null ? null : invokeOrNull(side, "isServer");
            if (value instanceof Boolean result) {
                return result;
            }
            for (String getter : List.of("getShooter", "getAttacker", "getHurtEntity", "getKilledEntity")) {
                Entity entity = entity(event, getter);
                if (entity != null) {
                    return !entity.level().isClientSide;
                }
            }
            return false;
        }

        private static Entity entity(Object event, String getter) {
            Object value = invokeOrNull(event, getter);
            return value instanceof Entity entity ? entity : null;
        }

        private static float floatValue(Object event, String getter) {
            Object value = invokeOrNull(event, getter);
            return value instanceof Number number ? number.floatValue() : 0.0f;
        }

        private static void cancel(Object event) {
            try {
                invoke(event, "setCanceled", true);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }

        private static Object invokeOrNull(Object target, String methodName) {
            try {
                return invoke(target, methodName);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
    }

    private static Object invoke(Object target, String methodName, Object... args) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), methodName, args.length);
        return method.invoke(target, args);
    }

    private static Method findMethod(Class<?> type, String methodName, int parameterCount) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + methodName + "/" + parameterCount);
    }
}
