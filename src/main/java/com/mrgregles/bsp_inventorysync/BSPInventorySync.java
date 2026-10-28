package com.mrgregles.bsp_inventorysync;

import com.mojang.logging.LogUtils;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.data.Modules;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * BSP-InventorySync: syncs player inventories and player data between the Build Secure Protect
 * servers. The design is decided in the vault (InventorySync - Design Decisions) before each part is
 * built here; today the data modules and the storage layer exist, and nothing is wired to logins yet.
 */
@Mod(BSPInventorySync.MODID)
public final class BSPInventorySync {
    public static final String MODID = "bsp_inventorysync";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BSPInventorySync() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SyncConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, NetworkConfig.SPEC, NetworkConfig.FILE); // the connection: never sent to players
        Modules.registerVanilla();
        Modules.registerAdapters();
        BspCore.install();
        LOGGER.info("BSP-InventorySync loaded: {} data modules registered ", Modules.all().size());
    }
}
