package com.malfoid.motion;

/** Bounded committed-tick evidence for cosmetic recovery and blocked-route backoff. */
public final class RecoveryPolicy
{
    private int offScreenTicks, blockedTicks, ineffectivePlans;
    private long lastRelocation = Long.MIN_VALUE;
    private long planNotBefore, retryAt;

    public void commit(int separation, WholeBoundsVisibility.Visibility visibility, boolean routeProgress,
        boolean confirmedBlocked, boolean destinationNearPlayer, boolean intervalKnown)
    {
        if (!intervalKnown)
        {
            offScreenTicks = blockedTicks = ineffectivePlans = 0;
            planNotBefore = retryAt = 0;
            return;
        }
        offScreenTicks = separation >= 12 && visibility == WholeBoundsVisibility.Visibility.OFF_SCREEN
            ? Math.min(5, offScreenTicks + 1) : 0;
        if (routeProgress)
        {
            blockedTicks = ineffectivePlans = 0;
            planNotBefore = retryAt = 0;
        }
        else
        {
            blockedTicks = confirmedBlocked && destinationNearPlayer ? Math.min(50, blockedTicks + 1) : 0;
        }
    }

    public void recordPlan(long tick, boolean ineffective, boolean reachableRefreshedDestination)
    {
        if (reachableRefreshedDestination)
        {
            ineffectivePlans = blockedTicks = 0;
            planNotBefore = retryAt = 0;
        }
        else if (ineffective)
        {
            ineffectivePlans = Math.min(3, ineffectivePlans + 1);
            if (ineffectivePlans >= 3)
            {
                planNotBefore = tick + 5;
                retryAt = tick + 30;
            }
        }
    }

    public boolean mayPlan(long tick, boolean meaningfulChange)
    {
        return tick >= planNotBefore && (ineffectivePlans < 3 || meaningfulChange || tick >= retryAt);
    }

    public boolean offScreenEligible(long tick, int separation)
    {
        return cooldownPassed(tick) && separation >= 12 && offScreenTicks >= 5
            && (ineffectivePlans >= 3 || separation >= 16);
    }

    public boolean stuckEligible(long tick)
    {
        return cooldownPassed(tick) && blockedTicks >= 50;
    }

    public boolean confirmOrigin(WholeBoundsVisibility.Visibility current, boolean stuckRecovery)
    {
        if (stuckRecovery)
        {
            return true;
        }
        if (current != WholeBoundsVisibility.Visibility.OFF_SCREEN)
        {
            offScreenTicks = 0;
            return false;
        }
        return true;
    }

    public void relocated(long tick)
    {
        lastRelocation = tick;
        offScreenTicks = blockedTicks = ineffectivePlans = 0;
        planNotBefore = retryAt = 0;
    }

    private boolean cooldownPassed(long tick)
    {
        return lastRelocation == Long.MIN_VALUE || tick - lastRelocation >= 60;
    }

    public void reset()
    {
        offScreenTicks = blockedTicks = ineffectivePlans = 0;
        planNotBefore = retryAt = 0;
        lastRelocation = Long.MIN_VALUE;
    }

    public int getOffScreenTicks() { return offScreenTicks; }
    public int getBlockedTicks() { return blockedTicks; }
    public int getIneffectivePlans() { return ineffectivePlans; }
    public long getPlanNotBefore() { return planNotBefore; }
    public long getRetryAt() { return retryAt; }
}
