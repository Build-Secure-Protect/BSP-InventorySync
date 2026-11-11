package com.mrgregles.bsp_inventorysync.mixin;

import com.mrgregles.bsp_inventorysync.machine.MachineGuard;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * D31 (audit AUD-8 1 a): every placement of a block item passes through {@code BlockItem.place}, whoever places it (a
 * player's click, Dank Storage's construction mode, a Create deployer, any other mod), before the block is set and before
 * Compact Machines' {@code setPlacedBy} binds it to a room. A Compact Machine whose room is on another server is refused
 * here: nothing is placed and the stack is untouched.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;", at = @At("HEAD"), cancellable = true)
    private void bsp_inventorysync$homeServerOnly(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (MachineGuard.refusesPlacement(context)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
