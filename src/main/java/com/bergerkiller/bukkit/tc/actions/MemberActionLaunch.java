package com.bergerkiller.bukkit.tc.actions;

import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;

public class MemberActionLaunch extends MemberAction implements MovementAction {
    private static final double minVelocity = 0.02;
    private static final double minVelocityForLaunch = 0.004;
    private double distance;
    private double targetdistance;
    private double targetvelocity;
    private double startvelocity;

    public MemberActionLaunch(double targetdistance, double targetvelocity) {
        this.distance = 0;
        this.targetdistance = targetdistance;
        this.targetvelocity = targetvelocity;
        this.distance = 0;
    }

    @Override
    public void start() {
        this.startvelocity = MathUtil.clamp(this.getMember().getForce(), this.getEntity().getMaxSpeed());
        if (this.startvelocity < minVelocity) {
            this.startvelocity = minVelocity;
        }
    }

    @Override
    public boolean isMovementSuppressed() {
        return true;
    }

    public double getTargetVelocity() {
        return this.targetvelocity;
    }

    public double getTargetDistance() {
        return this.targetdistance;
    }

    protected void setTargetDistance(double distance) {
        this.targetdistance = distance;
    }

    public double getDistance() {
        return this.distance;
    }

    @Override
    public boolean update() {
        // Abort when derailed. We do permit vertical 'air-launching'
        if (this.getMember().isDerailed() && !this.getMember().isMovingVerticalOnly()) {
            return true;
        }

        // Did any of the carts in the group stop?
        if (this.distance != 0) {
            for (MinecartMember<?> mm : this.getGroup()) {
                if (mm.getForceSquared() < minVelocityForLaunch * minVelocityForLaunch) {
                    //stopped
                    return true;
                }
            }
        }

        //Increment distance
        this.distance += this.getEntity().getMovedDistance();

        //Reached the target distance?
        double targetvel = this.targetvelocity; // * this.getGroup().getUpdateSpeedFactor();
        if (this.distance > this.targetdistance - 0.2) {
            // Finish with the desired end-velocity
            this.getGroup().setForwardForce(targetvel);
            return true;
        } else {
            //Get the velocity to set the carts to
            targetvel = MathUtil.clamp(targetvel, this.getEntity().getMaxSpeed());
            if (targetvel > 0 || (this.targetdistance - this.distance) < 5) {
                targetvel = MathUtil.lerp(this.startvelocity, targetvel, this.distance / this.targetdistance);
            } else {
                targetvel = this.startvelocity;
            }
            targetvel = Math.max(targetvel, minVelocity);
            this.getGroup().setForwardForce(targetvel);
            return false;
        }
    }
}
