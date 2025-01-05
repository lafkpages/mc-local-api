package luisafk.mclocalapi;

import io.wispforest.owo.config.annotation.Config;
import io.wispforest.owo.config.annotation.Modmenu;
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
    public boolean enableCors = true;

    @SectionHeader("pos")
    public boolean enableEndpointPos = false;
    public boolean posSseClose = true;
    public double posSseDistanceThreshold = 1;
}