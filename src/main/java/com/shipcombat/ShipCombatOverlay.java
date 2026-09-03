package com.shipcombat;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;

import com.google.common.base.Strings;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.ColorUtil;

public class ShipCombatOverlay extends Overlay
{
    private static final Stroke STROKE_THICK = new java.awt.BasicStroke(2.0f);
    private static final Stroke STROKE_THIN  = new java.awt.BasicStroke(1.0f);
    private static final Font FONT_TICK         = new Font(Font.SANS_SERIF, Font.BOLD, 17);
    private static final Font FONT_MONSTER_TICK = new Font(Font.SANS_SERIF, Font.BOLD, 14);
    private static final int TEXT_Z_OFFSET = 40;

    @Inject private Client client;
    @Inject private ShipCombatPlugin plugin;
    @Inject private ShipCombatConfig config;

    private int cachedWorldViewId = -1;
    private int cachedShipCenterX = -1;

    @Inject
    public ShipCombatOverlay()
    {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(0.25f);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Player local = client.getLocalPlayer();
        if (local == null) return null;

        WorldEntity we = plugin.getBoatWorldEntity();
        if (we != null)
        {
            if (config.showShipTiles()) renderShipTiles(graphics, we);
            if (config.showCannonRange())
            {
                if (config.onlyShowArcWhenManning())
                {
                    GameObject playerCannon = plugin.getPlayerCannon();

                    if (plugin.isPlayerAtCannon() && playerCannon != null)
                    {
                        renderCannonArcs(graphics, java.util.Collections.singletonList(playerCannon));
                    }
                }
                else
                {
                    renderCannonArcs(graphics, plugin.getTrackedCannons());
                }
            }

            if (config.showCannonTick() && plugin.getCannonTicksRemaining() > 0) renderCannonTickOverhead(graphics, local);
        }

        renderMonsters(graphics, local, we);
        renderCorpseTimers(graphics);
        return null;
    }

    private void renderShipTiles(Graphics2D graphics, WorldEntity we)
    {
        LocalPoint center = we.getTargetLocation();
        if (center == null) return;
        WorldEntityConfig wec = we.getConfig();
        int angle = we.getTargetOrientation(), halfTile = 64, tileSize = 128;
        int halfW = wec.getBoundsWidth() / 2, halfH = wec.getBoundsHeight() / 2;

        Color color = config.shipTileColor();
        graphics.setColor(withAlpha(color, color.getAlpha()));

        for (int mx = -halfW + halfTile; mx <= halfW - halfTile; mx += tileSize)
        {
            for (int my = -halfH + halfTile; my <= halfH - halfTile; my += tileSize)
            {
                float[] xs = {mx - halfTile, mx + halfTile, mx + halfTile, mx - halfTile};
                float[] ys = {my - halfTile, my - halfTile, my + halfTile, my + halfTile};
                Polygon poly = modelToCanvasPoly(center, angle, xs, ys, client.getTopLevelWorldView());
                if (poly != null)
                {
                    graphics.fillPolygon(poly);
                    graphics.setStroke(STROKE_THIN);
                    graphics.setColor(withAlpha(color, Math.min(255, color.getAlpha() * 2)));
                    graphics.drawPolygon(poly);
                    graphics.setColor(withAlpha(color, color.getAlpha()));
                }
            }
        }
    }

