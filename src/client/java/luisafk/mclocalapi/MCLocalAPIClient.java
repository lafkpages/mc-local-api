package luisafk.mclocalapi;

import java.util.ArrayList;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.http.sse.SseClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

public class MCLocalAPIClient implements ClientModInitializer {
    public static final luisafk.mclocalapi.MCLocalAPIConfig config = luisafk.mclocalapi.MCLocalAPIConfig
            .createAndLoad();

    public static final Logger logger = LoggerFactory.getLogger("mc-local-api");
    public static final Version modVersion = FabricLoader.getInstance().getModContainer("mc-local-api").get()
            .getMetadata()
            .getVersion();

    private Javalin server;

    Vec3d lastPos = new Vec3d(0, 0, 0);
    Identifier lastWorld;
    ArrayList<SseClient> posSseClients = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        if (config.autoStart()) {
            startServer();
        }

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("startserver").executes(context -> {
                startServer();
                context.getSource()
                        .sendFeedback(Text.literal("MC Local API server started").formatted(Formatting.GREEN));
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

        ClientTickEvents.START_CLIENT_TICK.register((minecraftClient) -> {
            if (minecraftClient.player == null) {
                if (config.posSseClose()) {
                    posSseClients.forEach(SseClient::close);
                    posSseClients.clear();
                }
                return;
            }

            Vec3d pos = minecraftClient.player.getPos();

            if (lastPos.distanceTo(pos) > config.posSseDistanceThreshold()) {
                lastPos = pos;

                posSseClients.forEach(sse -> {
                    sse.sendEvent(pos.toString());
                });
            }

            Identifier world = minecraftClient.world.getRegistryKey().getValue();

            if (lastWorld != world) {
                lastWorld = world;

                posSseClients.forEach(sse -> {
                    sse.sendEvent("changeworld", world);
                });
            }
        });
    }

    private void startServer() {
        if (server != null) {
            throw new IllegalStateException("MC Local API server is already running");
        }

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
            serverConfig.bundledPlugins.enableGlobalHeaders(headers -> {
                headers.getHeaders().put("Server", "MC Local API v" + modVersion);
            });
        }).start(config.port());

        // Define your routes
        defineRoutes();

        logger.info("MC Local API server started on port {}", server.port());
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
        protectEndpoint("/pos/*", () -> config.enableEndpointPos());
        protectEndpoint("/screen/*", () -> config.enableEndpointScreen());
        protectEndpoint("/chat", () -> config.enableEndpointChat());
        protectEndpoint("/chat/command", () -> config.enableEndpointChatCommand());

        server.get("/", ctx -> {
            ctx.result("MC Local API v" + modVersion + " running");
        });

        server.get("/pos", this::handlePos);
        server.get("/pos/world", this::handlePosWorld);
        server.sse("/pos/sse", this::handlePosSse);
        server.get("/screen", this::handleScreen);
        server.post("/chat", this::handleChat);
        server.post("/chat/command", this::handleChatCommand);
    }

    private void protectEndpoint(String path, Supplier<Boolean> enabledCheck) {
        server.before(path, ctx -> {
            if (!enabledCheck.get()) {
                throw new EndpointDisabledResponse();
            }
        });
    }

    private void requirePlayer() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player == null) {
            throw new ServiceUnavailableResponse("Player not available");
        }
    }

    private void handlePos(Context ctx) {
        requirePlayer();

        MinecraftClient client = MinecraftClient.getInstance();
        ctx.result(client.player.getPos().toString());
    }

    private void handlePosWorld(Context ctx) {
        requirePlayer();

        MinecraftClient client = MinecraftClient.getInstance();
        Identifier world = client.world.getRegistryKey().getValue();
        ctx.result(world.toString());
    }

    private void handlePosSse(SseClient sse) {
        requirePlayer();

        sse.keepAlive();

        MinecraftClient client = MinecraftClient.getInstance();
        sse.sendEvent(client.player.getPos().toString());

        posSseClients.add(sse);
        sse.onClose(() -> posSseClients.remove(sse));
    }

    private void handleChat(Context ctx) {
        requirePlayer();

        MinecraftClient client = MinecraftClient.getInstance();
        String message = ctx.body();

        if (message.isEmpty()) {
            throw new BadRequestResponse("Message cannot be empty");
        }

        client.player.networkHandler.sendChatMessage(message);
    }

    private void handleChatCommand(Context ctx) {
        requirePlayer();

        MinecraftClient client = MinecraftClient.getInstance();
        String command = ctx.body();

        if (command.isEmpty()) {
            throw new BadRequestResponse("Command cannot be empty");
        }

        client.player.networkHandler.sendChatCommand(command);
    }

    private void handleScreen(Context ctx) {
        requirePlayer();

        MinecraftClient client = MinecraftClient.getInstance();

        if (client.currentScreen == null) {
            ctx.status(204);
            return;
        }

        ctx.result(client.currentScreen.getTitle().getString());
    }

}
