package luisafk.mclocalapi.rest;

import static luisafk.mclocalapi.MCLocalAPIClient.config;
import static luisafk.mclocalapi.MCLocalAPIClient.fabricLoader;
import static luisafk.mclocalapi.MCLocalAPIClient.mc;
import static luisafk.mclocalapi.MCLocalAPIClient.modVersion;
import static luisafk.mclocalapi.MCLocalAPIClient.posSseClients;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.util.Identifier;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

public class RestApiProvider {

    private final HttpServer server;
    private final Gson gson = new Gson();

    public RestApiProvider(HttpServer server) {
        this.server = server;
    }

    public void defineRoutes() {
        register("/", "GET", this::handleRoot);

        registerProtected(
            "/chat/commands",
            "POST",
            () -> config.enableEndpointChatCommands,
            this::handlePostChatCommands
        );
        registerProtected(
            "/chat/messages",
            "POST",
            () -> config.enableEndpointChatMessages,
            this::handlePostChatMessages
        );
        registerProtected(
            "/mods",
            "GET",
            () -> config.enableEndpointMods,
            this::handleGetMods
        );
        registerProtected(
            "/player/position",
            "GET",
            () -> config.enableEndpointPlayerPosition,
            this::handleGetPlayerPosition
        );
        registerProtected(
            "/player/position/stream",
            () -> config.enableEndpointPlayerPositionStream,
            this::handlePlayerPositionStream
        );
        registerProtected(
            "/player/world",
            "GET",
            () -> config.enableEndpointPlayerWorld,
            this::handleGetPlayerWorld
        );
        registerProtected(
            "/screen",
            "GET",
            () -> config.enableEndpointScreen,
            this::handleGetScreen
        );
        registerProtected(
            "/xaero/waypoint-sets",
            () -> config.enableEndpointXaeroWaypointSets,
            exchange -> {
                switch (exchange.getRequestMethod().toUpperCase()) {
                    case "GET" -> handleGetXaeroWaypointSets(exchange);
                    case "POST" -> handlePostXaeroWaypointSets(exchange);
                    default -> throw new ApiException(
                        405,
                        "Method Not Allowed"
                    );
                }
            }
        );
    }

