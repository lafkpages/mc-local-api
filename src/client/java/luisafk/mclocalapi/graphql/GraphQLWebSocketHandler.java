package luisafk.mclocalapi.graphql;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;

public class GraphQLWebSocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(GraphQLWebSocketHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GraphQL graphQL;
    private final ScheduledExecutorService pingScheduler = Executors.newScheduledThreadPool(1);

    // Store active subscriptions per session
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    // Message types for graphql-transport-ws protocol (newer protocol)
    private static final String GQL_CONNECTION_INIT = "connection_init";
    private static final String GQL_CONNECTION_ACK = "connection_ack";
    private static final String GQL_PING = "ping";
    private static final String GQL_PONG = "pong";
    private static final String GQL_SUBSCRIBE = "subscribe";
    private static final String GQL_NEXT = "next";
    private static final String GQL_ERROR = "error";
    private static final String GQL_COMPLETE = "complete";

    private static class SessionData {
        final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
        ScheduledFuture<?> pingTask;
    }

    public GraphQLWebSocketHandler(GraphQL graphQL) {
        this.graphQL = graphQL;
    }

    public void handleConnect(WsConnectContext ctx) {
        sessions.put(ctx.sessionId(), new SessionData());
        logger.debug("WebSocket connected: {}", ctx.sessionId());
    }

    public void handleMessage(WsMessageContext ctx) {
        try {
            Map<String, Object> message = objectMapper.readValue(ctx.message(), Map.class);
            String type = (String) message.get("type");

            logger.debug("Received message type: {} for session: {}", type, ctx.sessionId());

            switch (type) {
                case GQL_CONNECTION_INIT:
                    handleConnectionInit(ctx);
                    break;
                case GQL_PING:
                    handlePing(ctx);
                    break;
                case GQL_PONG:
                    // Client acknowledged our ping
                    break;
                case GQL_SUBSCRIBE:
                    handleSubscribe(ctx, message);
                    break;
                case GQL_COMPLETE:
                    handleComplete(ctx, message);
                    break;
                default:
                    logger.warn("Unknown message type: {}", type);
            }
        } catch (Exception e) {
            logger.error("Error handling WebSocket message", e);
            sendError(ctx, null, e.getMessage());
        }
    }

    public void handleClose(WsCloseContext ctx) {
        cleanupSession(ctx.sessionId());
        logger.debug("WebSocket closed: {}", ctx.sessionId());
    }

    public void handleError(WsErrorContext ctx) {
        logger.error("WebSocket error for session {}: {}", ctx.sessionId(), ctx.error());
        cleanupSession(ctx.sessionId());
    }

    private void cleanupSession(String sessionId) {
        SessionData session = sessions.remove(sessionId);
        if (session != null) {
            // Cancel all subscriptions
            session.subscriptions.values().forEach(Subscription::cancel);

            // Cancel ping task
            if (session.pingTask != null) {
                session.pingTask.cancel(false);
            }
        }
    }

    private void handleConnectionInit(WsContext ctx) {
        sendMessage(ctx, Map.of("type", GQL_CONNECTION_ACK));

        // Start ping/pong to keep connection alive
        SessionData session = sessions.get(ctx.sessionId());
        if (session != null) {
            session.pingTask = pingScheduler.scheduleAtFixedRate(() -> {
                sendMessage(ctx, Map.of("type", GQL_PING));
            }, 30, 30, TimeUnit.SECONDS);
        }
    }

    private void handlePing(WsContext ctx) {
        sendMessage(ctx, Map.of("type", GQL_PONG));
    }

    private void handleSubscribe(WsContext ctx, Map<String, Object> message) {
        String id = (String) message.get("id");
        Map<String, Object> payload = (Map<String, Object>) message.get("payload");

        String query = (String) payload.get("query");
        Map<String, Object> variables = (Map<String, Object>) payload.get("variables");
        String operationName = (String) payload.get("operationName");

        logger.debug("Subscribing to query: {} with id: {}", query, id);

        ExecutionInput executionInput = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables != null ? variables : Map.of())
                .operationName(operationName)
                .build();

        ExecutionResult result = graphQL.execute(executionInput);

        if (result.getData() instanceof Publisher) {
            // It's a subscription
            handleSubscription(ctx, id, (Publisher<ExecutionResult>) result.getData());
        } else {
            // It's a regular query/mutation - send single result
            if (!result.getErrors().isEmpty()) {
                sendMessage(ctx, Map.of(
                        "id", id,
                        "type", GQL_ERROR,
                        "payload", result.getErrors()));
            } else {
                sendMessage(ctx, Map.of(
                        "id", id,
                        "type", GQL_NEXT,
                        "payload", result.toSpecification()));
            }
            sendMessage(ctx, Map.of(
                    "id", id,
                    "type", GQL_COMPLETE));
        }
    }

    private void handleSubscription(WsContext ctx, String id, Publisher<ExecutionResult> publisher) {
        publisher.subscribe(new Subscriber<ExecutionResult>() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                SessionData session = sessions.get(ctx.sessionId());
                if (session != null) {
                    session.subscriptions.put(id, s);
                    s.request(1);
                } else {
                    // Session was closed before subscription could be stored
                    s.cancel();
                }
            }

            @Override
            public void onNext(ExecutionResult result) {
                if (!result.getErrors().isEmpty()) {
                    sendMessage(ctx, Map.of(
                            "id", id,
                            "type", GQL_ERROR,
                            "payload", result.getErrors()));
                } else {
                    sendMessage(ctx, Map.of(
                            "id", id,
                            "type", GQL_NEXT,
                            "payload", result.toSpecification()));
                }
                subscription.request(1);
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Subscription error for id: {}", id, t);
                sendError(ctx, id, t.getMessage());
                SessionData session = sessions.get(ctx.sessionId());
                if (session != null) {
                    session.subscriptions.remove(id);
                }
            }

            @Override
            public void onComplete() {
                sendMessage(ctx, Map.of(
                        "id", id,
                        "type", GQL_COMPLETE));
                SessionData session = sessions.get(ctx.sessionId());
                if (session != null) {
                    session.subscriptions.remove(id);
                }
            }
        });
    }

    private void handleComplete(WsContext ctx, Map<String, Object> message) {
        String id = (String) message.get("id");
        SessionData session = sessions.get(ctx.sessionId());
        if (session != null) {
            Subscription subscription = session.subscriptions.remove(id);
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }

    private void sendMessage(WsContext ctx, Map<String, Object> message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            ctx.send(json);
            logger.debug("Sent message: {}", json);
        } catch (IOException e) {
            logger.error("Error sending message", e);
        }
    }

    private void sendError(WsContext ctx, String id, String errorMessage) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", GQL_ERROR);
        message.put("payload", new Object[] {
                Map.of("message", errorMessage)
        });

        if (id != null) {
            message.put("id", id);
        }

        sendMessage(ctx, message);
    }

    public void shutdown() {
        pingScheduler.shutdown();
        try {
            if (!pingScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                pingScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            pingScheduler.shutdownNow();
        }
    }
}
