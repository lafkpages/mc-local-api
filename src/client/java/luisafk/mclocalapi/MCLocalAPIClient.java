package luisafk.mclocalapi;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

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

    HttpServer server;
    Handler handler;

    Vec3d lastPos = new Vec3d(0, 0, 0);
    Identifier lastWorld;
    ArrayList<HttpExchange> posSseExchanges = new ArrayList<>();

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
                    posSseExchanges.forEach(HttpExchange::close);
                    posSseExchanges.clear();
                }
                return;
            }

            boolean hasSseExchanges = !posSseExchanges.isEmpty();

            Vec3d pos = minecraftClient.player.getPos();

            if (lastPos.distanceTo(pos) > config.posSseDistanceThreshold()) {
                lastPos = pos;

                if (hasSseExchanges) {
                    String res = "data: " + pos.toString() + "\n\n";

                    logger.info("Sending pos update: {}", pos);

                    posSseExchanges.forEach(exchange -> {
                        OutputStream os = exchange.getResponseBody();
                        try {
                            os.write(res.getBytes());
                            os.flush();
                        } catch (IOException e) {
                            logger.error("Failed to write SSE response", e);
                            exchange.close();
                        }
                    });
                }
            }

            Identifier world = minecraftClient.world.getRegistryKey().getValue();

            if (lastWorld != world) {
                lastWorld = world;

                if (hasSseExchanges) {
                    String res = "event: changeworld\ndata: " + world + "\n\n";

                    logger.info("Sending world change: {}", world);

                    posSseExchanges.forEach(exchange -> {
                        OutputStream os = exchange.getResponseBody();
                        try {
                            os.write(res.getBytes());
                            os.flush();
                        } catch (IOException e) {
                            logger.error("Failed to write SSE response", e);
                            exchange.close();
                        }
                    });
                }
            }
        });
    }

    private void startServer() {
        if (server != null) {
            throw new IllegalStateException("MC Local API server is already running");
        }

        try {
            server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        } catch (IOException e) {
            logger.error("Failed to start MC Local API server", e);
            return;
        }

        handler = new Handler(this);
        server.createContext("/", handler);

        server.setExecutor(null);
        server.start();

        logger.info("MC Local API server started on {}", server.getAddress());
    }

    private void stopServer() {
        if (server == null) {
            throw new IllegalStateException("MC Local API server is not running");
        }

        server.stop(0);
        server = null;

        posSseExchanges.clear();

        logger.info("MC Local API server stopped");

    }

    static class Handler implements HttpHandler {
        MCLocalAPIClient modInstance;

        public Handler(MCLocalAPIClient mcLocalAPIClient) {
            modInstance = mcLocalAPIClient;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Server", "MC Local API v" + modVersion);

            if (config.enableCors()) {
                headers.set("Access-Control-Allow-Origin", "*");
                headers.set("Access-Control-Allow-Methods", "GET");
            }

            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (!path.endsWith("/")) {
                path += "/";
            }
            String id = method + " " + path;

            if (!config.enableEndpointPos() && path.startsWith("/pos/")) {
                exchange.sendResponseHeaders(403, -1);
                exchange.getResponseBody().close();
                return;
            }

            if (!config.enableEndpointChat() && path.startsWith("/chat/")) {
                exchange.sendResponseHeaders(403, -1);
                exchange.getResponseBody().close();
                return;
            }

            if (!config.enableEndpointChatCommand() && path.startsWith("/chat/command/")) {
                exchange.sendResponseHeaders(403, -1);
                exchange.getResponseBody().close();
                return;
            }

            if (!config.enableEndpointScreen() && path.startsWith("/screen/")) {
                exchange.sendResponseHeaders(403, -1);
                exchange.getResponseBody().close();
                return;
            }

            switch (id) {
                case "GET /":
                    handleRoot(exchange);
                    break;

                case "GET /pos/":
                    handlePos(exchange);
                    break;

                case "GET /pos/world/":
                    handlePosWorld(exchange);
                    break;

                case "GET /pos/sse/":
                    handlePosSse(exchange);
                    break;

                case "POST /chat/":
                    handleChat(exchange);
                    break;

                case "POST /chat/command/":
                    handleChatCommand(exchange);
                    break;

                case "GET /screen/":
                    handleScreen(exchange);
                    break;

                default:
                    exchange.sendResponseHeaders(404, -1);
                    exchange.getResponseBody().close();
            }
        }

        private boolean requirePlayer(HttpExchange exchange) throws IOException {
            MinecraftClient client = MinecraftClient.getInstance();

            if (client.player == null) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return true;
            }

            return false;
        }

        private void handleRoot(HttpExchange exchange) throws IOException {
            String res = "MC Local API v" + modVersion + " running";

            exchange.sendResponseHeaders(200, res.length());
            exchange.getResponseBody().write(res.getBytes());
            exchange.close();
        }

        private void handlePos(HttpExchange exchange) throws IOException {
            if (requirePlayer(exchange)) {
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            String res = client.player.getPos().toString();

            exchange.sendResponseHeaders(200, res.length());
            exchange.getResponseBody().write(res.getBytes());
            exchange.close();
        }

        private void handlePosWorld(HttpExchange exchange) throws IOException {
            if (requirePlayer(exchange)) {
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            Identifier world = client.world.getRegistryKey().getValue();
            String res = world.toString();

            exchange.sendResponseHeaders(200, res.length());
            exchange.getResponseBody().write(res.getBytes());
            exchange.close();

        }

        private void handlePosSse(HttpExchange exchange) throws IOException {
            if (requirePlayer(exchange)) {
                return;
            }

            Headers resHeaders = exchange.getResponseHeaders();
            resHeaders.set("Content-Type", "text/event-stream");
            resHeaders.set("Connection", "keep-alive");
            resHeaders.set("Transfer-Encoding", "chunked");
            exchange.sendResponseHeaders(200, 0);

            MinecraftClient client = MinecraftClient.getInstance();
            String res = "data: " + client.player.getPos().toString() + "\n\n";
            exchange.getResponseBody().write(res.getBytes());

            modInstance.posSseExchanges.add(exchange);
        }

        private void handleChat(HttpExchange exchange) throws IOException {
            if (requirePlayer(exchange)) {
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            String message = new String(exchange.getRequestBody().readAllBytes());

            if (message.isEmpty()) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            client.player.networkHandler.sendChatMessage(message);

            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }

        private void handleChatCommand(HttpExchange exchange) throws IOException {
            if (requirePlayer(exchange)) {
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            String command = new String(exchange.getRequestBody().readAllBytes());

            if (command.isEmpty()) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            client.player.networkHandler.sendChatCommand(command);

            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }

        private void handleScreen(HttpExchange exchange) throws IOException {
            if (requirePlayer(exchange)) {
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();

            if (client.currentScreen == null) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            byte[] res = client.currentScreen.getTitle().getString().getBytes();

            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/plain; charset=UTF-8");

            exchange.sendResponseHeaders(200, res.length);
            exchange.getResponseBody().write(res);
            exchange.close();
        }
    }
}
