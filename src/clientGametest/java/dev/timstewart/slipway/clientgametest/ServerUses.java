package dev.timstewart.slipway.clientgametest;

import java.util.ArrayDeque;
import java.util.Deque;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;

/** The last block uses the server received (Fabric's use-block event, server side), for failure messages. */
final class ServerUses {
	private static final Deque<String> LAST = new ArrayDeque<>();
	private static boolean installed;

	private ServerUses() {
	}

	static synchronized void install() {
		if (installed) {
			return;
		}
		installed = true;
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide()) {
				synchronized (LAST) {
					LAST.addLast(player.getName().getString() + " used " + hit.getBlockPos().toShortString() + " (" + level.getBlockState(hit.getBlockPos()) + ")");
					while (LAST.size() > 5) {
						LAST.removeFirst();
					}
				}
			}
			return InteractionResult.PASS;
		});
	}

	static String last() {
		synchronized (LAST) {
			return LAST.isEmpty() ? "none" : String.join("; ", LAST);
		}
	}
}
