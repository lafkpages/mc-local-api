package luisafk.mclocalapi;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.CharStreams;
import com.mojang.brigadier.Command;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.javalin.util.JavalinBindException;
import luisafk.mclocalapi.graphql.GraphQLProvider;
import luisafk.mclocalapi.graphql.GraphQLRequest;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

public class MCLocalAPIClient implements ClientModInitializer {
    public static final MCLocalAPIConfig config = MCLocalAPIConfig
            .createAndLoad();

    public static final MinecraftClient mc = MinecraftClient.getInstance();
    public static final FabricLoader fabricLoader = FabricLoader.getInstance();

    public static final Logger logger = LoggerFactory.getLogger("mc-local-api");
    public static final Version modVersion = fabricLoader.getModContainer("mc-local-api").get()
            .getMetadata()
            .getVersion();

    private Javalin server;
    private GraphQL graphQl;

    Vec3d lastPos = new Vec3d(0, 0, 0);
    Identifier lastWorld;

    public static final List<SseClient> posSseClients = new CopyOnWriteArrayList<>();

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

        initGraphQl();
    }

    private void initGraphQl() {
        if (graphQl != null) {
            throw new IllegalStateException("GraphQL is already initialized");
        }

        // Get the schema file from the resources folder
        InputStream schemaStream = MCLocalAPIClient.class.getClassLoader().getResourceAsStream("schema.graphqls");
        if (schemaStream == null) {
            throw new IllegalStateException("Cannot find schema.graphqls in resources!");
        }

        String schemaSdl;

        try {
            // Read the stream into a string
            schemaSdl = CharStreams.toString(new InputStreamReader(schemaStream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read schema.graphqls: " + e.getMessage(), e);
        }

        // Parse the schema and build the executable schema
        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(schemaSdl);
        RuntimeWiring runtimeWiring = GraphQLProvider.buildWiring();
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(typeRegistry, runtimeWiring);

        graphQl = GraphQL.newGraphQL(schema).build();
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

        // Define REST routes via RestApiProvider
        new RestApiProvider(server).defineRoutes();

        // GraphQL endpoints
        server.post("/graphql", this::handleGraphQL);
        server.get("/graphiql", this::handleGraphiQL);

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

    private void handleGraphQL(Context ctx) {
        // TODO: https://www.graphql-java.com/documentation/execution/#query-caching

        // From the [GraphQL
        // spec](https://graphql.org/learn/serving-over-http/#post-request-and-body):
        //
        // > Note that if the `Content-type` header is missing in the client’s request,
        // > then the server should respond with a `4xx` status code. As with the
        // > `Accept` header, `utf-8` encoding is assumed for a request body with an
        // > `application/json` media type when this information is not explicitly
        // > provided.

        String contentType = ctx.contentType();

        // Check if Content-Type header is missing
        if (contentType == null || contentType.isEmpty()) {
            ctx.status(400);
            ctx.json(Map.of(
                    "errors", List.of(Map.of(
                            "message",
                            "Missing Content-Type header. GraphQL requests must use Content-Type: application/json"))));
            return;
        }

        // Parse the content type (handle charset and other parameters)
        String mediaType = contentType.split(";")[0].trim().toLowerCase();

        // Check if Content-Type is application/json
        if (!mediaType.equals("application/json")) {
            ctx.status(400);
            ctx.json(Map.of(
                    "errors", List.of(Map.of(
                            "message", "Invalid Content-Type: " + contentType
                                    + ". GraphQL requests must use Content-Type: application/json"))));
            return;
        }

        // Content-Type is valid, continue to the handler
        // Javalin automatically handles UTF-8 encoding for application/json

        // Parse the JSON body into a GraphQLRequest object
        GraphQLRequest request = ctx.bodyAsClass(GraphQLRequest.class);

        // Validate that we have at least a query
        if (request.getQuery() == null || request.getQuery().isEmpty()) {
            throw new BadRequestResponse("Query is required");
        }

        ExecutionInput.Builder executionInputBuilder = ExecutionInput.newExecutionInput()
                .query(request.getQuery());

        // Add optional fields if present
        if (request.getOperationName() != null) {
            executionInputBuilder.operationName(request.getOperationName());
        }

        if (request.getVariables() != null) {
            executionInputBuilder.variables(request.getVariables());
        }

        if (request.getExtensions() != null) {
            executionInputBuilder.extensions(request.getExtensions());
        }

        ExecutionInput executionInput = executionInputBuilder.build();
        ExecutionResult executionResult = graphQl.execute(executionInput);

        Map<String, Object> resultMap = executionResult.toSpecification();

        ctx.json(resultMap);
    }

    private void handleGraphiQL(Context ctx) {
        InputStream graphiqlStream = MCLocalAPIClient.class.getClassLoader()
                .getResourceAsStream("graphiql.html");

        if (graphiqlStream == null) {
            ctx.status(404).result("GraphiQL interface not found");
            return;
        }

        ctx.contentType(ContentType.TEXT_HTML).result(graphiqlStream);
    }
}
