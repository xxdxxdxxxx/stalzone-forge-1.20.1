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

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
        ru.zonewars.forge.menu.ZoneWarsMenus.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
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
        if (action.equalsIgnoreCase("inventory:open")) {
            net.minecraftforge.network.NetworkHooks.openScreen(player,
                new net.minecraft.world.SimpleMenuProvider(
                    (windowId, inventory, ignored) -> new ru.zonewars.forge.menu.ZoneInventoryMenu(windowId, inventory),
                    net.minecraft.network.chat.Component.literal("Tactical Inventory")));
            return;
        }
        if (action.toLowerCase(Locale.ROOT).startsWith("buy:")) {
            buyForPlayer(player, action.substring("buy:".length()));
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
            Optional<RespawnKind> kind = RespawnKind.parse(action.substring("respawn:".length()));
            if (kind.isPresent()) {
                respawns.select(player.getUUID(), kind.get());
                clientBridge.sendState(player);
            }
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
        limits.put(id, now + actionCooldownMillis(action, stateRequest));
        return true;
    }

    private long actionCooldownMillis(String action, boolean stateRequest) {
        if (stateRequest) return 2_000L;
        String lower = action.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ping:") || lower.startsWith("waypoint:")) return 500L;
        if (lower.startsWith("buy:")) return 250L;
        return 100L;
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
            boolean inside = sameWorld && ZoneWarsRules.insideCaptureCylinder(dx, dy, dz, point.data().radius());
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
            UUID playerId = player.getUUID();
            if (!matches.isParticipant(playerId) || !awaitingRespawn.contains(playerId)) {
                return false;
            }
            RespawnPoint point = resolve(player).orElse(null);
            if (point == null) {
                awaitingRespawn.remove(playerId);
                matches.teleportToBase(player);
                return true;
            }
            long now = System.currentTimeMillis();
            if (point.availableAt() > now) {
                tell(player, point.name() + " is available in " + secondsUntil(point) + "s. Select base or wait.");
                return false;
            }
            awaitingRespawn.remove(playerId);
            matches.teleport(player, point.location());
            cooldownUntil.put(playerId, now + (point.kind() == RespawnKind.TENT ? 10_000L : 50_000L));
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

    /**
     * Native Forge EventBus adapter for TaCZ 1.1.8 combat events.
     * Compiles without the TaCZ jar: event classes are resolved by name at
     * runtime, but listeners are registered on the real MinecraftForge.EVENT_BUS.
     */
    static final class TaczEvents {
        private static final String PKG = "com.tacz.guns.api.event.common.";
        private static final Map<String, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();
        private static boolean registered;

        private TaczEvents() {}

        static synchronized void register(Consumer<Object> shoot, Consumer<Object> hurtPre,
                                          Consumer<Object> hurtPost, Consumer<Object> kill) {
            if (registered) return;
            registered = true;
            int hooked = 0;
            hooked += hook(PKG + "GunShootEvent", shoot);
            int hurt = hook(PKG + "EntityHurtByGunEvent$Pre", hurtPre)
                     + hook(PKG + "EntityHurtByGunEvent$Post", hurtPost);
            if (hurt == 0) {
                hurt = hook(PKG + "EntityHurtByGunEvent", hurtPost);
            }
            hooked += hurt;
            hooked += hook(PKG + "EntityKillByGunEvent", kill);
            if (hooked == 0) {
                System.err.println("[ZoneWars] TaCZ not detected; using generic Forge damage events only.");
            } else {
                System.out.println("[ZoneWars] Hooked " + hooked + " native TaCZ EventBus listeners.");
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static int hook(String className, Consumer<Object> handler) {
            try {
                Class<?> type = Class.forName(className);
                if (!Event.class.isAssignableFrom(type)) return 0;
                MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class) type, (Consumer) handler);
                return 1;
            } catch (ClassNotFoundException absent) {
                return 0;
            } catch (Throwable error) {
                System.err.println("[ZoneWars] Failed to hook TaCZ event " + className + ": " + error);
                return 0;
            }
        }

        static boolean isServer(Object event) {
            Object side = invokeOrNull(event, "getLogicalSide");
            return side != null && "SERVER".equals(String.valueOf(side));
        }

        static void cancel(Object event) {
            if (event instanceof Event forgeEvent && forgeEvent.isCancelable()) {
                forgeEvent.setCanceled(true);
            }
        }

        static Entity entity(Object event, String accessor) {
            Object value = invokeOrNull(event, accessor);
            return value instanceof Entity entity ? entity : null;
        }

        static float floatValue(Object event, String accessor) {
            Object value = invokeOrNull(event, accessor);
            return value instanceof Number number ? number.floatValue() : 0F;
        }

        static Object invokeOrNull(Object event, String accessor) {
            if (event == null) return null;
            String key = event.getClass().getName() + "#" + accessor;
            Optional<Method> method = METHOD_CACHE.computeIfAbsent(key, ignored -> {
                try {
                    return Optional.of(event.getClass().getMethod(accessor));
                } catch (NoSuchMethodException missing) {
                    return Optional.empty();
                }
            });
            if (method.isEmpty()) return null;
            try {
                return method.get().invoke(event);
            } catch (Throwable error) {
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
}