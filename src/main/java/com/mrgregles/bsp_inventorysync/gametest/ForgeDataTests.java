package com.mrgregles.bsp_inventorysync.gametest;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import com.mrgregles.bsp_inventorysync.compat.Curios;
import com.mrgregles.bsp_inventorysync.compat.DankStorage;
import com.mrgregles.bsp_inventorysync.compat.SophisticatedBackpacks;
import com.mrgregles.bsp_inventorysync.data.Modules;
import com.mrgregles.bsp_inventorysync.data.PlayerSnapshotCodec;
import com.mrgregles.bsp_inventorysync.data.RestoreContext;
import com.mrgregles.bsp_inventorysync.data.vanilla.ForgeDataModule;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The generic Forge data module (D11): persistent NBT with excluded paths kept per server, and
 * capabilities through Forge's own serialisation, checked with a capability this test attaches to
 * players. Also the backpack UUID scan the Sophisticated Backpacks adapter relies on, the restore
 * context, and the adapters' absence when their mods are not loaded (they are not, in this dev run).
 */
@GameTestHolder(BSPInventorySync.MODID)
@PrefixGameTestTemplate(false)
public class ForgeDataTests {
    private static final String EMPTY = "empty";

    /** A per-player value a mod might keep: attached to every player while the listener is registered. */
    public static final class Counter implements ICapabilitySerializable<CompoundTag> {
        public static final Capability<Counter> CAP = CapabilityManager.get(new CapabilityToken<>() {});
        public static final ResourceLocation KEY = ResourceLocation.parse("bsp_inventorysync:test_counter");
        private final LazyOptional<Counter> self = LazyOptional.of(() -> this);
        public int value;

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
            return cap == CAP ? self.cast() : LazyOptional.empty();
        }

        @Override
        public CompoundTag serializeNBT() {
            CompoundTag t = new CompoundTag();
            t.putInt("Value", value);
            return t;
        }

