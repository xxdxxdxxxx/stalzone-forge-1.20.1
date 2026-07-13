package ru.zonewars.client.net;

import ru.zonewars.forge.ZoneWarsNetwork;

public final class ZoneWarsNetworking {
    private ZoneWarsNetworking() {}
    public static void requestState() { ZoneWarsNetwork.sendAction("request_state"); }
    public static void chooseRespawn(String kind) { ZoneWarsNetwork.sendAction("respawn:" + kind); }
    public static void confirmRespawn() { ZoneWarsNetwork.sendAction("respawn:confirm"); }
    public static void sendPing(String type, int x, int z) { ZoneWarsNetwork.sendAction("ping:" + type + ":" + x + ":" + z); }
    public static void setWaypoint(int x, int z) { ZoneWarsNetwork.sendAction("waypoint:" + x + ":" + z); }
    public static void openSquadMenu() { ZoneWarsNetwork.sendAction("squad:menu"); }
}
