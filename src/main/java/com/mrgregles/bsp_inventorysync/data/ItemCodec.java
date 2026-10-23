package com.mrgregles.bsp_inventorysync.data;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Item stacks to and from NBT, with the rule for items the server does not know (D8): an item whose
 * id is not registered here (its mod is missing, or an older version lacks it) becomes a placeholder,
 * a named barrier carrying the original compound in the tag {@value #ORIGINAL}. When a placeholder
 * is saved again, the original compound is written instead, so the item comes back the moment it
 * lands on a server that has its mod. Placeholders stack only with themselves.
 *
 * <p>The base item is a barrier (D14): no recipe, villager trade, furnace or composter uses it, so the
 * original cannot be crafted or traded away, and its icon reads as "not usable here". Placing it is
 * blocked by {@link PlaceholderGuard}. Placeholders made before D14 are paper; they are recognised by
 * their tag like any other and become barriers at their next restore on a server without the mod.
 */
public final class ItemCodec {
    /** The placeholder's tag holding the original item compound. */
    public static final String ORIGINAL = BSPInventorySync.MODID + ":original";

    private ItemCodec() {}

    /** The compound for a stack: the original compound for a placeholder, else the stack as vanilla saves it. */
    public static CompoundTag save(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(ORIGINAL, CompoundTag.TAG_COMPOUND)) {
            CompoundTag original = tag.getCompound(ORIGINAL).copy();
            original.putByte("Count", (byte) stack.getCount()); // the stack may have been split or merged while it was a placeholder
            return original;
        }
        return stack.save(new CompoundTag());
    }

    /** The stack for a compound; a placeholder when the item id is unknown here; empty for an empty compound. */
    public static ItemStack load(CompoundTag tag) {
        if (tag.isEmpty()) {
            return ItemStack.EMPTY;
        }
        String id = tag.getString("id");
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        if (item == null || (item == Items.AIR && !key.equals(ForgeRegistries.ITEMS.getKey(Items.AIR)))) {
            return placeholder(tag);
        }
        return ItemStack.of(tag);
    }

    /** Whether this stack is one of this mod's placeholders. */
    public static boolean isPlaceholder(ItemStack stack) {
        return !stack.isEmpty() && stack.hasTag() && stack.getTag().contains(ORIGINAL, CompoundTag.TAG_COMPOUND);
    }

    private static ItemStack placeholder(CompoundTag original) {
        int count = Math.max(1, original.getByte("Count"));
        ItemStack placeholder = new ItemStack(Items.BARRIER, count);
        CompoundTag tag = placeholder.getOrCreateTag();
        tag.put(ORIGINAL, original.copy());
        String id = original.getString("id");
        placeholder.setHoverName(Component.literal("Missing item: " + id).withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("Turns back into the item on a server with its mod.").withStyle(ChatFormatting.GRAY))));
        lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("Keep it: it is the real item.").withStyle(ChatFormatting.GRAY))));
        tag.getCompound("display").put("Lore", lore);
        return placeholder;
    }
}
