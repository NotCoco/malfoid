package com.malfoid.motion;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.malfoid.motion.WholeBoundsVisibility.Visibility.*;

public class RecoveryPolicyTest
{
    @Test public void threeIneffectivePlansAndFiveOffScreenCommitsAreBothNeededAtTwelve()
    {
        RecoveryPolicy policy = new RecoveryPolicy();
        for (int tick = 1; tick <= 3; tick++) policy.recordPlan(tick, true, false);
        for (int tick = 1; tick <= 4; tick++)
        {
            policy.commit(12, OFF_SCREEN, false, true, true, true);
            assertFalse(policy.offScreenEligible(tick, 12));
        }
        policy.commit(12, OFF_SCREEN, false, true, true, true);
        assertTrue(policy.offScreenEligible(5, 12));
        assertFalse(policy.offScreenEligible(5, 11));
        assertFalse(policy.confirmOrigin(VISIBLE, false));
        assertEquals(0, policy.getOffScreenTicks());
        assertFalse(policy.offScreenEligible(6, 12));
    }

    @Test public void equalSpeedLargeGapCanRecoverAfterFiveKnownOffScreenTicks()
    {
        RecoveryPolicy policy = new RecoveryPolicy();
        for (int tick = 1; tick <= 5; tick++) policy.commit(16, OFF_SCREEN, true, false, true, true);
        assertTrue(policy.offScreenEligible(5, 16));
        assertTrue(policy.confirmOrigin(OFF_SCREEN, false));
        policy.relocated(5);
        for (int tick = 6; tick <= 10; tick++) policy.commit(16, OFF_SCREEN, false, false, true, true);
        assertFalse(policy.offScreenEligible(64, 16));
        assertTrue(policy.offScreenEligible(65, 16));
    }

    @Test public void unknownOrVisibleOriginResetsTheOffScreenWitness()
    {
        RecoveryPolicy policy = new RecoveryPolicy();
        for (int tick = 0; tick < 5; tick++) policy.commit(16, OFF_SCREEN, false, false, true, true);
        assertTrue(policy.offScreenEligible(5, 16));
        policy.commit(16, UNKNOWN, false, false, true, true);
        assertFalse(policy.offScreenEligible(6, 16));
        for (int tick = 0; tick < 4; tick++) policy.commit(16, OFF_SCREEN, false, false, true, true);
        assertFalse(policy.offScreenEligible(10, 16));
        policy.commit(16, VISIBLE, false, false, true, true);
        assertEquals(0, policy.getOffScreenTicks());
    }

    @Test public void p15RequiresFiftyConfirmedBlockedTicksWithoutProgress()
    {
        RecoveryPolicy policy = new RecoveryPolicy();
        for (int tick = 1; tick < 50; tick++) policy.commit(4, VISIBLE, false, true, true, true);
        assertFalse(policy.stuckEligible(49));
        policy.commit(4, VISIBLE, false, true, true, true);
        assertTrue(policy.stuckEligible(50));
        assertTrue(policy.confirmOrigin(VISIBLE, true));
        policy.commit(4, VISIBLE, true, true, true, true);
        assertFalse(policy.stuckEligible(51));
        for (int tick = 0; tick < 100; tick++) policy.commit(4, VISIBLE, false, false, true, true);
        assertFalse(policy.stuckEligible(200));
        for (int tick = 0; tick < 49; tick++) policy.commit(4, VISIBLE, false, true, true, true);
        policy.recordPlan(250, false, true);
        assertEquals(0, policy.getBlockedTicks());
        policy.commit(4, VISIBLE, false, true, true, false);
        assertEquals(0, policy.getBlockedTicks());
    }

    @Test public void ineffectiveSearchesBackOffButLegalProgressAndMeaningfulChangeResume()
    {
        RecoveryPolicy policy = new RecoveryPolicy();
        for (int tick = 1; tick <= 3; tick++) policy.recordPlan(tick, true, false);
        assertFalse(policy.mayPlan(7, true));
        assertTrue(policy.mayPlan(8, true));
        assertFalse(policy.mayPlan(8, false));
        assertTrue(policy.mayPlan(33, false));
        policy.commit(16, OFF_SCREEN, true, false, true, true);
        assertTrue(policy.mayPlan(4, false));
        policy.recordPlan(4, false, true);
        assertEquals(0, policy.getIneffectivePlans());
    }
}