    private void renderCannonArcs(Graphics2D graphics, List<GameObject> cannons)
    {
        if (cannons == null || cannons.isEmpty())
        {
            return;
        }

        /*
         * Key = unique local tile coordinate.
         *
         * Value tells us:
         * - where the tile is
         * - which WorldView it belongs to
         * - which cannon(s) cover it
         */
        Map<Long, CannonArcTile> tiles = new HashMap<>();

        int range = config.cannonRangeTiles();
        int tileSize = 128;

        /*
         * PASS 1:
         * Work out coverage without drawing anything.
         */
        for (int cannonIndex = 0; cannonIndex < cannons.size(); cannonIndex++)
        {
            GameObject cannon = cannons.get(cannonIndex);

            if (cannon == null)
            {
                continue;
            }

            LocalPoint cannonLp = cannon.getLocalLocation();
            if (cannonLp == null)
            {
                continue;
            }

            WorldView worldView = cannon.getWorldView();

            /*
             * Bit 0 = cannon 1
             * Bit 1 = cannon 2
             * etc.
             */
            int cannonBit = 1 << cannonIndex;

            int shipCenterX = getShipCenterX(worldView);
            int offX = cannonLp.getX() - shipCenterX;

            for (int mx = -range; mx <= range; mx++)
            {
                for (int my = -range; my <= range; my++)
                {
                    // Outside circular cannon range
                    if (mx * mx + my * my > range * range)
                    {
                        continue;
                    }

                    /*
                     * Preserve your existing port/starboard arc restriction.
                     */
                    if ((offX >= 0 && mx < 0) || (offX < 0 && mx > 0))
                    {
                        continue;
                    }

                    int tileX = cannonLp.getX() + (mx * tileSize);
                    int tileY = cannonLp.getY() + (my * tileSize);

                    /*
                     * One key for one physical tile.
                     */
                    long key = (((long) tileX) << 32) ^ (tileY & 0xffffffffL);
                    CannonArcTile tile = tiles.get(key);

                    if (tile == null)
                    {
                        LocalPoint center = new LocalPoint(tileX, tileY);
                        tile = new CannonArcTile( center, worldView, cannonBit);
                        tiles.put(key, tile);
                    }
                    else
                    {
                        /*
                         * Already covered by another cannon.
                         *
                         * Don't draw it again -- just record that this
                         * cannon also covers the tile.
                         */
                        tile.cannonMask |= cannonBit;
                    }
                }
            }
        }

        /*
         * PASS 2:
         * Render each physical tile exactly once.
         */
        for (CannonArcTile tile : tiles.values())
        {
            int cannonCount = Integer.bitCount(tile.cannonMask);

            Color lineColor;
            Color fillColor;

            if (cannonCount > 1)
            {
                // Covered by multiple cannons
                lineColor = config.cannonOverlapColor();
                fillColor = withAlpha(lineColor, config.cannonOverlapColor().getAlpha());
            }
            else if ((tile.cannonMask & 1) != 0)
            {
                // Cannon 1 only
                lineColor = config.cannonOneColor();
                fillColor = withAlpha(lineColor, config.cannonOneColor().getAlpha());
            }
            else
            {
                // Cannon 2 only
                lineColor = config.cannonTwoColor();
                fillColor = withAlpha(lineColor, config.cannonTwoColor().getAlpha());
            }

            renderCannonArcTile(graphics, tile.center, tile.worldView, lineColor, fillColor);
        }
    }

    private void renderCannonArcTile(Graphics2D graphics, LocalPoint center, net.runelite.api.WorldView worldView, Color lineColor, Color fillColor)
    {
        int halfTile = 64;

        float[] xs =
                {
                        -halfTile,
                        halfTile,
                        halfTile,
                        -halfTile
                };

        float[] ys =
                {
                        -halfTile,
                        -halfTile,
                        halfTile,
                        halfTile
                };

        Polygon poly = modelToCanvasPoly(center, 0, xs, ys, worldView
        );

        if (poly == null)
        {
            return;
        }

        graphics.setColor(fillColor);
        graphics.fillPolygon(poly);

        graphics.setStroke(STROKE_THIN);
        graphics.setColor(lineColor);
        graphics.drawPolygon(poly);
    }

