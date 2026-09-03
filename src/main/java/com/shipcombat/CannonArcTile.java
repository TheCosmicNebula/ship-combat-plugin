package com.shipcombat;

import net.runelite.api.coords.LocalPoint;

public final class CannonArcTile
{
    final LocalPoint center;
    final net.runelite.api.WorldView worldView;
    int cannonMask;

    CannonArcTile(LocalPoint center, net.runelite.api.WorldView worldView, int cannonMask)
    {
        this.center = center;
        this.worldView = worldView;
        this.cannonMask = cannonMask;
    }
}