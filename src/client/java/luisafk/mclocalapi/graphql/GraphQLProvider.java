package luisafk.mclocalapi.graphql;

import static luisafk.mclocalapi.MCLocalAPIClient.mc;

import graphql.schema.DataFetcher;
import graphql.schema.idl.RuntimeWiring;
import net.minecraft.client.network.ClientPlayerEntity;

public class GraphQLProvider {

    private static DataFetcher<Object> playerFetcher() {
        return environment -> {
            return mc.player;
        };
    }

    private static DataFetcher<String> playerPositionFetcher() {
        return environment -> {
            // The 'source' is the result from the parent field's data fetcher.
            // In this case, it's the ClientPlayerEntity object from the 'playerFetcher'.
            ClientPlayerEntity sourcePlayer = environment.getSource();

            if (sourcePlayer == null) {
                return null;
            }

            return sourcePlayer.getPos().toString();
        };
    }

    public static RuntimeWiring buildWiring() {
        return RuntimeWiring.newRuntimeWiring()
                .type("Query", builder -> builder
                        .dataFetcher("player", playerFetcher()))
                .type("Player", builder -> builder
                        .dataFetcher("position", playerPositionFetcher()))
                .build();
    }
}