    private int getThreatLevel(WorldEntity boat, NPC npc, int attackRange)
    {
        LocalPoint boatCenter = boat.getTargetLocation(), npcCenter = npc.getLocalLocation();
        if (boatCenter == null || npcCenter == null) return 0;

        WorldEntityConfig wec = boat.getConfig();
        int ts = 128, h = 64;

        // 1. DAISY CHECK: Does the NPC fall within the max possible swing radius?
        double radius = Math.sqrt(Math.pow(wec.getBoundsWidth()/2.0, 2) + Math.pow(wec.getBoundsHeight()/2.0, 2));
        double distToCenter = Math.sqrt(Math.pow(npcCenter.getX()-boatCenter.getX(), 2) + Math.pow(npcCenter.getY()-boatCenter.getY(), 2));
        if (distToCenter > radius + (attackRange * ts) + h) return 0;

        // 2. HULL CHECK: Is the NPC touching the hull AT THIS INSTANT?
        double rad = Math.toRadians(boat.getTargetOrientation() * (360.0 / 2048.0));
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double hitThreshold = (attackRange * ts) + h + 20;

        for (int x = 0; x < wec.getBoundsWidth()/ts; x++) {
            for (int y = 0; y < wec.getBoundsHeight()/ts; y++) {
                int mX = (x * ts) - (wec.getBoundsWidth() / 2) + h;
                int mY = (y * ts) - (wec.getBoundsHeight() / 2) + h;
                double rX = mX * cos - mY * sin, rY = mX * sin + mY * cos;
                if (Math.sqrt(Math.pow(npcCenter.getX()-(boatCenter.getX()+rX), 2) + Math.pow(npcCenter.getY()-(boatCenter.getY()+rY), 2)) <= hitThreshold) return 2;
            }
        }
        return 1;
    }

    private void renderMonsters(Graphics2D graphics, Player local, WorldEntity we)
    {
        for (Map.Entry<NPC, TrackedMonster> entry : plugin.getTrackedMonsters().entrySet())
        {
            NPC npc = entry.getKey();
            if (npc == null || npc.isDead()) continue;
            TrackedMonster tracked = entry.getValue();
            int range = tracked.getType().getAttackRangeTiles();
            int threat = (we != null) ? getThreatLevel(we, npc, range) : (npc.getWorldArea().distanceTo(local.getWorldLocation()) <= range ? 2 : 0);
            renderSingleMonster(graphics, npc, threat, range, tracked.getTicksUntilNextAttack(), tracked.isAttackImminent());
        }
    }

    private void renderSingleMonster(Graphics2D graphics, NPC npc, int threat, int range, int ticks, boolean imminent)
    {
        Color color = (threat == 2 && imminent) ? config.rangeColorImminent() : (threat > 0 ? config.rangeColorDanger() : config.rangeColorSafe());
        if (config.highlightMonsterTiles()) {
            Polygon poly = npc.getCanvasTilePoly();
            if (poly != null) {
                graphics.setColor(withAlpha(color, 50));
                graphics.fillPolygon(poly);
                graphics.setStroke(threat > 0 ? STROKE_THICK : STROKE_THIN);
                graphics.setColor(color);
                graphics.drawPolygon(poly);
            }
        }
        if (config.showMonsterRanges()) renderMonsterRangeBoundary(graphics, npc.getWorldArea(), range, color);
        if (config.showMonsterTicks() && ticks > 0) {
            graphics.setFont(FONT_MONSTER_TICK);
            Point pt = npc.getCanvasTextLocation(graphics, String.valueOf(ticks), npc.getLogicalHeight() + TEXT_Z_OFFSET);
            if (pt != null) {
                OverlayUtil.renderTextLocation(graphics, new Point(pt.getX() + 1, pt.getY() + 1), String.valueOf(ticks), Color.BLACK);
                OverlayUtil.renderTextLocation(graphics, pt, String.valueOf(ticks), color);
            }
        }
    }

