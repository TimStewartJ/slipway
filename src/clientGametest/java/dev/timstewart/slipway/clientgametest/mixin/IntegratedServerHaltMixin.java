package dev.timstewart.slipway.clientgametest.mixin;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Test harness only (never shipped). Under Fabric's client GameTests, closing a singleplayer world runs
 * Minecraft.disconnect at the start of a tick phase; IntegratedServer.halt then blocks on a task for the server thread
 * (removing players other than the owner). When the client's disconnect work before that point outlasts the server's
 * tick (Distant Horizons closes its client world and databases there), the server has already parked at the phase
 * barrier and neither side proceeds: a deadlock (thread dump in DESIGN.md, "Testing"). The task is queued without
 * waiting instead; the tests never share a world, so there are no other players to remove.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerHaltMixin {
	@Redirect(method = "halt", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/server/IntegratedServer;executeBlocking(Ljava/lang/Runnable;)V"))
	private void slipwayTest$queueInsteadOfBlocking(IntegratedServer server, Runnable task) {
		((BlockableEventLoop<?>)(Object)server).execute(task);
	}
}