        @Override
        public void deserializeNBT(CompoundTag t) {
            value = t.getInt("Value");
        }
    }

    static final class Attacher {
        @SubscribeEvent
        public void attach(AttachCapabilitiesEvent<Entity> event) {
            if (event.getObject() instanceof Player) {
                event.addCapability(Counter.KEY, new Counter());
            }
        }
    }

    private static ForgeDataModule module(List<String> dataExclusions, List<String> capExclusions) {
        return new ForgeDataModule(() -> dataExclusions, () -> capExclusions);
    }

    @GameTest(template = EMPTY)
    public static void persistent_data_round_trips_and_excluded_paths_stay_local(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        CompoundTag data = p.getPersistentData();
        data.putString("waystones:known", "spawn,home");
        data.putInt("starterkit:given", 1);
        CompoundTag persisted = new CompoundTag();
        persisted.putBoolean("bsp_core:TotemGranted", true);
        persisted.putString("bsp_core:LastMainDim", "minecraft:overworld");
        persisted.putLong("bsp_core:LastMainPos", 12345L);
        data.put(Player.PERSISTED_NBT_TAG, persisted);

        ForgeDataModule m = module(Modules.DEFAULT_DATA_EXCLUSIONS, Modules.DEFAULT_CAP_EXCLUSIONS);
        CompoundTag out = new CompoundTag();
        m.save(p, out);
        CompoundTag saved = out.getCompound("Data");
        h.assertTrue(saved.getString("waystones:known").equals("spawn,home") && saved.getInt("starterkit:given") == 1, "mod keys saved");
        CompoundTag savedPersisted = saved.getCompound(Player.PERSISTED_NBT_TAG);
        h.assertTrue(savedPersisted.getBoolean("bsp_core:TotemGranted"), "the grant travels");
        h.assertTrue(!savedPersisted.contains("bsp_core:LastMainDim") && !savedPersisted.contains("bsp_core:LastMainPos"), "the server-local positions are left out: " + savedPersisted);

        // another server: different local positions, stale other data; the restore replaces the data and keeps the positions
        ServerPlayer q = TestPlayers.mockServerPlayer(h);
        CompoundTag qp = new CompoundTag();
        qp.putString("bsp_core:LastMainDim", "minecraft:the_nether");
        qp.putLong("bsp_core:LastMainPos", 999L);
        qp.putBoolean("bsp_core:TotemGranted", false);
        q.getPersistentData().put(Player.PERSISTED_NBT_TAG, qp);
        q.getPersistentData().putInt("stale:key", 7);
        m.load(q, out);
        CompoundTag after = q.getPersistentData();
        h.assertTrue(after.getString("waystones:known").equals("spawn,home") && after.getInt("starterkit:given") == 1, "mod keys applied");
        h.assertTrue(!after.contains("stale:key"), "keys absent from the snapshot are gone");
        CompoundTag afterPersisted = after.getCompound(Player.PERSISTED_NBT_TAG);
        h.assertTrue(afterPersisted.getBoolean("bsp_core:TotemGranted"), "the grant applied");
        h.assertTrue(afterPersisted.getString("bsp_core:LastMainDim").equals("minecraft:the_nether") && afterPersisted.getLong("bsp_core:LastMainPos") == 999L, "this server's positions kept: " + afterPersisted);

        // the whole codec carries the module too
        h.assertTrue(Modules.get(ForgeDataModule.ID) != null, "the generic module is registered");
        CompoundTag snapshot = PlayerSnapshotCodec.capture(p);
        h.assertTrue(PlayerSnapshotCodec.module(snapshot, ForgeDataModule.ID).getCompound("Data").getInt("starterkit:given") == 1, "in the snapshot");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void capabilities_round_trip_through_forge_and_excluded_ids_are_skipped(GameTestHelper h) {
        h.assertTrue(ForgeDataModule.capsSupported(), "Forge's serializeCaps and deserializeCaps are reachable");
        Attacher attacher = new Attacher();
        MinecraftForge.EVENT_BUS.register(attacher);
        try {
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            Counter c = p.getCapability(Counter.CAP).orElseThrow(() -> new AssertionError("no test capability on the mock player"));
            c.value = 42;
            ForgeDataModule m = module(List.of(), List.of());
            CompoundTag out = new CompoundTag();
            m.save(p, out);
            CompoundTag caps = out.getCompound("Caps");
            h.assertTrue(caps.getCompound(Counter.KEY.toString()).getInt("Value") == 42, "the capability is in the snapshot under its id: " + caps);

            ServerPlayer q = TestPlayers.mockServerPlayer(h);
            Counter qc = q.getCapability(Counter.CAP).orElseThrow(() -> new AssertionError("no test capability"));
            qc.value = 1;
            m.load(q, out);
            h.assertTrue(qc.value == 42, "the capability value arrived, got " + qc.value);

            // excluded: neither saved nor applied
            ForgeDataModule excluding = module(List.of(), List.of(Counter.KEY.toString()));
            CompoundTag out2 = new CompoundTag();
            excluding.save(p, out2);
            h.assertTrue(!out2.getCompound("Caps").contains(Counter.KEY.toString()), "an excluded capability is not saved");
            qc.value = 5;
            excluding.load(q, out); // the snapshot that does hold it
            h.assertTrue(qc.value == 5, "an excluded capability is not applied either");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(attacher);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void backpack_uuids_are_found_in_items_and_nested_contents(GameTestHelper h) {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();
        ItemStack pack = new ItemStack(Items.BUNDLE); // stands in for a backpack: only the tag matters to the scan
        pack.getOrCreateTag().putUUID(SophisticatedBackpacks.CONTENTS_UUID, outer);
        h.assertTrue(SophisticatedBackpacks.contentsUuid(pack).orElseThrow().equals(outer), "the contents uuid is read from the tag");
        h.assertTrue(SophisticatedBackpacks.contentsUuid(new ItemStack(Items.DIRT)).isEmpty(), "plain items have none");

        // contents of the outer pack holding another pack (the layout inside is the mod's; the scan walks the tree)
        CompoundTag contents = new CompoundTag();
        ListTag items = new ListTag();
        CompoundTag nested = new CompoundTag();
        nested.putString("id", "sophisticatedbackpacks:backpack");
        CompoundTag nestedTag = new CompoundTag();
        nestedTag.putUUID(SophisticatedBackpacks.CONTENTS_UUID, inner);
        nested.put("tag", nestedTag);
        items.add(nested);
        CompoundTag inv = new CompoundTag();
        inv.put("Items", items);
        contents.put("inventory", inv);
        Set<UUID> found = new LinkedHashSet<>();
        SophisticatedBackpacks.collectUuids(contents, found);
        h.assertTrue(found.equals(Set.of(inner)), "the nested uuid is found: " + found);

        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        p.getInventory().setItem(3, pack);
        ItemStack chestPack = new ItemStack(Items.BUNDLE);
        chestPack.getOrCreateTag().putUUID(SophisticatedBackpacks.CONTENTS_UUID, inner);
        p.getEnderChestInventory().setItem(0, chestPack);
        h.assertTrue(SophisticatedBackpacks.Module.carried(p).equals(Set.of(outer, inner)), "carried packs come from the inventory and the ender chest: " + SophisticatedBackpacks.Module.carried(p));
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void adapters_stay_out_without_their_mods_and_the_restore_context_is_set_during_apply(GameTestHelper h) {
        h.assertTrue(!Curios.present() && !SophisticatedBackpacks.present() && !DankStorage.present(), "none of the three mods is in this dev run");
        h.assertTrue(Modules.get(Curios.Module.ID) == null && Modules.get(SophisticatedBackpacks.Module.ID) == null && Modules.get(DankStorage.Module.ID) == null, "so none of their modules is registered");

        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        CompoundTag snapshot = PlayerSnapshotCodec.capture(p);
        h.assertTrue(RestoreContext.current() == null, "no context outside a restore");
        RestoreContext[] seen = new RestoreContext[1];
        Modules.register(new com.mrgregles.bsp_inventorysync.api.DataModule() {
            @Override
            public String id() {
                return "test_context";
            }

            @Override
            public void save(ServerPlayer player, CompoundTag out) {
            }

            @Override
            public void load(ServerPlayer player, CompoundTag in) {
                seen[0] = RestoreContext.current();
            }
        });
        try {
            snapshot.getCompound("Modules").put("test_context", new CompoundTag());
            PlayerSnapshotCodec.apply(p, snapshot, new RestoreContext("hub", "pvp"));
            h.assertTrue(seen[0] != null && seen[0].fromAnotherServer() && seen[0].sourceServer().equals("hub"), "modules see where the snapshot came from: " + seen[0]);
            PlayerSnapshotCodec.apply(p, snapshot, new RestoreContext("pvp", "pvp"));
            h.assertTrue(!seen[0].fromAnotherServer(), "a relog on the same server is not from another server");
            PlayerSnapshotCodec.apply(p, snapshot);
            h.assertTrue(seen[0] == null, "no context when none is given");
        } finally {
            Modules.unregister("test_context");
        }
        h.assertTrue(RestoreContext.current() == null, "the context is cleared after the apply");
        h.succeed();
    }
}
