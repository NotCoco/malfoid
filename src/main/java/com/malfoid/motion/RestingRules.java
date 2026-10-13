package com.malfoid.motion;

/** Small fixed courtesy multipliers, applied only at an existing movement decision. */
public final class RestingRules
{
    private RestingRules() {}

    public static double refine(double score, boolean inPlayerFacingCone, boolean groundItems,
        boolean knownRoofMismatch, int traversableExits)
    {
        if (inPlayerFacingCone) score *= .2;
        if (groundItems) score *= .2;
        if (knownRoofMismatch) score *= .3;
        if (traversableExits >= 0 && traversableExits <= 2) score *= .3;
        return score;
    }
}
