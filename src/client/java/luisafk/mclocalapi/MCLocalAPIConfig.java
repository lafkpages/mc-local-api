package luisafk.mclocalapi;

import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.autogen.AutoGen;
import dev.isxander.yacl3.config.v2.api.autogen.DoubleField;
import dev.isxander.yacl3.config.v2.api.autogen.IntField;
import dev.isxander.yacl3.config.v2.api.autogen.TickBox;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * Mod configuration. Fields are the single source of truth: {@link SerialEntry}
 * persists them (GSON, no annotation processor) and the {@link AutoGen}
 * annotations generate the settings GUI reflectively at runtime. Labels come
 * from the {@code yacl3.config.mc-local-api:config.*} translation keys in the
 * lang files.
 */
public class MCLocalAPIConfig {

    private static final String CATEGORY = "general";
    private static final String GROUP_SERVER = "server";
    private static final String GROUP_PLAYER_POSITION = "playerPosition";
    private static final String GROUP_ENDPOINTS = "endpoints";

    public static final ConfigClassHandler<MCLocalAPIConfig> HANDLER =
        ConfigClassHandler.createBuilder(MCLocalAPIConfig.class)
            .id(Identifier.fromNamespaceAndPath("mc-local-api", "config"))
            .serializer(config ->
                GsonConfigSerializerBuilder.create(config)
                    .setPath(
                        FabricLoader.getInstance()
                            .getConfigDir()
                            .resolve("mc-local-api.json5")
                    )
                    .setJson5(true)
                    .build()
            )
            .build();

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_SERVER)
    @IntField(min = 1025, max = 65535)
    public int port = 25566;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_SERVER)
    @TickBox
    public boolean autoStart = true;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_SERVER)
    @TickBox
    public boolean enableCors = true;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_PLAYER_POSITION)
    @TickBox
    public boolean closePlayerPositionStreams = true;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_PLAYER_POSITION)
    @DoubleField(min = 0.0)
    public double playerPositionStreamDistanceThreshold = 1;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_ENDPOINTS)
    @TickBox
    public boolean enableEndpointChatCommands = false;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_ENDPOINTS)
    @TickBox
    public boolean enableEndpointChatMessages = false;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_ENDPOINTS)
    @TickBox
    public boolean enableEndpointMods = false;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_ENDPOINTS)
    @TickBox
    public boolean enableEndpointPlayerPosition = false;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_ENDPOINTS)
    @TickBox
    public boolean enableEndpointPlayerPositionStream = false;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_ENDPOINTS)
    @TickBox
    public boolean enableEndpointPlayerWorld = false;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_ENDPOINTS)
    @TickBox
    public boolean enableEndpointScreen = false;

    @SerialEntry
    @AutoGen(category = CATEGORY, group = GROUP_ENDPOINTS)
    @TickBox
    public boolean enableEndpointXaeroWaypointSets = false;

    /**
     * Loads the config from disk (creating defaults if absent) and returns the
     * handler-managed instance. The instance is mutated in place across
     * load/save, so it's safe to hold this reference for the mod's lifetime.
     */
    public static MCLocalAPIConfig createAndLoad() {
        HANDLER.load();
        return HANDLER.instance();
    }

    /** Builds the YACL settings screen (used by the ModMenu integration). */
    public static Screen createScreen(Screen parent) {
        return HANDLER.generateGui().generateScreen(parent);
    }
}
