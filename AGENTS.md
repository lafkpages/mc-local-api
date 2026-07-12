# MC Local API Copilot Instructions

## Architecture Overview

This is a client-side Minecraft Fabric mod that exposes a local HTTP REST API for interacting with the Minecraft client. The HTTP server is the JDK's built-in `com.sun.net.httpserver.HttpServer` (no external web framework), and JSON is produced with Gson, which ships with Minecraft. **Nothing is shaded into the jar** — the mod has no bundled runtime dependencies.

### Key Components

- **`MCLocalAPIClient`** (`src/client/java/.../MCLocalAPIClient.java`): `ClientModInitializer` entry point. Owns the `HttpServer`, registers the `/startserver` + `/stopserver` client commands, and drives `/player/position/stream` SSE updates from a client tick listener. Holds the static `config`, `mc`, `fabricLoader`, `logger`, `modVersion`, and `posSseClients` used across the mod.
- **`rest/RestApiProvider`**: Defines all routes and their handlers on the `HttpServer`.
- **`rest/SseConnection`**: Wraps an `HttpExchange` for Server-Sent Events.
- **`rest/ApiException`**: `RuntimeException` carrying an HTTP status code + message; thrown by handlers and translated into an HTTP error response.
- **`MCLocalAPIConfig`**: YACL-backed config (see below).
- **`MCLocalAPIModMenu`**: Optional ModMenu integration that opens the YACL config screen.

## Minecraft 26.1+ / build model

- **Minecraft 26.1+ is unobfuscated** (Mojang/"Mojmap"-style names, e.g. `Minecraft`, `Component`, `ChatFormatting`, `Vec3`, `ResourceLocation`). There are **no Yarn mappings** (Yarn stopped at 1.21.11) and **no `mappings` line** in `build.gradle`.
- Loom plugin id is `net.fabricmc.fabric-loom` (not `fabric-loom`). Because the game is unobfuscated, mod dependencies are **not remapped**: use plain `implementation` / `compileOnly` / `localRuntime` (not `modImplementation` etc.), and the build output is the normal `jar` task (no `remapJar`).
- Targets **Java 25** bytecode (`options.release = 25`, `JavaVersion.VERSION_25`); built locally with a newer JDK (JDK 26 is fine). Native-access `WARNING:` lines on JDK 24+ are expected/cosmetic.

## Development Patterns

### Endpoint protection

Public endpoints are registered with `registerProtected(...)`, which gates the handler behind a config flag (returns 403 when disabled). Two overloads exist — with and without a fixed HTTP method:

```java
registerProtected("/mods", "GET", () -> config.enableEndpointMods, this::handleGetMods);
registerProtected("/xaero/waypoint-sets", () -> config.enableEndpointXaeroWaypointSets, exchange -> { /* GET/POST switch */ });
```

Unprotected/meta routes use `register(path, method, handler)`.

### Player state validation

Use `requirePlayer()` for handlers that need an active player:

```java
private void requirePlayer() {
    if (mc.player == null) {
        throw new ApiException(503, "Player not available");
    }
}
```

### Error handling

- Throw `ApiException(statusCode, message)` from handlers (e.g. `400`, `403`, `405`, `503`).
- The `ExchangeWrapper` inner class in `RestApiProvider` wraps every handler: it applies CORS headers when `config.enableCors` (short-circuiting `OPTIONS`), sets the `Server` header, catches `ApiException` (and any other exception → `500`), and always closes the exchange.

### Configuration management

