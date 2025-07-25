package luisafk.mclocalapi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.http.sse.SseClient;
import io.javalin.util.JavalinBindException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

public class MCLocalAPIClient implements ClientModInitializer {
    public static final luisafk.mclocalapi.MCLocalAPIConfig config = luisafk.mclocalapi.MCLocalAPIConfig
            .createAndLoad();

    public static final Logger logger = LoggerFactory.getLogger("mc-local-api");
    public static final Version modVersion = FabricLoader.getInstance().getModContainer("mc-local-api").get()
            .getMetadata()
            .getVersion();

    public static final MinecraftClient mc = MinecraftClient.getInstance();

    private Javalin server;

    Vec3d lastPos = new Vec3d(0, 0, 0);
    Identifier lastWorld;
    List<SseClient> posSseClients = new CopyOnWriteArrayList<>();

    @Override
    public void onInitializeClient() {
        if (config.autoStart()) {
            startServer();
        }

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("startserver").executes(context -> {
                startServer();
                return Command.SINGLE_SUCCESS;
            }));
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("stopserver").executes(context -> {
                stopServer();
                context.getSource()
                        .sendFeedback(Text.literal("MC Local API server stopped").formatted(Formatting.YELLOW));
                return Command.SINGLE_SUCCESS;
            }));
        });

        ClientTickEvents.START_CLIENT_TICK.register((mc) -> {
            if (mc.player == null) {
                if (config.closePlayerPositionStreams()) {
                    posSseClients.forEach(SseClient::close);
                    posSseClients.clear();
                }
                return;
            }

            Vec3d pos = mc.player.getPos();

            if (lastPos.distanceTo(pos) > config.playerPositionStreamDistanceThreshold()) {
                lastPos = pos;

                posSseClients.forEach(sse -> {
                    try {
                        sse.sendEvent(pos.toString());
                    } catch (Exception e) {
                        logger.error("Error sending position update to SSE client", e);
                        sse.close();
                    }
                });
            }

            Identifier world = mc.world.getRegistryKey().getValue();

            if (lastWorld != world) {
                lastWorld = world;

                posSseClients.forEach(sse -> {
                    try {
                        sse.sendEvent("changeworld", world);
                    } catch (Exception e) {
                        logger.error("Error sending world update to SSE client", e);
                        sse.close();
                    }
                });
            }
        });
    }

    private boolean startServer() {
        if (server != null) {
            throw new IllegalStateException("MC Local API server is already running");
        }

        try {
            // Create and configure the Javalin server
            server = Javalin.create(serverConfig -> {
                // Enable CORS if configured
                if (config.enableCors()) {
                    serverConfig.bundledPlugins.enableCors(cors -> {
                        cors.addRule(it -> {
                            it.anyHost();
                        });
                    });
                }

                // Add a custom header to all responses
                // serverConfig.http.addResponseHeader("Server", "MC Local API v" + modVersion);
                serverConfig.bundledPlugins.enableGlobalHeaders(globalHeaders -> {
                    globalHeaders.getHeaders().put("Server", "MC Local API v" + modVersion + ", Minecraft "
                            + SharedConstants.getGameVersion().id());
                });
            }).start(config.port());
        } catch (JavalinBindException e) {
            logger.error("Failed to start MC Local API server on port {}: {}", config.port(), e.getMessage());

            if (mc.player != null) {
                mc.player.sendMessage(Text
                        .literal("Failed to start MC Local API server on port " + config.port() + ": " + e.getMessage())
                        .formatted(Formatting.RED), false);
            }

            return false;
        }

        // Define your routes
        defineRoutes();

        logger.info("MC Local API server started on port {}", server.port());
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("MC Local API server started on port " + config.port())
                    .formatted(Formatting.GREEN), false);
        }

        return true;
    }

    private void stopServer() {
        if (server == null) {
            throw new IllegalStateException("MC Local API server is not running");
        }

        server.stop();
        server = null;

        posSseClients.clear();

        logger.info("MC Local API server stopped");
    }

    private void defineRoutes() {
        server.get("/", ctx -> {
            ctx.result("MC Local API v" + modVersion + " running on Minecraft "
                    + mc.getGameVersion() + " "
                    + SharedConstants.getGameVersion().name());
        });

        // Protect new RESTful endpoints
        protectEndpoint("/player/position", () -> config.enableEndpointPlayerPosition());
        protectEndpoint("/player/world", () -> config.enableEndpointPlayerWorld());
        protectEndpoint("/player/position/stream", () -> config.enableEndpointPlayerPositionStream());
        protectEndpoint("/screen", () -> config.enableEndpointScreen());
        protectEndpoint("/chat/messages", () -> config.enableEndpointChatMessages());
        protectEndpoint("/chat/commands", () -> config.enableEndpointChatCommands());
        protectEndpoint("/xaero/waypoint-sets", () -> config.enableEndpointXaeroWaypointSets());

        // RESTful routes
        server.get("/player/position", this::handleGetPlayerPosition);
        server.get("/player/world", this::handleGetPlayerWorld);
        server.sse("/player/position/stream", this::handlePlayerPositionStream);
        server.get("/screen", this::handleGetScreen);
        server.post("/chat/messages", this::handlePostChatMessages);
        server.post("/chat/commands", this::handlePostChatCommands);
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

    private void handleGetPlayerPosition(Context ctx) {
        requirePlayer();

        ctx.result(mc.player.getPos().toString());
    }

    private void handleGetPlayerWorld(Context ctx) {
        requirePlayer();

        Identifier world = mc.world.getRegistryKey().getValue();
        ctx.result(world.toString());
    }

    private void handlePlayerPositionStream(SseClient sse) {
        requirePlayer();

        sse.keepAlive();

        sse.sendEvent(mc.player.getPos().toString());

        posSseClients.add(sse);
        sse.onClose(() -> posSseClients.remove(sse));
    }

    private void handlePostChatMessages(Context ctx) {
        requirePlayer();

        String message = ctx.body();

        if (message.isEmpty()) {
            throw new BadRequestResponse("Message cannot be empty");
        }

        mc.player.networkHandler.sendChatMessage(message);
    }

    private void handlePostChatCommands(Context ctx) {
        requirePlayer();

        String command = ctx.body();

        if (command.isEmpty()) {
            throw new BadRequestResponse("Command cannot be empty");
        }

        mc.player.networkHandler.sendChatCommand(command);
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

        // Array of WaypointSet arrays to hold all waypoints
        List<WaypointSet> allWaypoints = new ArrayList<WaypointSet>();

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
