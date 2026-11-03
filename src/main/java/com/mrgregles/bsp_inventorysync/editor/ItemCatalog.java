package com.mrgregles.bsp_inventorysync.editor;

import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The editor's item list (D21): every item and block, grouped by creative tab, or one list of the
 * items whose name or id contains a search word. Never a Shatter Totem (BSP-Core's rule: totems are
 * never created), never a placeholder, never air.
 */
public final class ItemCatalog {
    /** One page source: a name for the Items button and its items, one of each. */
    public record Category(String name, List<ItemStack> items) {}

    private ItemCatalog() {}

    /** The creative tabs that have items, built on the server; one "All items" list when they cannot be built. */
    public static List<Category> categories(MinecraftServer server) {
        List<Category> out = new ArrayList<>();
        try {
            CreativeModeTabs.tryRebuildTabContents(server.getWorldData().enabledFeatures(), true, server.registryAccess());
            for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
                if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
                    continue;
                }
                List<ItemStack> items = new ArrayList<>();
                for (ItemStack s : tab.getDisplayItems()) {
                    if (offered(s)) {
                        items.add(s.copyWithCount(1));
                    }
                }
                if (!items.isEmpty()) {
                    out.add(new Category(tab.getDisplayName().getString(), items));
                }
            }
        } catch (RuntimeException e) {
            out.clear();
        }
        if (out.isEmpty()) {
            out.add(new Category("All items", registry(null)));
        }
        return out;
    }

    /** One category of every item whose id or name contains {@code word} (any case). */
    public static Category search(String word) {
        return new Category("Search: " + word, registry(word.toLowerCase(Locale.ROOT)));
    }

    private static List<ItemStack> registry(String word) {
        List<ItemStack> out = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            ItemStack s = new ItemStack(item);
            if (!offered(s)) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (word == null || (id != null && id.toString().contains(word)) || s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(word)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Whether the list may offer this item. */
    public static boolean offered(ItemStack s) {
        if (s.isEmpty() || s.is(Items.AIR) || ItemCodec.isPlaceholder(s) || BspCore.isLiveTotem(s)) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.getItem());
        return id == null || !id.toString().equals("bsp_core:shatter_totem"); // spent husks too: a totem is never made here
    }
}
