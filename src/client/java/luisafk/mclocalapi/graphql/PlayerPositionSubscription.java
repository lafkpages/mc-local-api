package luisafk.mclocalapi.graphql;

import static luisafk.mclocalapi.MCLocalAPIClient.config;
import static luisafk.mclocalapi.MCLocalAPIClient.mc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import graphql.schema.DataFetcher;
import net.minecraft.util.math.Vec3d;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

public class PlayerPositionSubscription {
    // Store active subscriptions
    private static final Map<String, Sinks.Many<Map<String, String>>> activeSubscriptions = new ConcurrentHashMap<>();

    public static void broadcastPositionUpdate(Vec3d position, String world) {
        Map<String, String> update = new HashMap<>();
        update.put("position", position.toString());
        update.put("world", world);

        // Send to all active subscriptions
        activeSubscriptions.values().forEach(sink -> sink.tryEmitNext(update));
    }

    public static DataFetcher<Flux<Map<String, String>>> playerPositionSubscription() {
        return environment -> {
            if (!config.enableEndpointPlayerPositionStream()) {
                throw new RuntimeException("Player position stream endpoint is disabled");
            }

            if (mc.player == null) {
                throw new RuntimeException("Player not available");
            }

            String subscriptionId = UUID.randomUUID().toString();

            // Create a sink for this subscription
            Sinks.Many<Map<String, String>> sink = Sinks.many().multicast().onBackpressureBuffer();
            activeSubscriptions.put(subscriptionId, sink);

            // Send initial position
            Map<String, String> initialUpdate = new HashMap<>();
            initialUpdate.put("position", mc.player.getPos().toString());
            initialUpdate.put("world", mc.world.getRegistryKey().getValue().toString());

            sink.tryEmitNext(initialUpdate);

            // Return the flux and clean up when cancelled
            return sink.asFlux()
                    .doOnCancel(() -> activeSubscriptions.remove(subscriptionId))
                    .doOnTerminate(() -> activeSubscriptions.remove(subscriptionId));
        };
    }
}
