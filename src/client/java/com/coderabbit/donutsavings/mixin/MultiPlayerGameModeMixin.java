package com.coderabbit.donutsavings.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.coderabbit.donutsavings.client.PurchaseGuard;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;

/** Every container click goes through here before its packet is built. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), cancellable = true)
	private void donutsavings$guardPurchase(int containerId, int slotId, int button, ClickType type, Player player,
			CallbackInfo ci) {
		if (PurchaseGuard.onClick(containerId, slotId, button, type)) {
			ci.cancel();
		}
	}
}
