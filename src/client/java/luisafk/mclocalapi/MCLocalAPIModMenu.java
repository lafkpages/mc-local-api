package luisafk.mclocalapi;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration: exposes the YACL config screen from the mods list.
 * ModMenu is an optional runtime dependency, so this entrypoint only loads
 * when ModMenu is installed.
 */
public class MCLocalAPIModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MCLocalAPIConfig::createScreen;
    }
}
