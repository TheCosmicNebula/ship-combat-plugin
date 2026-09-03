package com.shipcombat;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.Area;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public class ShipBoundariesOverlay extends Overlay
{
    private static final int SWEEP_STEPS = 16;
    private static final int FULL_ROTATION = 2048;
    private static final int ANGLE_STEP = FULL_ROTATION / SWEEP_STEPS;

    private static final BasicStroke SWEEP_STROKE = new BasicStroke(1.5f);

    @Inject
    private Client client;

    @Inject
    private ShipCombatPlugin plugin;

    @Inject
    private ShipCombatConfig config;

    /*
     * Reusable arrays.
     * Avoid allocating these every frame/rotation.
     */
    private final float[] modelXs = new float[4];
    private final float[] modelYs = new float[4];
    private final float[] modelZs = {0, 0, 0, 0};

    private final int[] canvasXs = new int[4];
    private final int[] canvasYs = new int[4];

    @Inject
    public ShipBoundariesOverlay()
    {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(0.4f);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showShipSweep())
        {
            return null;
        }

        WorldEntity boat = plugin.getBoatWorldEntity();

        if (boat == null)
        {
            return null;
        }

        renderMergedDaisy(graphics, boat);

        return null;
    }

    private void renderMergedDaisy(Graphics2D graphics,WorldEntity we)
    {
        LocalPoint centerLp = we.getTargetLocation();

        if (centerLp == null)
        {
            return;
        }

        WorldEntityConfig wec = we.getConfig();

        /*
         * Instead of rendering every individual hull tile, represent the entire ship as one rectangle.
         * The old tile renderer ultimately covered exactly these same bounds.
         */
        float halfWidth = wec.getBoundsWidth() / 2.0f;
        float halfHeight = wec.getBoundsHeight() / 2.0f;

        modelXs[0] = -halfWidth;
        modelXs[1] = halfWidth;
        modelXs[2] = halfWidth;
        modelXs[3] = -halfWidth;

        modelYs[0] = -halfHeight;
        modelYs[1] = -halfHeight;
        modelYs[2] = halfHeight;
        modelYs[3] = halfHeight;

        Area combinedArea = new Area();

        /*
         * Only 16 model-to-canvas projections now:
         *
         * OLD:
         * 16 × numberOfHullTiles
         *
         * NEW:
         * 16
         */
        for (int step = 0; step < SWEEP_STEPS; step++)
        {
            int loopAngle = step * ANGLE_STEP;

            Perspective.modelToCanvas(client, client.getTopLevelWorldView(), 4, centerLp.getX(), centerLp.getY(), 0, loopAngle, modelXs, modelYs, modelZs, canvasXs, canvasYs);

            if (!hasVisiblePoint())
            {
                continue;
            }

            Polygon hull = new Polygon(canvasXs, canvasYs, 4);
            combinedArea.add(new Area(hull));
        }

        if (combinedArea.isEmpty())
        {
            return;
        }

        graphics.setColor(config.shipSweepFillColor());
        graphics.fill(combinedArea);

        graphics.setStroke(SWEEP_STROKE);
        graphics.setColor(config.shipSweepBorderColor());
        graphics.draw(combinedArea);
    }

    private boolean hasVisiblePoint()
    {
        for (int i = 0; i < 4; i++)
        {
            if (canvasXs[i] != 0 || canvasYs[i] != 0)
            {
                return true;
            }
        }

        return false;
    }
}