package dev.timstewart.slipway.vessel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Slipway Helm. {@link #FACING} is the side with the wheel, which faces the pilot; the vessel's forward
 * direction is the opposite side (the way the player looked when placing it).
 *
 * <p>In the world, using the helm assembles the structure it is part of. On a vessel, using it takes the helm;
 * using it while sneaking disassembles the vessel.
 */
public class HelmBlock extends HorizontalDirectionalBlock {
	public HelmBlock(BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	/** The vessel's forward direction for a helm state. */
	public static Direction forward(BlockState state) {
		return state.getValue(FACING).getOpposite();
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
			VesselManager.get(serverLevel).useHelm(pos, serverPlayer);
		}
		return InteractionResult.SUCCESS;
	}
}
