package luisafk.mclocalapi;

import io.wispforest.owo.config.annotation.Config;
import io.wispforest.owo.config.annotation.Modmenu;
import io.wispforest.owo.config.annotation.PredicateConstraint;
import io.wispforest.owo.config.annotation.RangeConstraint;
import io.wispforest.owo.config.annotation.RestartRequired;
import io.wispforest.owo.config.annotation.SectionHeader;

// https://docs.wispforest.io/owo/config/

@Modmenu(modId = "mc-local-api")
@Config(name = "mc-local-api", wrapperName = "MCLocalAPIConfig")
public class MCLocalAPIConfigModel {
    @RestartRequired
    @RangeConstraint(min = 1025, max = 65535)
    public int port = 25566;
    public boolean autoStart = true;
    public boolean enableCors = true;
    public boolean enableGraphQL = false;
    public boolean enableGraphiQL = false;

    @SectionHeader("player-position")
    public boolean closePlayerPositionStreams = true;
    @PredicateConstraint("nonNegative")
    public double playerPositionStreamDistanceThreshold = 1;

    @SectionHeader("endpoints")
    public boolean enableEndpointChatCommands = false;
    public boolean enableEndpointChatMessages = false;
    public boolean enableEndpointMods = false;
    public boolean enableEndpointPlayerPosition = false;
    public boolean enableEndpointPlayerPositionStream = false;
    public boolean enableEndpointPlayerWorld = false;
    public boolean enableEndpointScreen = false;
    public boolean enableEndpointXaeroWaypointSets = false;

    public static boolean nonNegative(double value) {
        return value >= 0;
    }
}