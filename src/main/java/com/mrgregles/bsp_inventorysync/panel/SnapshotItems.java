package com.mrgregles.bsp_inventorysync.panel;

import com.mrgregles.bsp_inventorysync.api.ItemArea;
import com.mrgregles.bsp_inventorysync.api.ItemPlace;
import com.mrgregles.bsp_inventorysync.api.ItemSlot;
import com.mrgregles.bsp_inventorysync.compat.BspCore;
import com.mrgregles.bsp_inventorysync.data.ItemCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The staff panel's view of a decoded snapshot (D29 and its audit): every area of the player's items, read
 * and changed in the stored form of the mod it belongs to, so a change rewrites only the slots it touches and
 * every other slot (an unknown mod's item included) stays byte for byte as it was.
 * <ul>
 * <li>{@code inventory} and {@code ender_chest}: this mod's {@code {Slot, Item}} entries ({@code data/vanilla/Slots});</li>
 * <li>Curios: its own tag, {@code {Curios: [{Identifier, StacksHandler: {Stacks: {Items, Size}, Cosmetics, HasCosmetic}}]}},
 * the slots as Forge's item handler stores them ({@code {Slot, id, Count, tag}});</li>
 * <li>Sophisticated Backpacks: {@code Packs: [{Uuid, Contents: {inventory: {Items, Size}}}]}, slots with the true
 * count in {@code realCount} (the vanilla byte count overflows above 127);</li>
 * <li>Dank Storage: {@code Danks: [{Frequency, Contents: {Items, GhostItems, DankStats}}]}, the true count in
 * {@code ExtendedCount}, the size from the tier.</li>
 * </ul>
 * Reading never adds anything to the root: a section the snapshot lacks (its module failed to save) has no
 * area, and nothing is written into it. In a snapshot, backpacks and danks only give items up (their filters,
 * memorised slots and stack upgrades are not stored with them); live, they take adds through the mods' own
 * checks ({@link LiveItems}). A linked-storage backpack (its item tagged {@code linked_storage.endpoint}) keeps
 * its items in another backpack: read-only.
 */
final class SnapshotItems {
    static final String INVENTORY = "inventory";
    static final String ARMOR = "armor";
    static final String OFFHAND = "offhand";
    static final String CURSOR = "cursor";
    static final String ENDER_CHEST = "ender_chest";
    static final String CURIOS = "curios/";
    static final String CURIOS_COSMETIC = "curios_cosmetic/";
    static final String BACKPACK = "backpack/";
    static final String DANK = "dank/";
    static final String GRAVE = "grave";

    static final String CONTENTS_UUID = "contentsUuid";
    static final String DANK_FREQUENCY = "dankstorage:frequency";
    /** The marker on a live read's slot entry for a Shatter Totem shown as an inert copy ({@link LiveItems}). */
    static final String LOCKED = "bsp_inventorysync:locked";
    /** Dank Storage 16's tiers ({@code DankStats}): slots per dank. */
    private static final Map<String, Integer> DANK_SLOTS = Map.of("zero", 0, "one", 9, "two", 18, "three", 27, "four", 36, "five", 45, "six", 54, "seven", 81);

    private SnapshotItems() {}

    /** One area of the snapshot: its view, which reads and writes the root. */
    record Area(String id, ItemArea.Kind kind, NbtView view) {}

    /** Where each backpack and dank sits, and which backpacks are linked-storage endpoints. */
    record Places(Map<String, ItemPlace> places, Set<String> linked) {}

    /** Whether an add may go into the area: inventory and ender chest; backpacks and danks only live. */
    static boolean addable(String id, boolean live) {
        return id.equals(INVENTORY) || id.equals(ENDER_CHEST) || (live && (id.startsWith(BACKPACK) || id.startsWith(DANK)));
    }

    /** The module a vanilla area lives in, or null for the others. */
    @Nullable
    static String vanillaModule(String id) {
        return switch (id) {
            case INVENTORY, ARMOR, OFFHAND, CURSOR -> "inventory";
            case ENDER_CHEST -> "ender_chest";
            default -> null;
        };
    }

    @Nullable
    static ItemArea.Kind kind(@Nullable String id) {
        if (id == null) {
            return null;
        }
        return switch (id) {
            case INVENTORY -> ItemArea.Kind.INVENTORY;
            case ARMOR -> ItemArea.Kind.ARMOR;
            case OFFHAND -> ItemArea.Kind.OFFHAND;
            case CURSOR -> ItemArea.Kind.CURSOR;
            case ENDER_CHEST -> ItemArea.Kind.ENDER_CHEST;
            case GRAVE -> ItemArea.Kind.GRAVE;
            default -> id.startsWith(CURIOS) ? ItemArea.Kind.CURIOS : id.startsWith(CURIOS_COSMETIC) ? ItemArea.Kind.CURIOS_COSMETIC
                    : id.startsWith(BACKPACK) ? ItemArea.Kind.BACKPACK : id.startsWith(DANK) ? ItemArea.Kind.DANK : null;
        };
    }

    /** Every area the snapshot has, in the order {@code PlayerItems} lists them. */
    static Map<String, Area> areas(CompoundTag root) {
        Map<String, Area> out = new LinkedHashMap<>();
        CompoundTag modules = root.getCompound("Modules");
        if (modules.contains("inventory", Tag.TAG_COMPOUND)) {
            CompoundTag inv = modules.getCompound("inventory");
            out.put(INVENTORY, new Area(INVENTORY, ItemArea.Kind.INVENTORY, new NbtSlots(inv, "Items", 0, 36, Format.VANILLA, SnapshotItems::vanillaLimit)));
            out.put(ARMOR, new Area(ARMOR, ItemArea.Kind.ARMOR, new NbtSlots(inv, "Items", 36, 4, Format.VANILLA, SnapshotItems::vanillaLimit)));
            out.put(OFFHAND, new Area(OFFHAND, ItemArea.Kind.OFFHAND, new NbtSlots(inv, "Items", 40, 1, Format.VANILLA, SnapshotItems::vanillaLimit)));
            out.put(CURSOR, new Area(CURSOR, ItemArea.Kind.CURSOR, new Cursor(inv)));
        }
        if (modules.contains("ender_chest", Tag.TAG_COMPOUND)) {
            CompoundTag ender = modules.getCompound("ender_chest");
            int size = Math.max(27, highestSlot(ender.getList("Items", Tag.TAG_COMPOUND)) + 1);
            out.put(ENDER_CHEST, new Area(ENDER_CHEST, ItemArea.Kind.ENDER_CHEST, new NbtSlots(ender, "Items", 0, size, Format.VANILLA, SnapshotItems::vanillaLimit)));
        }
        if (modules.contains("curios", Tag.TAG_COMPOUND)) {
            for (Tag t : modules.getCompound("curios").getCompound("Curios").getList("Curios", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) t;
                String type = entry.getString("Identifier");
                CompoundTag handler = entry.getCompound("StacksHandler");
                if (type.isEmpty()) {
                    continue;
                }
                out.put(CURIOS + type, new Area(CURIOS + type, ItemArea.Kind.CURIOS, handlerSlots(handler.getCompound("Stacks"))));
                if (handler.getBoolean("HasCosmetic")) {
                    out.put(CURIOS_COSMETIC + type, new Area(CURIOS_COSMETIC + type, ItemArea.Kind.CURIOS_COSMETIC, handlerSlots(handler.getCompound("Cosmetics"))));
                }
            }
        }
        if (modules.contains("backpacks", Tag.TAG_COMPOUND)) {
            for (Tag t : modules.getCompound("backpacks").getList("Packs", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) t;
                if (!entry.hasUUID("Uuid")) {
                    continue;
                }
                CompoundTag inventory = entry.getCompound("Contents").getCompound("inventory");
                int size = inventory.contains("Size", Tag.TAG_ANY_NUMERIC) ? inventory.getInt("Size") : highestSlot(inventory.getList("Items", Tag.TAG_COMPOUND)) + 1;
                String id = BACKPACK + entry.getUUID("Uuid");
                out.putIfAbsent(id, new Area(id, ItemArea.Kind.BACKPACK, new NbtSlots(inventory, "Items", 0, size, Format.BACKPACK, (slot, stack) -> stack.getMaxStackSize())));
            }
        }
        if (modules.contains("dank", Tag.TAG_COMPOUND)) {
            for (Tag t : modules.getCompound("dank").getList("Danks", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) t;
                CompoundTag contents = entry.getCompound("Contents");
                int size = Math.max(DANK_SLOTS.getOrDefault(contents.getString("DankStats"), 0), highestSlot(contents.getList("Items", Tag.TAG_COMPOUND)) + 1);
                String id = DANK + entry.getInt("Frequency");
                out.putIfAbsent(id, new Area(id, ItemArea.Kind.DANK, new NbtSlots(contents, "Items", 0, size, Format.DANK, (slot, stack) -> stack.getMaxStackSize())));
            }
        }
        return out;
    }

    /** The areas as the api hands them out, with where each backpack and dank sits. */
    static List<ItemArea> read(CompoundTag root, boolean live) {
        return toApi(areas(root), live, false, false);
    }

    /**
     * What one grave holds (SU-IS3), read-only: the area {@code grave}, one slot per stack (a live Shatter Totem as a locked
     * inert copy), then the backpacks and danks inside it, from the grave's extras ({@code Packs} and {@code Danks}, the
     * forms the sync stores them in), each with where it sits.
     */
    static List<ItemArea> readGrave(List<ItemStack> stacks, CompoundTag extras) {
        Map<String, Area> areas = new LinkedHashMap<>();
        areas.put(GRAVE, new Area(GRAVE, ItemArea.Kind.GRAVE, new GraveView(stacks)));
        CompoundTag modules = new CompoundTag();
        CompoundTag packs = new CompoundTag();
        packs.put("Packs", extras.getList("Packs", Tag.TAG_COMPOUND).copy());
        modules.put("backpacks", packs);
        CompoundTag danks = new CompoundTag();
        danks.put("Danks", extras.getList("Danks", Tag.TAG_COMPOUND).copy());
        modules.put("dank", danks);
        CompoundTag root = new CompoundTag();
        root.put("Modules", modules);
        areas.putAll(areas(root)); // only backpack and dank areas: the root has no inventory or ender chest
        return toApi(areas, false, true, true);
    }

    /**
     * The areas as the api hands them out. Every slot holding a live Shatter Totem, or an item carrying one anywhere
     * inside it (a shulker box's contents, a backpack's stored contents), is locked, and its copy is made inert first:
     * the totem itself becomes a spent husk, and so does every totem nested in the copy's data, so no read ever hands a
     * live totem out (D29, SU-IS3 audit). {@code onlyReachable}: an area of a backpack or dank no slot leads to (contents
     * a grave kept after its item was taken) is left out.
     */
    private static List<ItemArea> toApi(Map<String, Area> areas, boolean live, boolean readOnly, boolean onlyReachable) {
        Places places = places(areas);
        Set<String> reachable = onlyReachable ? reachable(areas, places) : areas.keySet();
        List<ItemArea> out = new ArrayList<>();
        for (Area a : areas.values()) {
            if (!reachable.contains(a.id())) {
                continue;
            }
            List<ItemSlot> slots = new ArrayList<>();
            for (int i = 0; i < a.view().size(); i++) {
                ItemStack stack = a.view().get(i).copy();
                String missing = ItemCodec.isPlaceholder(stack) ? stack.getTag().getCompound(ItemCodec.ORIGINAL).getString("id") : null;
                boolean locked = a.view().locked(i) || (!stack.isEmpty() && carriesTotem(stack, areas));
                slots.add(new ItemSlot(i, locked ? inert(stack) : stack, missing, locked));
            }
            boolean linked = places.linked().contains(a.id());
            out.add(new ItemArea(a.id(), a.kind(), !readOnly && !linked && addable(a.id(), live), !readOnly && !linked, places.places().get(a.id()), slots));
        }
        return out;
    }

    /**
     * Where each backpack and dank sits: every slot of every area is searched through its whole item tag, so a
     * backpack in a shulker box (or deeper) is found with the containers in between. A backpack inside another
     * backpack sits in that backpack's area. The first place found wins.
     */
    static Places places(Map<String, Area> areas) {
        Map<String, ItemPlace> found = new HashMap<>();
        Set<String> linked = new HashSet<>();
        for (Area a : areas.values()) {
            for (int i = 0; i < a.view().size(); i++) {
                CompoundTag raw = a.view().raw(i);
                if (raw != null) {
                    walk(raw, a.id(), i, List.of(), found, linked);
                }
            }
        }
        return new Places(found, linked);
    }

    private static void walk(CompoundTag item, String area, int slot, List<String> inside, Map<String, ItemPlace> found, Set<String> linked) {
        CompoundTag tag = item.getCompound("tag");
        if (tag.hasUUID(CONTENTS_UUID)) {
            String id = BACKPACK + tag.getUUID(CONTENTS_UUID);
            found.putIfAbsent(id, new ItemPlace(area, slot, inside));
            if (tag.getCompound("linked_storage").contains("endpoint")) {
                linked.add(id);
            }
        }
        CompoundTag settings = tag.getCompound("settings");
        if (settings.contains(DANK_FREQUENCY, Tag.TAG_ANY_NUMERIC)) {
            found.putIfAbsent(DANK + settings.getInt(DANK_FREQUENCY), new ItemPlace(area, slot, inside));
        }
        List<String> deeper = new ArrayList<>(inside);
        deeper.add(item.getString("id"));
        forEachItem(tag, nested -> walk(nested, area, slot, deeper, found, linked));
    }

    /** Every item compound in a tag tree ({@code id} and {@code Count}), not looking inside the items found. */
    static void forEachItem(Tag tag, Consumer<CompoundTag> visit) {
        if (tag instanceof CompoundTag c) {
            if (c.contains("id", Tag.TAG_STRING) && c.contains("Count", Tag.TAG_ANY_NUMERIC)) {
                visit.accept(c);
                return;
            }
            for (String key : c.getAllKeys()) {
                forEachItem(c.get(key), visit);
            }
        } else if (tag instanceof ListTag list) {
            for (Tag t : list) {
                forEachItem(t, visit);
            }
        }
    }

    /**
     * Whether the stack is a live Shatter Totem or holds one anywhere: inside its item tag (a shulker box's
     * contents, at any depth) or in the stored contents of a backpack it is or holds, as {@code areas} has them.
     */
    static boolean carriesTotem(ItemStack stack, Map<String, Area> areas) {
        return carriesTotem(stack, areas, new HashSet<>());
    }

    private static boolean carriesTotem(ItemStack stack, Map<String, Area> areas, Set<String> seen) {
        if (stack.isEmpty()) {
            return false;
        }
        if (BspCore.isLiveTotem(stack)) {
            return true;
        }
        CompoundTag raw = ItemCodec.save(stack);
        CompoundTag tag = raw.getCompound("tag");
        if (tag.hasUUID(CONTENTS_UUID)) {
            String id = BACKPACK + tag.getUUID(CONTENTS_UUID);
            Area pack = areas.get(id);
            if (pack != null && seen.add(id)) {
                for (int i = 0; i < pack.view().size(); i++) {
                    if (carriesTotem(pack.view().get(i), areas, seen)) {
                        return true;
                    }
                }
            }
        }
        boolean[] found = {false};
        forEachItem(tag, nested -> found[0] |= carriesTotem(ItemCodec.load(nested.copy()), areas, seen));
        return found[0];
    }

    /** Whether the item, or anything inside its tag, carries a backpack's contents id, a dank's frequency or a storage link. */
    static boolean carriesStorageId(ItemStack stack) {
        boolean[] found = {false};
        Consumer<CompoundTag> check = new Consumer<>() {
            @Override
            public void accept(CompoundTag item) {
                CompoundTag tag = item.getCompound("tag");
                if (tag.hasUUID(CONTENTS_UUID) || tag.getCompound("settings").contains(DANK_FREQUENCY) || tag.contains("linked_storage")) {
                    found[0] = true;
                }
                forEachItem(tag, this);
            }
        };
        check.accept(ItemCodec.save(stack));
        return found[0];
    }

    // ------------------------------------------------------------------ views

    /** A view over stored slots; {@link #raw} is a slot's stored item compound (the original item for a missing mod's). */
    abstract static class NbtView implements SlotView {
        @Nullable
        abstract CompoundTag raw(int slot);

        /** A live read's totem shown as an inert copy. */
        boolean locked(int slot) {
            return false;
        }
    }

    private static NbtSlots handlerSlots(CompoundTag handler) {
        int size = handler.contains("Size", Tag.TAG_ANY_NUMERIC) ? handler.getInt("Size") : highestSlot(handler.getList("Items", Tag.TAG_COMPOUND)) + 1;
        return new NbtSlots(handler, "Items", 0, size, Format.HANDLER, (slot, stack) -> stack.getMaxStackSize());
    }

    /** How each mod stores a slot: where the item compound is, and where its true count is. */
    enum Format {
        /** This mod's {@code {Slot, Item: <item>}}. */
        VANILLA,
        /** Forge's item handler: the item compound with {@code Slot}. */
        HANDLER,
        /** Sophisticated Core: as {@link #HANDLER}, the count in {@code realCount}. */
        BACKPACK,
        /** Dank Storage: as {@link #HANDLER}, the count in {@code ExtendedCount}. */
        DANK;

        @Nullable
        String countKey() {
            return this == BACKPACK ? "realCount" : this == DANK ? "ExtendedCount" : null;
        }
    }

    /**
     * Slots {@code offset .. offset + size - 1} of the list {@code parent.key}, shown as {@code 0 .. size - 1}.
     * The list is put into the parent only when a slot is written, never by reading.
     */
    static final class NbtSlots extends NbtView {
        private final CompoundTag parent;
        private final String key;
        private final int offset;
        private final int size;
        private final Format format;
        private final BiFunction<Integer, ItemStack, Integer> limit;

        NbtSlots(CompoundTag parent, String key, int offset, int size, Format format, BiFunction<Integer, ItemStack, Integer> limit) {
            this.parent = parent;
            this.key = key;
            this.offset = offset;
            this.size = Math.max(0, size);
            this.format = format;
            this.limit = limit;
        }

        @Override
        public int size() {
            return size;
        }

        private ListTag list() {
            return parent.getList(key, Tag.TAG_COMPOUND);
        }

        @Nullable
        private CompoundTag entry(int slot) {
            if (slot < 0 || slot >= size) {
                return null;
            }
            for (Tag t : list()) {
                if (t instanceof CompoundTag c && c.getInt("Slot") == offset + slot) {
                    return c;
                }
            }
            return null;
        }

        @Nullable
        @Override
        CompoundTag raw(int slot) {
            CompoundTag e = entry(slot);
            if (e == null) {
                return null;
            }
            return format == Format.VANILLA ? e.getCompound("Item") : e;
        }

        @Override
        boolean locked(int slot) {
            CompoundTag e = entry(slot);
            return e != null && e.getBoolean(LOCKED);
        }

        @Override
        public ItemStack get(int slot) {
            CompoundTag e = entry(slot);
            CompoundTag item = raw(slot);
            if (e == null || item == null || item.isEmpty()) {
                return ItemStack.EMPTY;
            }
            CompoundTag copy = item.copy();
            String countKey = format.countKey();
            boolean trueCount = countKey != null && e.contains(countKey, Tag.TAG_ANY_NUMERIC);
            if (trueCount) {
                copy.putByte("Count", (byte) 1); // the byte count of 256 is 0, which would read as empty
            }
            ItemStack stack = ItemCodec.load(copy);
            if (!stack.isEmpty() && trueCount) {
                stack.setCount(e.getInt(countKey));
            }
            return stack;
        }

        @Override
        public void set(int slot, ItemStack stack) {
            if (!parent.contains(key, Tag.TAG_LIST)) {
                parent.put(key, new ListTag());
            }
            ListTag list = list();
            CompoundTag old = entry(slot);
            if (old != null) {
                list.remove(old);
            }
            if (stack.isEmpty()) {
                return;
            }
            CompoundTag item = ItemCodec.save(stack);
            CompoundTag entry;
            if (format == Format.VANILLA) {
                entry = new CompoundTag();
                entry.put("Item", item);
            } else {
                entry = item;
                if (format.countKey() != null) {
                    entry.putInt(format.countKey(), stack.getCount());
                }
            }
            entry.putInt("Slot", offset + slot);
            list.add(entry);
        }

        @Override
        public int limit(int slot, ItemStack stack) {
            return limit.apply(slot, stack);
        }
    }

    /** The cursor: one item compound in the inventory module. */
    static final class Cursor extends NbtView {
        static final String CURSOR_LOCKED = "CursorLocked";
        private final CompoundTag inv;

        Cursor(CompoundTag inv) {
            this.inv = inv;
        }

        @Override
        public int size() {
            return 1;
        }

        @Nullable
        @Override
        CompoundTag raw(int slot) {
            return slot == 0 && inv.contains("Cursor", Tag.TAG_COMPOUND) ? inv.getCompound("Cursor") : null;
        }

        @Override
        boolean locked(int slot) {
            return slot == 0 && inv.getBoolean(CURSOR_LOCKED);
        }

        @Override
        public ItemStack get(int slot) {
            CompoundTag item = raw(slot);
            return item == null ? ItemStack.EMPTY : ItemCodec.load(item.copy());
        }

        @Override
        public void set(int slot, ItemStack stack) {
            if (stack.isEmpty()) {
                inv.remove("Cursor");
            } else {
                inv.put("Cursor", ItemCodec.save(stack));
            }
        }

        @Override
        public int limit(int slot, ItemStack stack) {
            return vanillaLimit(slot, stack);
        }
    }

    /** A grave's stacks, read-only; a live totem shown as its locked inert copy, never handed out live. */
    static final class GraveView extends NbtView {
        private final List<ItemStack> shown = new ArrayList<>();
        private final List<CompoundTag> raw = new ArrayList<>();
        private final List<Boolean> locked = new ArrayList<>();

        GraveView(List<ItemStack> stacks) {
            for (ItemStack s : stacks) {
                boolean totem = BspCore.isLiveTotem(s);
                ItemStack copy = shown(s);
                shown.add(copy.copy());
                raw.add(ItemCodec.save(copy));
                locked.add(totem);
            }
        }

        @Override
        public int size() {
            return shown.size();
        }

        @Nullable
        @Override
        CompoundTag raw(int slot) {
            return slot >= 0 && slot < raw.size() ? raw.get(slot) : null;
        }

        @Override
        boolean locked(int slot) {
            return slot >= 0 && slot < locked.size() && locked.get(slot);
        }

        @Override
        public ItemStack get(int slot) {
            return slot >= 0 && slot < shown.size() ? shown.get(slot) : ItemStack.EMPTY;
        }

        @Override
        public void set(int slot, ItemStack stack) {
            throw new UnsupportedOperationException("a grave is read-only here");
        }

        @Override
        public int limit(int slot, ItemStack stack) {
            return 0;
        }
    }

    /**
     * The areas some slot leads to: the first one (the grave, or a player's own areas, which need no slot) and every
     * backpack or dank placed in an area already reached, until nothing more is added.
     */
    private static Set<String> reachable(Map<String, Area> areas, Places places) {
        Set<String> in = new HashSet<>();
        for (Area a : areas.values()) {
            if (a.kind() != ItemArea.Kind.BACKPACK && a.kind() != ItemArea.Kind.DANK) {
                in.add(a.id());
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, ItemPlace> e : places.places().entrySet()) {
                if (areas.containsKey(e.getKey()) && !in.contains(e.getKey()) && in.contains(e.getValue().area())) {
                    in.add(e.getKey());
                    grew = true;
                }
            }
        }
        return in;
    }

    /**
     * A copy with no live Shatter Totem in it: the stack itself made a spent husk if it is one, and every totem found in
     * its data (at any depth, a missing mod's item included) replaced by the same husk.
     */
    static ItemStack inert(ItemStack stack) {
        ItemStack out = shown(stack).copy();
        if (out.hasTag()) {
            huskNested(out.getTag());
        }
        return out;
    }

    private static void huskNested(Tag tag) {
        forEachItem(tag, item -> {
            if (BspCore.isLiveTotem(ItemCodec.load(item.copy()))) {
                CompoundTag spent = new CompoundTag();
                spent.putBoolean("Spent", true);
                item.put("tag", spent);
            } else if (item.contains("tag", Tag.TAG_COMPOUND)) {
                huskNested(item.getCompound("tag"));
            }
        });
    }

    /** What a read may hand out: the stack itself, or for a live totem an inert copy (spent, nothing else of its identity). */
    static ItemStack shown(ItemStack s) {
        if (!BspCore.isLiveTotem(s)) {
            return s;
        }
        ItemStack husk = s.copyWithCount(s.getCount());
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Spent", true);
        husk.setTag(tag);
        return husk;
    }

    // ------------------------------------------------------------------ helpers

    private static int vanillaLimit(int slot, ItemStack stack) {
        return Math.min(64, stack.getMaxStackSize());
    }

    private static int highestSlot(ListTag list) {
        int max = -1;
        for (Tag t : list) {
            if (t instanceof CompoundTag c) {
                max = Math.max(max, c.getInt("Slot"));
            }
        }
        return max;
    }

    /** A backpack area's contents id, or null. */
    @Nullable
    static UUID backpackId(String areaId) {
        if (!areaId.startsWith(BACKPACK)) {
            return null;
        }
        try {
            return UUID.fromString(areaId.substring(BACKPACK.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
