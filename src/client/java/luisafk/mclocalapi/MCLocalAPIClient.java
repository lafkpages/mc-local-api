package luisafk.mclocalapi;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

public class MCLocalAPIClient implements ClientModInitializer {
    public static final luisafk.mclocalapi.MCLocalAPIConfig config = luisafk.mclocalapi.MCLocalAPIConfig.createAndLoad();

    Logger logger = LoggerFactory.getLogger("mc-local-api");

    HttpServer server;
    Handler handler;

    ArrayList<HttpExchange> posSseExchanges = new ArrayList<>();

    @Override
    public void onInitializeClient() {
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

        AtomicReference<Vec3d> lastPos = new AtomicReference<>(new Vec3d(0, 0, 0));

        ClientTickEvents.START_CLIENT_TICK.register((minecraftClient) -> {
            if (minecraftClient.player == null) {
                if (config.posSseClose()) {
                    posSseExchanges.forEach(HttpExchange::close);
                    posSseExchanges.clear();
                }
                return;
            }

            Vec3d pos = minecraftClient.player.getPos();

            if (lastPos.get().distanceTo(pos) > config.posSseDistanceThreshold()) {
                String resp = "data: " + pos.toString() + "\n\n";
                lastPos.set(pos);

                logger.info("Sending pos update: {}", pos);

                posSseExchanges.forEach(exchange -> {
                    try {
                        OutputStream os = exchange.getResponseBody();
                        os.write(resp.getBytes());
                        os.flush();
                    } catch (IOException e) {
                        logger.error("Failed to write SSE response", e);
                    }
                });
            }
        });
    }

    static class Handler implements HttpHandler {
        MCLocalAPIClient modInstance;

        public Handler(MCLocalAPIClient mcLocalAPIClient) {
            modInstance = mcLocalAPIClient;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (!path.endsWith("/")) {
                path += "/";
            }
            String id = method + " " + path;

            if (!config.enableEndpointPos() && path.startsWith("/pos/")) {
                exchange.sendResponseHeaders(403, 0);
                exchange.getResponseBody().close();
                return;
            }

            switch (id) {
                case "GET /pos/":
                    handlePos(exchange);
                    break;

                case "GET /pos/sse/":
                    handlePosSse(exchange);
                    break;

                default:
                    exchange.sendResponseHeaders(404, 0);
                    exchange.getResponseBody().close();
            }
        }

        private void setHeaders(HttpExchange exchange) {
            if (config.enableCors()) {
                Headers headers = exchange.getResponseHeaders();
                headers.set("Access-Control-Allow-Origin", "*");
                headers.set("Access-Control-Allow-Methods", "GET");
            }
        }

        private void handlePos(HttpExchange exchange) throws IOException {
            MinecraftClient client = MinecraftClient.getInstance();

            if (client.player == null) {
                exchange.sendResponseHeaders(503, 0);
                exchange.getResponseBody().close();
                return;
            }

            setHeaders(exchange);

            String resp = client.player.getPos().toString();

            exchange.sendResponseHeaders(200, resp.length());
            OutputStream os = exchange.getResponseBody();
            os.write(resp.getBytes());
            os.close();
        }


        private void handlePosSse(HttpExchange exchange) throws IOException {
            MinecraftClient client = MinecraftClient.getInstance();

            if (client.player == null) {
                exchange.sendResponseHeaders(503, 0);
                exchange.getResponseBody().close();
                return;
            }

            setHeaders(exchange);

            Headers respHeaders = exchange.getResponseHeaders();
            respHeaders.set("Content-Type", "text/event-stream");
            respHeaders.set("Connection", "keep-alive");
            respHeaders.set("Transfer-Encoding", "chunked");
            exchange.sendResponseHeaders(200, 0);

            modInstance.posSseExchanges.add(exchange);
        }
    }
}
