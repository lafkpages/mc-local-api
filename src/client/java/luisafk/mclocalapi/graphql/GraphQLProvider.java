package luisafk.mclocalapi.graphql;

import static luisafk.mclocalapi.MCLocalAPIClient.config;
import static luisafk.mclocalapi.MCLocalAPIClient.fabricLoader;
import static luisafk.mclocalapi.MCLocalAPIClient.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import graphql.GraphQLException;
import graphql.schema.DataFetcher;
import graphql.schema.idl.RuntimeWiring;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

public class GraphQLProvider {

    private static void checkEndpointEnabled(boolean enabled, String endpointName) {
        if (!enabled) {
            throw new GraphQLException("Endpoint " + endpointName + " is disabled in the user's configuration");
        }
    }

    private static DataFetcher<Object> playerFetcher() {
        return environment -> {
            checkEndpointEnabled(config.enableEndpointPlayerPosition() || config.enableEndpointPlayerWorld(), "player");
            return mc.player;
        };
    }

    private static DataFetcher<String> playerPositionFetcher() {
        return environment -> {
            checkEndpointEnabled(config.enableEndpointPlayerPosition(), "player.position");

            // The 'source' is the result from the parent field's data fetcher.
            // In this case, it's the ClientPlayerEntity object from the 'playerFetcher'.
            ClientPlayerEntity sourcePlayer = environment.getSource();

            if (sourcePlayer == null) {
                return null;
            }

            return sourcePlayer.getPos().toString();
        };
    }

    private static DataFetcher<String> playerWorldFetcher() {
        return environment -> {
            checkEndpointEnabled(config.enableEndpointPlayerWorld(), "player.world");

            ClientPlayerEntity sourcePlayer = environment.getSource();

            if (sourcePlayer == null) {
                return null;
            }

            Identifier world = mc.world.getRegistryKey().getValue();
            return world.toString();
        };
    }

    private static DataFetcher<List<Map<String, String>>> modsFetcher() {
        return environment -> {
            checkEndpointEnabled(config.enableEndpointMods(), "mods");

            List<Map<String, String>> mods = new ArrayList<>();

            fabricLoader.getAllMods().forEach(modContainer -> {
                var metadata = modContainer.getMetadata();

                Map<String, String> mod = new HashMap<>();
                mod.put("id", metadata.getId());
                mod.put("version", metadata.getVersion().getFriendlyString());
                mods.add(mod);
            });

            return mods;
        };
    }

    private static DataFetcher<String> screenFetcher() {
        return environment -> {
            checkEndpointEnabled(config.enableEndpointScreen(), "screen");

            if (mc.currentScreen == null) {
                return null;
            }

            return mc.currentScreen.getTitle().getString();
        };
    }

    private static DataFetcher<List<WaypointSet>> xaeroWaypointSetsFetcher() {
        return environment -> {
            checkEndpointEnabled(config.enableEndpointXaeroWaypointSets(), "xaeroWaypointSets");

            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();

            if (session == null) {
                throw new GraphQLException("No Xaero's Minimap session available");
            }

            MinimapWorld world = session.getWorldManager().getCurrentWorld();

            List<WaypointSet> allWaypoints = new ArrayList<>();

            for (WaypointSet set : world.getIterableWaypointSets()) {
                allWaypoints.add(set);
            }

            return allWaypoints;
        };
    }

    private static DataFetcher<Boolean> sendChatCommandFetcher() {
        return environment -> {
            checkEndpointEnabled(config.enableEndpointChatCommands(), "sendChatCommand");

            String command = environment.getArgument("command");

            if (command == null || command.isEmpty()) {
                throw new GraphQLException("Command cannot be empty");
            }

            mc.player.networkHandler.sendChatCommand(command);
            return true;
        };
    }

    private static DataFetcher<Boolean> sendChatMessageFetcher() {
        return environment -> {
            checkEndpointEnabled(config.enableEndpointChatMessages(), "sendChatMessage");

            String message = environment.getArgument("message");

            if (message == null || message.isEmpty()) {
                throw new GraphQLException("Message cannot be empty");
            }

            mc.player.networkHandler.sendChatMessage(message);
            return true;
        };
    }

    private static DataFetcher<WaypointSet> createXaeroWaypointSetFetcher() {
        return environment -> {
            checkEndpointEnabled(config.enableEndpointXaeroWaypointSets(), "createXaeroWaypointSet");

            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();

            if (session == null) {
                throw new GraphQLException("No Xaero's Minimap session available");
            }

            String setName = environment.getArgument("name");

            if (setName == null || setName.isEmpty()) {
                throw new GraphQLException("Set name cannot be empty");
            }

            MinimapWorld world = session.getWorldManager().getCurrentWorld();

            world.addWaypointSet(setName);

            return world.getWaypointSet(setName);
        };
    }

    public static RuntimeWiring buildWiring() {
        return RuntimeWiring.newRuntimeWiring()
                .type("Query", builder -> builder
                        .dataFetcher("player", playerFetcher())
                        .dataFetcher("mods", modsFetcher())
                        .dataFetcher("screen", screenFetcher())
                        .dataFetcher("xaeroWaypointSets", xaeroWaypointSetsFetcher()))
                .type("Player", builder -> builder
                        .dataFetcher("position", playerPositionFetcher())
                        .dataFetcher("world", playerWorldFetcher()))
                .type("Mutation", builder -> builder
                        .dataFetcher("sendChatCommand", sendChatCommandFetcher())
                        .dataFetcher("sendChatMessage", sendChatMessageFetcher())
                        .dataFetcher("createXaeroWaypointSet", createXaeroWaypointSetFetcher()))
                .build();
    }
}
