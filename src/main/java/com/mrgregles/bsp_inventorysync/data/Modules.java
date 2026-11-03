package com.mrgregles.bsp_inventorysync.data;

import com.mrgregles.bsp_inventorysync.SyncConfig;
import com.mrgregles.bsp_inventorysync.api.DataModule;
import com.mrgregles.bsp_inventorysync.compat.Curios;
import com.mrgregles.bsp_inventorysync.compat.DankStorage;
import com.mrgregles.bsp_inventorysync.compat.SophisticatedBackpacks;
import com.mrgregles.bsp_inventorysync.data.vanilla.ForgeDataModule;

import com.mrgregles.bsp_inventorysync.data.vanilla.AdvancementsModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.EnderChestModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.ExperienceModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.GameModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.InventoryModule;
import com.mrgregles.bsp_inventorysync.data.vanilla.VitalsModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registered {@link DataModule}s, in the order they run. The vanilla modules are registered when
 * the mod loads; other mods add theirs through the api (later task). Order matters in one place: the
 * inventory goes before vitals, because armour and modded items change the player's maximum health.
 */
public final class Modules {
    private static final Map<String, DataModule> MODULES = new LinkedHashMap<>();

    private Modules() {}

    /** Adds a module; a second module with the same id replaces the first and keeps its place. */
    public static synchronized void register(DataModule module) {
        String id = module.id();
        if (!id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("module id must be letters, digits and underscores: " + id);
        }
        MODULES.put(id, module);
    }

    /** Removes a module by id; false when there was none. */
    public static synchronized boolean unregister(String id) {
        return MODULES.remove(id) != null;
    }

    /** The modules in running order, as a copy. */
    public static synchronized List<DataModule> all() {
        return Collections.unmodifiableList(new ArrayList<>(MODULES.values()));
    }

    public static synchronized DataModule get(String id) {
        return MODULES.get(id);
    }

    /** The vanilla modules of D3 (statistics and the recipe book are left out on purpose), then the generic Forge module (D11). */
    public static void registerVanilla() {
        register(new InventoryModule());
        register(new EnderChestModule());
        register(new ExperienceModule());
        register(new GameModule());
        register(new VitalsModule());
        register(new AdvancementsModule());
        register(new ForgeDataModule(
                () -> withAlwaysExcluded(SyncConfig.getOr(SyncConfig.EXCLUDE_FORGE_DATA, DEFAULT_DATA_EXCLUSIONS)),
                () -> SyncConfig.getOr(SyncConfig.EXCLUDE_CAPABILITIES, DEFAULT_CAP_EXCLUSIONS)));
    }

    /**
     * BSP-Core's keys that stay on the server that wrote them, whatever {@code sync.excludeForgeData} says:
     * its last position in a main dimension and the server that position belongs to (meaningless anywhere
     * else), and the totems it keeps through a death in an Anti Totem zone or on a no-totem server (live
     * Shatter Totems, which this mod never saves).
     */
    public static final List<String> ALWAYS_DATA_EXCLUSIONS = List.of(
            "PlayerPersisted.bsp_core:LastMainDim",
            "PlayerPersisted.bsp_core:LastMainPos",
            "PlayerPersisted.bsp_core:LastMainServer",
            "PlayerPersisted.bsp_core:ZoneHeldTotems");

    /** The defaults of the exclusion lists, also used while the config is not loaded. */
    public static final List<String> DEFAULT_DATA_EXCLUSIONS = ALWAYS_DATA_EXCLUSIONS;
    public static final List<String> DEFAULT_CAP_EXCLUSIONS = List.of("curios:inventory");

    /** {@link #ALWAYS_DATA_EXCLUSIONS} followed by whatever else the config lists. */
    public static List<String> withAlwaysExcluded(List<? extends String> configured) {
        List<String> all = new ArrayList<>(ALWAYS_DATA_EXCLUSIONS);
        for (String path : configured) {
            if (!all.contains(path)) {
                all.add(path);
            }
        }
        return all;
    }

    /** The adapters for the pack's item mods (D3, D12), each only when its mod is loaded. After the vanilla modules, since they read the restored inventory. */
    public static void registerAdapters() {
        if (Curios.present()) {
            register(new Curios.Module());
        }
        if (SophisticatedBackpacks.present()) {
            register(new SophisticatedBackpacks.Module());
        }
        if (DankStorage.present()) {
            register(new DankStorage.Module());
        }
    }
}
