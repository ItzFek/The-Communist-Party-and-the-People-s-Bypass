package .module.modules;

import .event.EventTarget;
import .events.MotionEvent;
import .events.PacketEvent;
import .events.WorldEvent;
import .module.Module;
import .property.properties.BooleanProperty;
import .property.properties.FloatProperty;
import .property.properties.IntegerProperty;
import .utils.Rotation;
import .utils.RotationUtils;
import .utils.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChestAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty chest = new BooleanProperty("Chest", true);
    public final BooleanProperty enderChest = new BooleanProperty("EnderChest", true);
    public final BooleanProperty furnace = new BooleanProperty("Furnace", true);
    public final FloatProperty range = new FloatProperty("Range", 4.5f, 1.0f, 6.0f);
    public final IntegerProperty delay = new IntegerProperty("Delay", 100, 0, 500);
    public final BooleanProperty throughWalls = new BooleanProperty("ThroughWalls", true);
    public final FloatProperty wallsRange = new FloatProperty("WallsRange", 3.0f, 0.0f, 6.0f);
    public final BooleanProperty visualSwing = new BooleanProperty("VisualSwing", true);
    public final BooleanProperty noUsing = new BooleanProperty("NoUsing", true);
    public final FloatProperty turnSpeed = new FloatProperty("TurnSpeed", 100.0f, 10.0f, 180.0f);
    public final BooleanProperty rotations = new BooleanProperty("Rotations", true);

    private final TimerUtil timer = new TimerUtil();
    private final Set<TileEntity> clickedTileEntities = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<BlockPos, Long> recentPlacements = new ConcurrentHashMap<>();

    private float rangeSq;
    private float searchRadiusSq;
    private float wallsRangeSq;

    public ChestAura() {
        super("ChestAura", false);
    }

    @Override
    public void onEnable() {
        updateRangeSq(range.get());
        updateWallsRangeSq(wallsRange.get());
    }

    @Override
    public void onDisable() {
        clickedTileEntities.clear();
        recentPlacements.clear();
    }

    private void updateRangeSq(float value) {
        rangeSq = value * value;
        searchRadiusSq = (value + 1) * (value + 1);
    }

    private void updateWallsRangeSq(float value) {
        wallsRangeSq = value * value;
    }

    private boolean shouldClickTileEntity(TileEntity tile) {
        if (tile instanceof TileEntityChest) return chest.get();
        if (tile instanceof TileEntityEnderChest) return enderChest.get();
        if (tile instanceof TileEntityFurnace) return furnace.get();
        return false;
    }

    private boolean isBlockedByWalls(BlockPos pos) {
        if (throughWalls.get()) return false;
        double distSq = mc.thePlayer.getPosition().distanceSq(pos);
        float maxWallRange = Math.min(wallsRange.get(), range.get());
        return distSq > maxWallRange * maxWallRange;
    }

    @EventTarget
    public void onWorld(WorldEvent event) {
        clickedTileEntities.clear();
        recentPlacements.clear();
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (noUsing.get() && mc.thePlayer.isBlocking()) return;
        if (mc.currentScreen != null) return;

        double px = mc.thePlayer.posX;
        double py = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double pz = mc.thePlayer.posZ;

        TileEntity targetTile = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3 bestHitVec = null;

        for (TileEntity tile : mc.theWorld.loadedTileEntityList) {
            if (!shouldClickTileEntity(tile)) continue;
            if (clickedTileEntities.contains(tile)) continue;

            BlockPos pos = tile.getPos();
            double distSq = mc.thePlayer.getPosition().distanceSq(pos);
            if (distSq > searchRadiusSq) continue;
            if (isBlockedByWalls(pos)) continue;

            Vec3 hitVec = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            double distance = hitVec.distanceTo(new Vec3(px, py, pz));
            if (distance < bestDistance) {
                bestDistance = distance;
                targetTile = tile;
                bestHitVec = hitVec;
            }
        }

        if (targetTile == null || bestHitVec == null) return;

        if (timer.hasTimeElapsed(delay.get())) {
            Rotation targetRot = RotationUtils.toRotation(bestHitVec, true, mc.thePlayer);
            if (rotations.get()) {
                Rotation current = RotationUtils.getCurrentRotation();
                if (current != null) {
                    targetRot = RotationUtils.limitAngleChange(current, targetRot, turnSpeed.get());
                }
                RotationUtils.setTargetRotation(targetRot, true, true, false, 180f, 180f, 5f);
            }

            MovingObjectPosition rayTrace = RotationUtils.performRayTrace(targetRot.getYaw(), targetRot.getPitch(), range.get() + 1);
            if (rayTrace != null && rayTrace.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                BlockPos blockPos = rayTrace.getBlockPos();
                EnumFacing side = rayTrace.sideHit;
                Vec3 hitVecActual = rayTrace.hitVec;

                mc.addScheduledTask(() -> {
                    boolean success = onPlayerRightClick(mc.thePlayer, blockPos, side, hitVecActual, null);
                    if (success) {
                        if (visualSwing.get()) {
                            mc.thePlayer.swingItem();
                        } else {
                            mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
                        }
                        timer.reset();
                        clickedTileEntities.add(targetTile);
                    }
                });
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled()) return;
        Packet<?> packet = event.getPacket();
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement place = (C08PacketPlayerBlockPlacement) packet;
            BlockPos pos = place.getPosition();
            if (pos != null && (mc.theWorld.getBlockState(pos).getBlock() == Blocks.chest ||
                    mc.theWorld.getBlockState(pos).getBlock() == Blocks.trapped_chest)) {
                recentPlacements.put(pos, System.currentTimeMillis());
            }
        }
    }

    private boolean onPlayerRightClick(EntityPlayer player, BlockPos pos, EnumFacing side, Vec3 hitVec, ItemStack stack) {
        if (player == null) return false;
        mc.playerController.processRightClickBlock(player, mc.theWorld, pos, side, hitVec);
        return true;
    }
}
