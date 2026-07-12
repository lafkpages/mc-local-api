package luisafk.mclocalapi;

import com.mojang.brigadier.Command;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import luisafk.mclocalapi.rest.RestApiProvider;
import luisafk.mclocalapi.rest.SseConnection;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCLocalAPIClient implements ClientModInitializer {

    public static final MCLocalAPIConfig config =
        MCLocalAPIConfig.createAndLoad();

    public static final Minecraft mc = Minecraft.getInstance();
    public static final FabricLoader fabricLoader = FabricLoader.getInstance();

    public static final Logger logger = LoggerFactory.getLogger("mc-local-api");
    public static final Version modVersion = fabricLoader
        .getModContainer("mc-local-api")
        .get()
        .getMetadata()
        .getVersion();

    private HttpServer server;

    Vec3 lastPos;
    String lastWorld;

    public static final List<SseConnection> posSseClients =
        new CopyOnWriteArrayList<>();

    @Override
    public void onInitializeClient() {
        if (config.autoStart) {
            startServer();
        }

        ClientCommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess) -> {
                dispatcher.register(
                    ClientCommands.literal("startserver").executes(context -> {
                        startServer();
                        return Command.SINGLE_SUCCESS;
                    })
                );
            }
        );

        ClientCommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess) -> {
                dispatcher.register(
                    ClientCommands.literal("stopserver").executes(context -> {
                        stopServer();
                        context
                            .getSource()
                            .sendFeedback(
                                Component.literal(
                                    "MC Local API server stopped"
                                ).withStyle(ChatFormatting.YELLOW)
                            );
                        return Command.SINGLE_SUCCESS;
                    })
                );
            }
        );

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (mc.player == null) {
                if (config.closePlayerPositionStreams) {
                    posSseClients.forEach(SseConnection::close);
                    posSseClients.clear();
                }
                return;
            }

            Vec3 pos = mc.player.position();
            String world = mc.level.dimension().identifier().toString();

            boolean didPositionChange =
                lastPos == null ||
                lastPos.distanceTo(pos) >
                    config.playerPositionStreamDistanceThreshold;
            boolean didWorldChange =
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
            server = HttpServer.create(new InetSocketAddress(config.port), 0);
            new RestApiProvider(server).defineRoutes();
            server.start();
        } catch (IOException e) {
            logger.error(
                "Failed to start MC Local API server on port {}: {}",
                config.port,
                e.getMessage()
            );

            if (mc.player != null) {
                mc.player.sendSystemMessage(
                    Component.literal(
                        "Failed to start MC Local API server on port " +
                            config.port +
                            ": " +
                            e.getMessage()
                    ).withStyle(ChatFormatting.RED)
                );
            }

            return false;
        }

        logger.info(
            "MC Local API server started on port {}",
            server.getAddress().getPort()
        );
        if (mc.player != null) {
            mc.player.sendSystemMessage(
                Component.literal(
                    "MC Local API server started on port " + config.port
                ).withStyle(ChatFormatting.GREEN)
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

        posSseClients.forEach(SseConnection::close);
        posSseClients.clear();

        server.stop(0);
        server = null;

        logger.info("MC Local API server stopped");
    }
}