- `MCLocalAPIConfig` is a plain data class using YACL's GSON-based Config API (`@SerialEntry` fields + `ConfigClassHandler`); **no annotation processor is involved**. Config file: `run/config/mc-local-api.json5`.
- The fields are the single source of truth — REST handlers and the client read them directly (e.g. `config.port`, `config.enableEndpointMods`). There are no getter wrappers.
- The settings GUI is generated reflectively from `@AutoGen` + controller annotations (`@TickBox`, `@IntField`, `@DoubleField`) via `HANDLER.generateGui()`; `MCLocalAPIConfig.createScreen(parent)` wires it to ModMenu (an optional runtime dependency, added to the dev run via `localRuntime`).
- GUI labels come from `yacl3.config.mc-local-api:config.*` keys in `assets/mc-local-api/lang/en_us.json`. The AutoGen key shape is:
  - Category: `yacl3.config.mc-local-api:config.category.<category>`
  - Group: `yacl3.config.mc-local-api:config.category.<category>.group.<group>`
  - Option label: `yacl3.config.mc-local-api:config.<fieldName>` (flat; field name verbatim/case-sensitive)
  - Option tooltip (optional): `…<fieldName>.desc`
  - Title: `yacl3.config.mc-local-api:config.title`
- Access via the static `config` field in `MCLocalAPIClient`.

## Build & Dependencies

### Dependencies (declared in `build.gradle`, none shaded)

- `net.fabricmc:fabric-loader`, `net.fabricmc.fabric-api:fabric-api`
- `dev.isxander:yet-another-config-lib` (YACL) — config + GUI
- `maven.modrinth:modmenu` (`compileOnly` + `localRuntime`) — config screen entry
- `maven.modrinth:xaeros-minimap` — waypoint integration
- Baritone + nether-pathfinder are **commented out** in `build.gradle` pending a Baritone build for the current MC family; keep them commented, do not delete. The `libs/` flatDir jars are git-ignored — do not touch `libs/`.

Version numbers live in `gradle.properties`; update them there when porting.

### Build commands

```bash
./gradlew build              # Build the mod jar
./gradlew runClient          # Launch a dev client (ModMenu + YACL + Xaero available)
```

### Fabric Loom configuration

- `splitEnvironmentSourceSets()`; all mod code lives in the **client** source set (`src/client`). Resources/lang live in `src/main/resources`.
- Client-only mod (no server-side components).

## Integration Patterns

### Xaero's Minimap Integration

Access via `BuiltInHudModules.MINIMAP.getCurrentSession()`:

- Check session availability first; throw `ApiException(503, "No Xaero's Minimap session available")` when the mod/session isn't present.
- Use `session.getWorldManager().getCurrentWorld()` (a `MinimapWorld`) for waypoint-set operations (`getIterableWaypointSets()`, `addWaypointSet(name)`, `getWaypointSet(name)`).

### Client Tick Events

Position streaming uses `ClientTickEvents.START_CLIENT_TICK`:

- Distance-threshold check (`config.playerPositionStreamDistanceThreshold`) before broadcasting a position update.
- SSE client management (`posSseClients`) with per-client error handling and cleanup.
- World-change detection sends a `changeworld` SSE event; when the player is gone, streams are closed if `config.closePlayerPositionStreams`.

## Testing & Development

### In-Game Commands

- `/startserver` — start HTTP server manually
- `/stopserver` — stop HTTP server

### Development Server

- Auto-starts if `config.autoStart` is true (default).
- CORS enabled by default for development.
- Default port `25566`.

## Version porting / releases

The mod is maintained **forward-only**: target one Minecraft version family at a time, release it, then move to the next — old families are not maintained on branches. To port a family:

1. Bump `gradle.properties`: `minecraft_version`, `loader_version`, `loom_version`, `fabric_version`, `yacl_version`, `modmenu_version`, `xaeros_minimap_version`, and `mod_version`.
2. Update `fabric.mod.json` `depends`/`suggests` predicates (`minecraft`, `java`, `yet_another_config_lib_v3`).
3. Fix any Mojmap API drift the compiler reports (26.x renames from earlier versions).
4. Build oldest patch of the family, declare the full family range in `fabric.mod.json` (`minecraft`), and upload one jar tagged for every patch on Modrinth (Modrinth's game-version tags are separate from the `fabric.mod.json` predicate).