    private void register(String path, String method, HttpHandler handler) {
        server.createContext(
            path,
            new ExchangeWrapper(exchange -> {
                if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
                    throw new ApiException(405, "Method Not Allowed");
                }
                handler.handle(exchange);
            })
        );
    }

    private void registerProtected(
        String path,
        String method,
        Supplier<Boolean> enabledCheck,
        HttpHandler handler
    ) {
        registerProtected(path, enabledCheck, exchange -> {
            if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
                throw new ApiException(405, "Method Not Allowed");
            }
            handler.handle(exchange);
        });
    }

    private void registerProtected(
        String path,
        Supplier<Boolean> enabledCheck,
        HttpHandler handler
    ) {
        server.createContext(
            path,
            new ExchangeWrapper(exchange -> {
                if (!enabledCheck.get()) {
                    throw new ApiException(
                        403,
                        "This endpoint is disabled in the user's configuration"
                    );
                }
                handler.handle(exchange);
            })
        );
    }

    private void requirePlayer() {
        if (mc.player == null) {
            throw new ApiException(503, "Player not available");
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        String text =
            "MC Local API v" +
            modVersion +
            " running on Minecraft " +
            mc.getGameVersion() +
            " " +
            SharedConstants.getGameVersion().name();
        sendText(exchange, 200, text);
    }

    private void handlePostChatCommands(HttpExchange exchange)
        throws IOException {
        requirePlayer();

        String command = readBody(exchange);
        if (command.isEmpty()) {
            throw new ApiException(400, "Command cannot be empty");
        }

        mc.player.networkHandler.sendChatCommand(command);
        exchange.sendResponseHeaders(204, -1);
    }

    private void handlePostChatMessages(HttpExchange exchange)
        throws IOException {
        requirePlayer();

        String message = readBody(exchange);
        if (message.isEmpty()) {
            throw new ApiException(400, "Message cannot be empty");
        }

        mc.player.networkHandler.sendChatMessage(message);
        exchange.sendResponseHeaders(204, -1);
    }

    private void handleGetMods(HttpExchange exchange) throws IOException {
        Map<String, String> mods = new HashMap<>();

        fabricLoader.getAllMods().forEach(modContainer -> {
            var metadata = modContainer.getMetadata();
            mods.put(
                metadata.getId(),
                metadata.getVersion().getFriendlyString()
            );
        });

        sendJson(exchange, mods);
    }

    private void handleGetPlayerPosition(HttpExchange exchange)
        throws IOException {
        requirePlayer();
        sendText(exchange, 200, mc.player.getPos().toString());
    }

    private void handlePlayerPositionStream(HttpExchange exchange)
        throws IOException {
        requirePlayer();

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        SseConnection sse = new SseConnection(exchange);
        sse.sendEvent(mc.player.getPos().toString());

        Thread handlerThread = Thread.currentThread();
        sse.onClose(() -> {
            posSseClients.remove(sse);
            handlerThread.interrupt();
        });
        posSseClients.add(sse);

        try {
            while (!sse.isClosed()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            sse.close();
        }
    }

    private void handleGetPlayerWorld(HttpExchange exchange)
        throws IOException {
        requirePlayer();

        Identifier world = mc.world.getRegistryKey().getValue();
        sendText(exchange, 200, world.toString());
    }

    private void handleGetScreen(HttpExchange exchange) throws IOException {
        requirePlayer();

        if (mc.currentScreen == null) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        sendText(exchange, 200, mc.currentScreen.getTitle().getString());
    }

    private void handleGetXaeroWaypointSets(HttpExchange exchange)
        throws IOException {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();

        if (session == null) {
            throw new ApiException(503, "No Xaero's Minimap session available");
        }

        MinimapWorld world = session.getWorldManager().getCurrentWorld();

        List<WaypointSet> allWaypoints = new ArrayList<>();
        for (WaypointSet set : world.getIterableWaypointSets()) {
            allWaypoints.add(set);
        }

        sendJson(exchange, allWaypoints);
    }

    private void handlePostXaeroWaypointSets(HttpExchange exchange)
        throws IOException {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();

        if (session == null) {
            throw new ApiException(503, "No Xaero's Minimap session available");
        }

        String setName = readBody(exchange);
        if (setName.isEmpty()) {
            throw new ApiException(400, "Set name cannot be empty");
        }

        MinimapWorld world = session.getWorldManager().getCurrentWorld();
        world.addWaypointSet(setName);

        sendJson(exchange, world.getWaypointSet(setName));
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(
            exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8
        );
    }

    private void sendText(HttpExchange exchange, int status, String text)
        throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange
            .getResponseHeaders()
            .set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, Object obj)
        throws IOException {
        String json = gson.toJson(obj);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange
            .getResponseHeaders()
            .set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendError(
        HttpExchange exchange,
        int statusCode,
        String message
    ) {
        try {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            exchange
                .getResponseHeaders()
                .set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException | IllegalStateException e) {
            // Headers may already be sent; nothing we can do.
        }
    }

    private class ExchangeWrapper implements HttpHandler {

        private final HttpHandler delegate;

        ExchangeWrapper(HttpHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void handle(HttpExchange exchange) {
            try {
                if (config.enableCors) {
                    exchange
                        .getResponseHeaders()
                        .set("Access-Control-Allow-Origin", "*");
                    exchange
                        .getResponseHeaders()
                        .set(
                            "Access-Control-Allow-Methods",
                            "GET, POST, OPTIONS"
                        );
                    exchange
                        .getResponseHeaders()
                        .set("Access-Control-Allow-Headers", "Content-Type");

                    if (
                        "OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())
                    ) {
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                }

                exchange
                    .getResponseHeaders()
                    .set(
                        "Server",
                        "MC Local API v" +
                            modVersion +
                            ", Minecraft " +
                            SharedConstants.getGameVersion().id()
                    );

                delegate.handle(exchange);
            } catch (ApiException e) {
                sendError(exchange, e.getStatusCode(), e.getMessage());
            } catch (Exception e) {
                luisafk.mclocalapi.MCLocalAPIClient.logger.error(
                    "Error handling request {} {}",
                    exchange.getRequestMethod(),
                    exchange.getRequestURI(),
                    e
                );
                sendError(exchange, 500, "Internal Server Error");
            } finally {
                exchange.close();
            }
        }
    }
}
