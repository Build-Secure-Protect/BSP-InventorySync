package com.mrgregles.bsp_inventorysync.compat;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BSP-Region Guard, by reflection (no build dependency): only its world border, which Region Guard keeps for every
 * world, the Nether and the End included ({@code RegionGuardApi.get().regions().border(ServerLevel)}, a square
 * {@code Border(centerX, centerZ, size)}, or null when the world has none; API version 6 and later). Minecraft's own
 * border matches it only in the Overworld (Region Guard sets the other worlds' to unlimited), so graves read this one
 * as well (fix GB-1). Every miss is "no border": the grave rule then has Minecraft's border alone.
 */
public final class RegionGuard {
    public static final String MOD_ID = "bsp_region_guard";
    private static final String API = "com.mrgregles.bsp_region_guard.api.RegionGuardApi";
    private static final String SERVICE = "com.mrgregles.bsp_region_guard.api.RegionService";

    /** A world's border as edges: x from {@code minX} to {@code maxX}, z from {@code minZ} to {@code maxZ}, the whole height. */
    public record Square(double minX, double maxX, double minZ, double maxZ) {
        /** A square {@code size} blocks across around a centre, as Region Guard keeps it. */
        public static Square around(double centerX, double centerZ, double size) {
            double half = size / 2.0;
            return new Square(centerX - half, centerX + half, centerZ - half, centerZ + half);
        }
    }

    /** GameTests only: borders to use instead of Region Guard's, per world (the dev run has no Region Guard). */
    private static final Map<ResourceKey<Level>, Square> TEST_BORDERS = new ConcurrentHashMap<>();
    private static volatile boolean resolved;
    @Nullable private static Method get, regions, border;
    private static volatile boolean failedOnce;

    private RegionGuard() {}

    /** GameTests only: act as if Region Guard had this border for the world; null to stop. */
    public static void borderForTests(ResourceKey<Level> world, @Nullable Square square) {
        if (square == null) {
            TEST_BORDERS.remove(world);
        } else {
            TEST_BORDERS.put(world, square);
        }
    }

    /** Region Guard's border for the world, or null (no border there, Region Guard not installed, or it could not be read). */
    @Nullable
    public static Square border(ServerLevel level) {
        if (!TEST_BORDERS.isEmpty()) {
            Square forTests = TEST_BORDERS.get(level.dimension());
            if (forTests != null) {
                return forTests;
            }
        }
        if (net.minecraftforge.fml.ModList.get() == null || !net.minecraftforge.fml.ModList.get().isLoaded(MOD_ID)) {
            return null;
        }
        resolve();
        if (get == null || regions == null || border == null) {
            return null;
        }
        try {
            Object api = Reflect.call(get, null);
            Object service = api == null ? null : Reflect.call(regions, api);
            Object b = service == null ? null : Reflect.call(border, service, level);
            if (b == null) {
                return null;
            }
            double cx = ((Number) Reflect.call(b.getClass().getMethod("centerX"), b)).doubleValue();
            double cz = ((Number) Reflect.call(b.getClass().getMethod("centerZ"), b)).doubleValue();
            double size = ((Number) Reflect.call(b.getClass().getMethod("size"), b)).doubleValue();
            return size > 0 ? Square.around(cx, cz, size) : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!failedOnce) {
                failedOnce = true;
                BSPInventorySync.LOGGER.warn("BSP-InventorySync: Region Guard's world border could not be read ({}); graves keep inside Minecraft's own border only", e.toString());
            }
            return null;
        }
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        Class<?> api = Reflect.type(API);
        get = Reflect.method(api, "get");
        regions = Reflect.method(api, "regions");
        border = Reflect.method(Reflect.type(SERVICE), "border", ServerLevel.class);
        if (get == null || regions == null || border == null) {
            BSPInventorySync.LOGGER.warn("BSP-InventorySync: Region Guard is installed but its world border api was not found (RegionGuardApi.regions().border); "
                    + "graves keep inside Minecraft's own border only");
        }
    }
}
