# MC Local API Copilot Instructions

## Architecture Overview

This is a Minecraft Fabric mod that provides a local HTTP REST API for interacting with the Minecraft client. The mod uses **Javalin** as the embedded HTTP server, running inside the Minecraft client.

### Key Components

- **`MCLocalAPIClient`**: Main entry point implementing `ClientModInitializer`
- **Javalin HTTP Server**: Embedded web server (default port 25566) with REST endpoints
- **Configuration**: Uses `owo-lib` for mod configuration with endpoint toggles and security controls
- **Integration Points**: Xaero's Minimap (waypoints), Baritone (optional), standard MC client APIs

## Development Patterns

### Endpoint Protection Pattern

All endpoints use a protection mechanism via `protectEndpoint()`:

```java
protectEndpoint("/endpoint", () -> config.enableEndpointName());
server.get("/endpoint", this::handleEndpoint);
```

### Player State Validation

Use `requirePlayer()` helper for endpoints that need active player:

```java
private void requirePlayer() {
    if (mc.player == null) {
        throw new PlayerUnavailableResponse();
    }
}
```

### Error Handling

- Custom Javalin exceptions: `EndpointDisabledResponse`, `PlayerUnavailableResponse`
- Endpoint availability checks in REST handlers

### Configuration Management

- Config stored in `MCLocalAPIConfigModel` with owo-lib annotations
- Endpoint toggles: Each API endpoint can be individually disabled
- Security settings: CORS, auto-start, port configuration
- Access via static `config` field in `MCLocalAPIClient`

## Build & Dependencies

### Key Dependencies (Shaded into JAR)

- `io.javalin:javalin:6.7.0` - HTTP server
- `com.fasterxml.jackson.core:jackson-databind` - JSON serialization

### Build Commands

```bash
./gradlew build              # Build mod JAR with shaded dependencies
./gradlew runClient          # Test in development environment
```

### Fabric Loom Configuration

- Uses `modShade` configuration to bundle HTTP server dependencies
- Client-only mod (no server-side components)
- Targets Java 21+ and Minecraft 1.21.8

## Integration Patterns

### Xaero's Minimap Integration

Access via `BuiltInHudModules.MINIMAP.getCurrentSession()`:

- Check session availability before operations
- Use `MinimapWorld` for waypoint operations
- Handle `ServiceUnavailableResponse` when mod not loaded

### Client Tick Events

Position streaming uses `ClientTickEvents.START_CLIENT_TICK` for real-time updates:

- Distance threshold checking before sending updates
- SSE client management with error handling and cleanup
- World change detection and notification

## Testing & Development

### In-Game Commands

- `/startserver` - Start HTTP server manually
- `/stopserver` - Stop HTTP server

### Development Server

- Auto-starts if `config.autoStart()` is true
- CORS enabled by default for development

### Configuration Location

Runtime config: `run/config/mc-local-api.json5`
