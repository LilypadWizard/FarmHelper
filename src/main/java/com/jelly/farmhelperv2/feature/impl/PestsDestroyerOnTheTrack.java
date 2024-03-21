package com.jelly.farmhelperv2.feature.impl;

import com.jelly.farmhelperv2.config.FarmHelperConfig;
import com.jelly.farmhelperv2.feature.IFeature;
import com.jelly.farmhelperv2.handler.GameStateHandler;
import com.jelly.farmhelperv2.handler.MacroHandler;
import com.jelly.farmhelperv2.handler.RotationHandler;
import com.jelly.farmhelperv2.util.KeyBindUtils;
import com.jelly.farmhelperv2.util.LogUtils;
import com.jelly.farmhelperv2.util.PlayerUtils;
import com.jelly.farmhelperv2.util.helper.Clock;
import com.jelly.farmhelperv2.util.helper.RotationConfiguration;
import com.jelly.farmhelperv2.util.helper.Target;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Tuple;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class PestsDestroyerOnTheTrack implements IFeature {

    private final Minecraft mc = Minecraft.getMinecraft();
    private static PestsDestroyerOnTheTrack instance;

    public static PestsDestroyerOnTheTrack getInstance() {
        if (instance == null) {
            instance = new PestsDestroyerOnTheTrack();
        }
        return instance;
    }

    private boolean isRunning = false;

    @Getter
    private final Clock delayStart = new Clock();
    @Getter
    private final Clock stuckTimer = new Clock();

    private Entity currentTarget = null;

    @Override
    public String getName() {
        return "Pests Destroyer On The Track";
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public boolean shouldPauseMacroExecution() {
        return true;
    }

    @Override
    public boolean shouldStartAtMacroStart() {
        return false;
    }

    @Override
    public void start() {
        isRunning = true;
        currentTarget = null;
        if (MacroHandler.getInstance().isMacroToggled()) {
            MacroHandler.getInstance().pauseMacro();
        }
        ItemStack currentItem = mc.thePlayer.getHeldItem();
        PestsDestroyer.getInstance().getVacuum(currentItem);
        LogUtils.sendWarning("[" + getName() + "] Started!");
        stuckTimer.schedule(FarmHelperConfig.pestsDestroyerOnTheTrackStuckTimer);
    }

    @Override
    public void stop() {
        isRunning = false;
        LogUtils.sendWarning("[" + getName() + "] Stopped!");
        if (MacroHandler.getInstance().isMacroToggled()) {
            MacroHandler.getInstance().resumeMacro();
            PlayerUtils.getTool();
        }
        delayStart.reset();
        stuckTimer.reset();
    }

    @Override
    public void resetStatesAfterMacroDisabled() {
        isRunning = false;
        delayStart.reset();
        stuckTimer.reset();
    }

    @Override
    public boolean isToggled() {
        return FarmHelperConfig.pestsDestroyerOnTheTrack;
    }

    @Override
    public boolean shouldCheckForFailsafes() {
        return false;
    }

    @SubscribeEvent
    public void onTickShouldEnable(TickEvent.PlayerTickEvent event) {
        if (mc.thePlayer == null) return;
        if (!MacroHandler.getInstance().isMacroToggled()) return;
        if (PestsDestroyer.getInstance().isRunning()) return;
        if (GameStateHandler.getInstance().inJacobContest() && FarmHelperConfig.dontKillPestsOnTrackDuringJacobsContest)
            return;
        if (isRunning()) return;
        if (!isToggled()) return;

        if (getPest(true).isPresent()) {
            if (!delayStart.isScheduled()) {
                delayStart.schedule(900);
                LogUtils.sendDebug("[" + getName() + "] Found pest, waiting for him to stay in range!");
            }
            if (delayStart.isScheduled() && delayStart.passed()) {
                start();
            }
        } else {
            if (delayStart.isScheduled()) {
                delayStart.reset();
                LogUtils.sendDebug("[" + getName() + "] Pest left!");
            }
        }
    }

    @SubscribeEvent
    public void onTickExecution(TickEvent.PlayerTickEvent event) {
        if (mc.thePlayer == null) return;
        if (!MacroHandler.getInstance().isMacroToggled()) return;
        if (!isRunning()) return;
        if (!isToggled()) {
            stop();
            return;
        }

        if (stuckTimer.isScheduled() && stuckTimer.passed()) {
            LogUtils.sendWarning("[" + getName() + "] Stuck for too long, stopping!");
            stop();
            return;
        }

        ItemStack currentItem = mc.thePlayer.getHeldItem();
        PestsDestroyer.getInstance().getVacuum(currentItem);

        Optional<Entity> entity = getPest(false);
        if (entity.isPresent()) {
            if (currentTarget == null || !currentTarget.equals(entity.get())) {
                currentTarget = entity.get();
                stuckTimer.schedule(FarmHelperConfig.pestsDestroyerOnTheTrackStuckTimer);
            }
            KeyBindUtils.holdThese(mc.gameSettings.keyBindUseItem);
            if (!RotationHandler.getInstance().isRotating()) {
                RotationHandler.getInstance().easeTo(
                        new RotationConfiguration(
                                new Target(entity.get()),
                                FarmHelperConfig.getRandomRotationTime(),
                                null
                        )
                );
            }
        } else {
            stop();
        }
    }

    private Optional<Entity> getPest(boolean start) {
        List<Entity> entities = PestsDestroyer.getInstance().getPestsLocations();
        float vacuumRange = PestsDestroyer.getInstance().getCurrentVacuumRange();
        this.entities.clear();
        return entities.stream()
                .filter(e -> {
                    Vec3 entityPosition = new Vec3(e.posX, e.posY + e.getEyeHeight(), e.posZ);
                    Vec3 playerPosition = mc.thePlayer.getPositionEyes(1);
                    double dist = playerPosition.distanceTo(entityPosition);
                    boolean returnResult;
                    if (start) {
                        double xDiff = entityPosition.xCoord - playerPosition.xCoord;
                        double zDiff = entityPosition.zCoord - playerPosition.zCoord;

                        float yaw = (float) Math.toDegrees(Math.atan2(zDiff, xDiff)) - 90F;
                        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw) - yaw);
                        returnResult = dist <= vacuumRange - 0.5 && yawDiff <= FarmHelperConfig.pestsDestroyerOnTheTrackFOV / 2f;
                    } else {
                        returnResult = dist <= vacuumRange - 0.5;
                    }
                    if (returnResult) {
                        this.entities.add(new Tuple<>(e, dist));
                    }
                    return returnResult;
                }).min((e1, e2) -> {
                    double d1 = e1.getDistanceToEntity(mc.thePlayer);
                    double d2 = e2.getDistanceToEntity(mc.thePlayer);
                    return Double.compare(d1, d2);
                });
    }

    @Getter
    private final CopyOnWriteArrayList<Tuple<Entity, Double>> entities = new CopyOnWriteArrayList<>();
}
