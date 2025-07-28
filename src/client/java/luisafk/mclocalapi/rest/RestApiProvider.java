package luisafk.mclocalapi.rest;

import static luisafk.mclocalapi.MCLocalAPIClient.config;
import static luisafk.mclocalapi.MCLocalAPIClient.fabricLoader;
import static luisafk.mclocalapi.MCLocalAPIClient.mc;
import static luisafk.mclocalapi.MCLocalAPIClient.modVersion;
import static luisafk.mclocalapi.MCLocalAPIClient.posSseClients;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.http.sse.SseClient;
import net.minecraft.SharedConstants;
import net.minecraft.util.Identifier;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

public class RestApiProvider {
    private Javalin server;

    public RestApiProvider(Javalin server) {
        this.server = server;
    }

    public void defineRoutes() {
        server.get("/", this::handleRoot);

        // Protect RESTful endpoints
        protectEndpoint("/chat/commands", () -> config.enableEndpointChatCommands());
        protectEndpoint("/chat/messages", () -> config.enableEndpointChatMessages());
        protectEndpoint("/mods", () -> config.enableEndpointMods());
        protectEndpoint("/player/position", () -> config.enableEndpointPlayerPosition());
        protectEndpoint("/player/position/stream", () -> config.enableEndpointPlayerPositionStream());
        protectEndpoint("/player/world", () -> config.enableEndpointPlayerWorld());
        protectEndpoint("/screen", () -> config.enableEndpointScreen());
        protectEndpoint("/xaero/waypoint-sets", () -> config.enableEndpointXaeroWaypointSets());

        // RESTful routes
        server.post("/chat/commands", this::handlePostChatCommands);
        server.post("/chat/messages", this::handlePostChatMessages);
        server.get("/mods", this::handleGetMods);
        server.get("/player/position", this::handleGetPlayerPosition);
        server.sse("/player/position/stream", this::handlePlayerPositionStream);
        server.get("/player/world", this::handleGetPlayerWorld);
        server.get("/screen", this::handleGetScreen);
        server.get("/xaero/waypoint-sets", this::handleGetXaeroWaypointSets);
        server.post("/xaero/waypoint-sets", this::handlePostXaeroWaypointSets);
    }

    private void protectEndpoint(String path, Supplier<Boolean> enabledCheck) {
        server.before(path, ctx -> {
            if (!enabledCheck.get()) {
                throw new EndpointDisabledResponse();
            }
        });
    }

    private void requirePlayer() {
        if (mc.player == null) {
            throw new PlayerUnavailableResponse();
        }
    }

    private void handleRoot(Context ctx) {
        ctx.result("MC Local API v" + modVersion + " running on Minecraft "
                + mc.getGameVersion() + " "
                + SharedConstants.getGameVersion().name());
    }

    private void handlePostChatCommands(Context ctx) {
        requirePlayer();

        String command = ctx.body();

        if (command.isEmpty()) {
            throw new BadRequestResponse("Command cannot be empty");
        }

        mc.player.networkHandler.sendChatCommand(command);
    }

    private void handlePostChatMessages(Context ctx) {
        requirePlayer();

        String message = ctx.body();

        if (message.isEmpty()) {
            throw new BadRequestResponse("Message cannot be empty");
        }

        mc.player.networkHandler.sendChatMessage(message);
    }

    private void handleGetMods(Context ctx) {
        Map<String, String> mods = new HashMap<>();

        fabricLoader.getAllMods().forEach(modContainer -> {
            var metadata = modContainer.getMetadata();
            mods.put(metadata.getId(), metadata.getVersion().getFriendlyString());
        });

        ctx.json(mods);
    }

    private void handleGetPlayerPosition(Context ctx) {
        requirePlayer();
        ctx.result(mc.player.getPos().toString());
    }

    private void handlePlayerPositionStream(SseClient sse) {
        requirePlayer();

        sse.keepAlive();
        sse.sendEvent(mc.player.getPos().toString());

        posSseClients.add(sse);

        sse.onClose(() -> posSseClients.remove(sse));
    }

    private void handleGetPlayerWorld(Context ctx) {
        requirePlayer();

        Identifier world = mc.world.getRegistryKey().getValue();

        ctx.result(world.toString());
    }

    private void handleGetScreen(Context ctx) {
        requirePlayer();

        if (mc.currentScreen == null) {
            ctx.status(204);
            return;
        }

        ctx.result(mc.currentScreen.getTitle().getString());
    }

    private void handleGetXaeroWaypointSets(Context ctx) {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();

        if (session == null) {
            throw new ServiceUnavailableResponse("No Xaero's Minimap session available");
        }

        MinimapWorld world = session.getWorldManager().getCurrentWorld();

        List<WaypointSet> allWaypoints = new ArrayList<>();
        for (WaypointSet set : world.getIterableWaypointSets()) {
            allWaypoints.add(set);
        }

        ctx.json(allWaypoints);
    }

    private void handlePostXaeroWaypointSets(Context ctx) {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();

        if (session == null) {
            throw new ServiceUnavailableResponse("No Xaero's Minimap session available");
        }

        String setName = ctx.body();

        if (setName.isEmpty()) {
            throw new BadRequestResponse("Set name cannot be empty");
        }

        MinimapWorld world = session.getWorldManager().getCurrentWorld();

        world.addWaypointSet(setName);

        ctx.json(world.getWaypointSet(setName));
    }
}