    private int getShipCenterX(net.runelite.api.WorldView wv) {
        if (wv.getId() == cachedWorldViewId && cachedShipCenterX != -1) return cachedShipCenterX;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        for (net.runelite.api.Tile[][] plane : wv.getScene().getTiles()) {
            if (plane == null) continue;
            for (int x = 0; x < plane.length; x++) {
                if (plane[x] == null) continue;
                for (int y = 0; y < plane[x].length; y++) {
                    net.runelite.api.Tile tile = plane[x][y];
                    if (tile != null && (tile.getSceneTilePaint() != null || tile.getSceneTileModel() != null)) {
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    }
                }
            }
        }
        cachedShipCenterX = (minX != Integer.MAX_VALUE) ? ((minX + maxX) * 128) / 2 + 64 : 512;
        cachedWorldViewId = wv.getId();
        return cachedShipCenterX;
    }

    private Polygon modelToCanvasPoly(LocalPoint c, int a, float[] xs, float[] ys, net.runelite.api.WorldView wv) {
        float[] zs = {0,0,0,0}; int[] cxs = new int[4], cys = new int[4];
        Perspective.modelToCanvas(client, wv, 4, c.getX(), c.getY(), 0, a, xs, ys, zs, cxs, cys);
        for (int i=0; i<4; i++) if (cxs[i] != 0 || cys[i] != 0) return new Polygon(cxs, cys, 4);
        return null;
    }

    private void renderCannonTickOverhead(Graphics2D graphics, Player local) {
        String text = String.valueOf(plugin.getCannonTicksRemaining());
        graphics.setFont(FONT_TICK);
        Point pt = local.getCanvasTextLocation(graphics, text, local.getLogicalHeight() + TEXT_Z_OFFSET);
        if (pt == null) return;

        Color color = config.cannonCooldownLightMode() ? Color.WHITE : Color.BLACK;

        renderTextLocation(graphics, new Point(pt.getX() + 1, pt.getY() + 1), text, color, color);
        renderTextLocation(graphics, pt, text, config.cannonCooldownColor(), color);
    }

    public static void renderTextLocation(Graphics2D graphics, Point txtLoc, String text, Color color, Color backingColor)
    {
        if (Strings.isNullOrEmpty(text))
        {
            return;
        }

        int x = txtLoc.getX();
        int y = txtLoc.getY();

        if (color != backingColor) {
            graphics.setColor(backingColor);
            graphics.drawString(text, x + 1, y + 1);
        }

        graphics.setColor(ColorUtil.colorWithAlpha(color, 0xFF));
        graphics.drawString(text, x, y);
    }

    private void renderMonsterRangeBoundary(Graphics2D graphics, WorldArea area, int range, Color color) {
        for (int wx = area.getX() - range; wx <= area.getX() + area.getWidth() - 1 + range; wx++) {
            for (int wy = area.getY() - range; wy <= area.getY() + area.getHeight() - 1 + range; wy++) {
                WorldPoint tile = new WorldPoint(wx, wy, area.getPlane());
                if (area.distanceTo(tile) != range) continue;
                LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), tile);
                if (lp == null) continue;
                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if (poly != null) {
                    graphics.setColor(withAlpha(color, 40)); graphics.fillPolygon(poly);
                    graphics.setStroke(STROKE_THIN); graphics.setColor(color); graphics.drawPolygon(poly);
                }
            }
        }
    }

    private void renderCorpseTimers(Graphics2D graphics) {
        if (!config.showCorpseTimer()) return;
        for (TrackedCorpse corpse : plugin.getTrackedCorpses().values()) {
            NPC npc = corpse.getNpc();
            String text = config.corpseTimerInSeconds() ? corpse.getSecondsRemaining() + "s" : corpse.getTicksRemaining() + "t";
            graphics.setFont(FONT_MONSTER_TICK);
            Point pt = npc.getCanvasTextLocation(graphics, text, npc.getLogicalHeight() + TEXT_Z_OFFSET);
            if (pt != null) {
                OverlayUtil.renderTextLocation(graphics, new Point(pt.getX() + 1, pt.getY() + 1), text, Color.BLACK);
                OverlayUtil.renderTextLocation(graphics, pt, text, config.corpseTimerColor());
            }
        }
    }

    private static Color withAlpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a))); }
}