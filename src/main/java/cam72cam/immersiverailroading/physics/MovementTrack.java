package cam72cam.immersiverailroading.physics;

import cam72cam.immersiverailroading.library.Gauge;
import cam72cam.immersiverailroading.library.TrackItems;
import cam72cam.immersiverailroading.tile.TileRail;
import cam72cam.immersiverailroading.tile.TileRailBase;
import cam72cam.immersiverailroading.track.IIterableTrack;
import cam72cam.immersiverailroading.track.PosStep;
import cam72cam.immersiverailroading.util.VecUtil;
import cam72cam.immersiverailroading.thirdparty.trackapi.ITrack;
import cam72cam.mod.world.World;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.math.Vec3i;
import cam72cam.mod.util.Facing;

import java.util.List;

public class MovementTrack {

	public static ITrack findTrack(World world, Vec3d currentPosition, float trainYaw, double gauge) {
		Vec3d[] positions = new Vec3d[] {
				currentPosition,
				currentPosition.add(VecUtil.fromWrongYaw(1, trainYaw)),
				currentPosition.add(VecUtil.fromWrongYaw(-1, trainYaw)),
		};
		
		double[] heightSkew = new double[] {
			0,
			0.25,
			-0.25,
			0.5,
			-0.5,
			0.75,
			-0.75
		};
		
		for (Vec3d pos : positions) {
			for (double height : heightSkew) {
				ITrack te = ITrack.get(world, pos.add(0, height + (currentPosition.y%1), 0), true);
				if (te != null && Gauge.from(te.getTrackGauge()) == Gauge.from(gauge)) {
					return te;
				}
				// HACK for cross gauge
				TileRailBase rail = world.getBlockEntity(new Vec3i(pos).add(new Vec3i(0, (int)(height + (currentPosition.y%1)), 0)), TileRailBase.class);
				if (rail != null && rail.getParentReplaced() != null) {
					return rail;
				}
			}
		}
		return null;
	}

	public static Vec3d iterativePathing(World world, Vec3d currentPosition, ITrack te, double gauge, Vec3d motion, double maxDistance) {
		Vec3d startPos = currentPosition;
		Vec3d prevPosition = currentPosition;
		double totalDistance = motion.length();
		double maxDistanceSquared = maxDistance * maxDistance;
		double motionLengthSquared = motion.lengthSquared();

		Vec3i teBlockPosition = new Vec3i(currentPosition);

		for (double currentDistance = 0; currentDistance < totalDistance; currentDistance += maxDistance) {
			Vec3i currentBlockPosition = new Vec3i(currentPosition);
			if (!currentBlockPosition.equals(teBlockPosition)) {
				teBlockPosition = currentBlockPosition;

				te = findTrack(world, currentPosition, VecUtil.toWrongYaw(motion), gauge);
				if (te == null) {
					// Stuck
					return currentPosition;
				}
			}

			// Correct motion length (if off by 5%)
			if (motionLengthSquared > maxDistanceSquared || motionLengthSquared < maxDistanceSquared * 0.95) {
				motion = motion.scale(maxDistance / Math.sqrt(motionLengthSquared));
			}

			prevPosition = currentPosition;
			currentPosition = te instanceof TileRailBase ? ((TileRailBase) te).getNextPositionShort(currentPosition, motion) : te.getNextPosition(currentPosition, motion);
			motion = currentPosition.subtract(prevPosition);
			motionLengthSquared = motion.lengthSquared();

			if (motionLengthSquared == 0) {
				// Stuck
				return prevPosition;
			}
		}

		// prevPosition + motion scaled to remaining distance
		//double scale = (totalDistance - startPos.distanceTo(prevPosition)) / motion.length();
		//if (scale > 0.00001 && scale < 1.5) {
		//	currentPosition = prevPosition.add(motion.scale(scale));
		//}
		currentPosition = startPos.add(currentPosition.subtract(startPos).normalize().scale(totalDistance));

		return currentPosition;
	}

	public static Vec3d nextPositionDirect(World world, Vec3d currentPosition, TileRail rail, Vec3d delta) {
		if (rail == null) {
			if (world.isServer) {
				return null; // OFF TRACK
			} else {
				return currentPosition.add(delta);
			}
		}

		double railHeight = rail.info.getTrackHeight();
		double distance = delta.length();
		double heightOffset = railHeight * rail.info.settings.gauge.scale();

		if (rail.info.settings.type == TrackItems.CROSSING) {
			delta = VecUtil.fromWrongYaw(distance, Facing.fromAngle(VecUtil.toWrongYaw(delta)).getAngle());
			return currentPosition.add(delta);
		} else if (rail.info.settings.type == TrackItems.TURNTABLE) {
			double tablePos = rail.getParentTile().info.tablePos;
			
			currentPosition = currentPosition.add(delta);
			
			Vec3d center = new Vec3d(rail.getParentTile().getPos()).add(0.5, 1 + heightOffset, 0.5);
			
			double fromCenter = currentPosition.distanceTo(center);
			
			float angle = (float)tablePos + rail.info.placementInfo.facing().getAngle();
			
			Vec3d forward = center.add(VecUtil.fromWrongYaw(fromCenter, angle));
			Vec3d backward = center.add(VecUtil.fromWrongYaw(fromCenter, angle + 180));
			
			if (forward.distanceToSquared(currentPosition) < backward.distanceToSquared(currentPosition)) {
				return forward;
			} else {
				return backward;
			}
		} else if (rail.info.getBuilder(world) instanceof IIterableTrack) {
			/*
			 * Discovery: This is not accurate for distances less than 0.1m
			 * Since we snap to the line, small distances are snapped to a tangent that's further than they are
			 * trying to move.  Instead we should probably calculate the vector between the closest pos
			 * and the current pos and move distance along that.  How would that work for slopes at the ends? just fine?
			 */
			List<PosStep> positions = ((IIterableTrack) rail.info.getBuilder(world)).getPath(0.25);
			Vec3d center = rail.info.placementInfo.placementPosition.add(rail.getPos());
			Vec3d relative = currentPosition.subtract(center);
			PosStep close = positions.get(0);
			for (PosStep pos : positions) {
				if (close.distanceToSquared(relative) > pos.distanceToSquared(relative)) {
					close = pos;
				}
			}
			
			Vec3d estimatedPosition = currentPosition.add(delta);
			
			Vec3d closePos = center.add(close).add(0, heightOffset, 0);
			double distToClose = closePos.distanceTo(estimatedPosition);

			Vec3d curveDelta = new Vec3d(distToClose, 0, 0);
			curveDelta = VecUtil.rotatePitch(curveDelta, -close.pitch);
			curveDelta = VecUtil.rotateYaw(curveDelta, close.yaw);

			Vec3d forward = closePos.add(curveDelta);
			Vec3d backward = closePos.subtract(curveDelta);



			if (forward.distanceToSquared(estimatedPosition) < backward.distanceToSquared(estimatedPosition)) {
				return forward;
			} else {
				return backward;
			}
		}
		return currentPosition.add(delta);
	}
}
