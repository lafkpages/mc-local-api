package luisafk.mclocalapi;

import com.mojang.brigadier.Command;
import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
import io.javalin.util.JavalinBindException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import luisafk.mclocalapi.rest.RestApiProvider;
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
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCLocalAPIClient implements ClientModInitializer {

    public static final MCLocalAPIConfig config =
        MCLocalAPIConfig.createAndLoad();

    public static final MinecraftClient mc = MinecraftClient.getInstance();
    public static final FabricLoader fabricLoader = FabricLoader.getInstance();

    public static final Logger logger = LoggerFactory.getLogger("mc-local-api");
    public static final Version modVersion = fabricLoader
        .getModContainer("mc-local-api")
        .get()
        .getMetadata()
        .getVersion();

    private Javalin server;

    Vec3d lastPos;
    String lastWorld;

    public static final List<SseClient> posSseClients =
        new CopyOnWriteArrayList<>();

    @Override
    public void onInitializeClient() {
        if (config.autoStart()) {
            startServer();
        }

        ClientCommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess) -> {
                dispatcher.register(
                    ClientCommandManager.literal("startserver").executes(
                        context -> {
                            startServer();
                            return Command.SINGLE_SUCCESS;
                        }
                    )
                );
            }
        );

        ClientCommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess) -> {
                dispatcher.register(
                    ClientCommandManager.literal("stopserver").executes(
                        context -> {
                            stopServer();
                            context
                                .getSource()
                                .sendFeedback(
                                    Text.literal(
                                        "MC Local API server stopped"
                                    ).formatted(Formatting.YELLOW)
                                );
                            return Command.SINGLE_SUCCESS;
                        }
                    )
                );
            }
        );

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (mc.player == null) {
                if (config.closePlayerPositionStreams()) {
                    posSseClients.forEach(SseClient::close);
                    posSseClients.clear();
                }
                return;
            }

            Vec3d pos = mc.player.getPos();
            String world = mc.world.getRegistryKey().getValue().toString();

            Boolean didPositionChange =
                lastPos == null ||
                lastPos.distanceTo(pos) >
                    config.playerPositionStreamDistanceThreshold();
            Boolean didWorldChange =
                lastWorld == null || !lastWorld.equals(world);

            if (didPositionChange) {
                lastPos = pos;

                posSseClients.forEach(sse -> {
                    try {
                        sse.sendEvent(pos.toString());
                    } catch (Exception e) {
                        logger.error(
                            "Error sending position update to SSE client",
                            e
                        );
                        sse.close();
                    }
                });
            }

            if (didWorldChange) {
                lastWorld = world;

                posSseClients.forEach(sse -> {
                    try {
                        sse.sendEvent("changeworld", world);
                    } catch (Exception e) {
                        logger.error(
                            "Error sending world update to SSE client",
                            e
                        );
                        sse.close();
                    }
                });
            }
        });
    }

    private boolean startServer() {
        if (server != null) {
            throw new IllegalStateException(
                "MC Local API server is already running"
            );
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
                serverConfig.bundledPlugins.enableGlobalHeaders(
                    globalHeaders -> {
                        globalHeaders
                            .getHeaders()
                            .put(
                                "Server",
                                "MC Local API v" +
                                    modVersion +
                                    ", Minecraft " +
                                    SharedConstants.getGameVersion().id()
                            );
                    }
                );
            }).start(config.port());
        } catch (JavalinBindException e) {
            logger.error(
                "Failed to start MC Local API server on port {}: {}",
                config.port(),
                e.getMessage()
            );

            if (mc.player != null) {
                mc.player.sendMessage(
                    Text.literal(
                        "Failed to start MC Local API server on port " +
                            config.port() +
                            ": " +
                            e.getMessage()
                    ).formatted(Formatting.RED),
                    false
                );
            }

            return false;
        }

        // Define REST routes via RestApiProvider
        new RestApiProvider(server).defineRoutes();

        logger.info("MC Local API server started on port {}", server.port());
        if (mc.player != null) {
            mc.player.sendMessage(
                Text.literal(
                    "MC Local API server started on port " + config.port()
                ).formatted(Formatting.GREEN),
                false
            );
        }

        return true;
    }

    private void stopServer() {
        if (server == null) {
            throw new IllegalStateException(
                "MC Local API server is not running"
            );
        }

        server.stop();
        server = null;

        posSseClients.clear();

        logger.info("MC Local API server stopped");
    }
}
