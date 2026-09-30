package dev.timstewart.slipway.packagedcheck.mixin;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Test harness only (never shipped), the same workaround as the client GameTests' IntegratedServerHaltMixin: closing a
 * singleplayer world under Fabric's client GameTest threading can deadlock in IntegratedServer.halt's blocking task
 * when Distant Horizons' client close outlasts the server's tick (DESIGN.md, "Testing").
 */
@Mixin(IntegratedServer.class)
public abstract class PackagedCheckHaltMixin {
	@Redirect(method = "halt", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/server/IntegratedServer;executeBlocking(Ljava/lang/Runnable;)V"))
	private void slipwayPackagedCheck$queueInsteadOfBlocking(IntegratedServer server, Runnable task) {
		((BlockableEventLoop<?>)(Object)server).execute(task);
	}
}
