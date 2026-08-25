package com.shipcombat;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
        name = "Ship Combat",
        description = "Sailing ship combat helper: cannon arc, overhead tick counter, and monster attack range indicators",
        tags = {"sailing", "ship", "combat", "cannon", "sea", "monster", "kraken", "timer", "range"}
)
public class ShipCombatPlugin extends Plugin
{
    static final int BOAT_WORLD_ENTITY_CONFIG_ID_SKIFF = 2;
    static final int BOAT_WORLD_ENTITY_CONFIG_ID_SLOOP = 3;
    private static final int CANNON_FIRE_GRAPHICS_ID = 3538;
    private static final int CANNON_ANIM_OPERATING = 13323;
    private static final int CANNON_ANIM_READY     = 13324;

    @Inject private Client client;
    @Inject private ShipCombatOverlay overlay;
    @Inject private ShipBoundariesOverlay boundariesOverlay;
    @Inject private OverlayManager overlayManager;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final Set<WorldEntity> activeBoats = new HashSet<>();
    private final List<GameObject> allTrackedCannons = new ArrayList<>();

    @Getter
    private final Map<NPC, TrackedMonster> trackedMonsters = new LinkedHashMap<>();

    @Getter
    private final Map<NPC, TrackedCorpse> trackedCorpses = new LinkedHashMap<>();

    @Getter
    private int cannonTicksRemaining = 0;

    @Getter
    private boolean playerAtCannon = false;

    @Getter
    private GameObject playerCannon = null;

    private boolean pendingCannonOperate = false;

    // -----------------------------------------------------------------------
    // Entry Point
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception
    {
        net.runelite.client.externalplugins.ExternalPluginManager.loadBuiltin(ShipCombatPlugin.class);
        net.runelite.client.RuneLite.main(args);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void startUp()
    {
        overlayManager.add(overlay);
        overlayManager.add(boundariesOverlay);
        log.info("Ship Combat started.");
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        overlayManager.remove(boundariesOverlay);
        resetState();
        log.info("Ship Combat stopped.");
    }

    private void resetState()
    {
        activeBoats.clear();
        allTrackedCannons.clear();
        trackedMonsters.clear();
        trackedCorpses.clear();
        cannonTicksRemaining = 0;
        playerAtCannon = false;
        playerCannon = null;
    }

    // -----------------------------------------------------------------------
    // Logic Helpers
    // -----------------------------------------------------------------------

    public WorldEntity getBoatWorldEntity()
    {
        Player local = client.getLocalPlayer();
        if (local == null) return null;

        WorldView playerWv = local.getWorldView();
        if (playerWv == client.getTopLevelWorldView()) return null;

        for (WorldEntity we : activeBoats)
        {
            if (we.getWorldView() == playerWv) return we;
        }
        return null;
    }

    public List<GameObject> getTrackedCannons()
    {
        Player local = client.getLocalPlayer();
        if (local == null) return new ArrayList<>();

        WorldView playerWv = local.getWorldView();
        List<GameObject> active = new ArrayList<>();

        for (GameObject obj : allTrackedCannons)
        {
            if (obj.getWorldView() == playerWv) active.add(obj);
        }
        return active;
    }

    private GameObject getCurrentPlayerCannon()
    {
        Player local = client.getLocalPlayer();
        if (local == null)
        {
            return null;
        }

        LocalPoint playerLp = local.getLocalLocation();

        for (GameObject cannon : getTrackedCannons())
        {
            LocalPoint cannonLp = cannon.getLocalLocation();

            int dx = playerLp.getSceneX() - cannonLp.getSceneX();
            int dy = playerLp.getSceneY() - cannonLp.getSceneY();

            boolean cardinallyAdjacent = (Math.abs(dx) == 1 && dy == 0) || (dx == 0 && Math.abs(dy) == 1);

            if (cardinallyAdjacent)
            {
                return cannon;
            }
        }

        return null;
    }

    public CannonType getActiveCannonType()
    {
        GameObject cannon = getCurrentPlayerCannon();

        if (cannon != null)
        {
            return CannonType.fromObjectId(cannon.getId());
        }

        return null;
    }

    private void clearPlayerCannonState()
    {
        playerAtCannon = false;
        playerCannon = null;
        pendingCannonOperate = false;
        cannonTicksRemaining = 0;
    }

    // -----------------------------------------------------------------------
    // Events
    // -----------------------------------------------------------------------

    @Subscribe
    @SuppressWarnings("unused")
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.HOPPING || event.getGameState() == GameState.LOGIN_SCREEN)
        {
            resetState();
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onWorldEntitySpawned(WorldEntitySpawned event)
    {
        WorldEntity we = event.getWorldEntity();
        if (we.getConfig().getId() == BOAT_WORLD_ENTITY_CONFIG_ID_SKIFF || we.getConfig().getId() == BOAT_WORLD_ENTITY_CONFIG_ID_SLOOP)
        {
            activeBoats.add(we);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onWorldEntityDespawned(WorldEntityDespawned event)
    {
        activeBoats.remove(event.getWorldEntity());
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        GameObject obj = event.getGameObject();
        if (CannonType.isCannonObject(obj.getId()))
        {
            allTrackedCannons.add(obj);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onGameObjectDespawned(GameObjectDespawned event)
    {
        GameObject obj = event.getGameObject();
        if (CannonType.isCannonObject(obj.getId()))
        {
            allTrackedCannons.remove(obj);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onNpcSpawned(NpcSpawned event)
    {
        NPC npc = event.getNpc();
        SeaMonster type = SeaMonster.fromNpcId(npc.getId());
        if (type != null)
        {
            trackedMonsters.put(npc, new TrackedMonster(type));
            return;
        }

        SeaMonster corpseType = SeaMonster.fromCorpseId(npc.getId());
        if (corpseType != null)
        {
            trackedCorpses.put(npc, new TrackedCorpse(npc, corpseType.getDisplayName()));
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onNpcDespawned(NpcDespawned event)
    {
        trackedMonsters.remove(event.getNpc());
        trackedCorpses.remove(event.getNpc());
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onGraphicsObjectCreated(GraphicsObjectCreated event)
    {
        GraphicsObject gfx = event.getGraphicsObject();

        if (gfx.getId() != CANNON_FIRE_GRAPHICS_ID)
        {
            return;
        }

        Player local = client.getLocalPlayer();
        if (local == null || getBoatWorldEntity() == null)
        {
            return;
        }

        // Determine which cannon the local player is currently operating
        GameObject localPlayerCannon = getCurrentPlayerCannon();

        if (localPlayerCannon == null)
        {
            return;
        }

        // Ignore graphics objects from another WorldView
        if (gfx.getWorldView() != localPlayerCannon.getWorldView())
        {
            return;
        }

        LocalPoint gfxLocation = gfx.getLocation();
        LocalPoint playerCannonLocation = localPlayerCannon.getLocalLocation();

        long playerDx = gfxLocation.getX() - playerCannonLocation.getX();
        long playerDy = gfxLocation.getY() - playerCannonLocation.getY();
        long playerDistanceSquared = playerDx * playerDx + playerDy * playerDy;

        /*
         * Find out whether another cannon is closer to this muzzle flash.
         *
         * If another cannon is closer (or equally close), assume that cannon
         * produced the graphics object and do NOT reset our timer.
         */
        for (GameObject cannon : getTrackedCannons())
        {
            if (cannon == localPlayerCannon)
            {
                continue;
            }

            if (cannon.getWorldView() != gfx.getWorldView())
            {
                continue;
            }

            LocalPoint cannonLocation = cannon.getLocalLocation();

            long dx = gfxLocation.getX() - cannonLocation.getX();
            long dy = gfxLocation.getY() - cannonLocation.getY();
            long distanceSquared = dx * dx + dy * dy;

            if (distanceSquared <= playerDistanceSquared)
            {
                // This muzzle flash belongs to another cannon
                return;
            }
        }

        // The player's cannon is the closest cannon to the muzzle flash.
        CannonType cannonType = CannonType.fromObjectId(localPlayerCannon.getId());
        int speed = cannonType != null ? cannonType.getAttackSpeedTicks() : 7;

        cannonTicksRemaining = speed + 1;
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onAnimationChanged(AnimationChanged event)
    {
        Actor actor = event.getActor();

        if (actor instanceof NPC)
        {
            NPC npc = (NPC) actor;
            TrackedMonster tracked = trackedMonsters.get(npc);

            if (tracked == null)
            {
                return;
            }

            int animId = npc.getAnimation();

            if (animId != -1 && tracked.getType().isAttackAnimation(animId))
            {
                tracked.setTicksUntilNextAttack(tracked.getType().getAttackSpeedTicks() + 1
                );
            }
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        String option = event.getMenuOption();

        boolean cannonOperate = "Operate".equalsIgnoreCase(option) || "Stop-operating".equalsIgnoreCase(option) || "Stop operating".equalsIgnoreCase(option);

        if (!cannonOperate)
        {
            /*
             * If we're still travelling to a cannon after clicking Operate
             * and perform another action instead, cancel the pending state.
             */
            if (pendingCannonOperate && !playerAtCannon)
            {
                pendingCannonOperate = false;
            }

            return;
        }

        /*
         * Only react if this menu action targeted one of our cannon objects.
         */
        if (!CannonType.isCannonObject(event.getId()))
        {
            return;
        }

        /*
         * If we're already manning a cannon, clicking the cannon again
         * means we're stopping operation.
         *
         * This must happen BEFORE the "start operating" code below,
         * otherwise the second click just sets the state true again.
         */
        if (playerAtCannon)
        {
            clearPlayerCannonState();
            return;
        }

        /*
         * We are not currently manning one, so this is a request
         * to start operating a cannon.
         */
        pendingCannonOperate = true;

        /*
         * If already standing in the correct cannon position, activate
         * immediately rather than waiting for the next game tick.
         */
        GameObject cannon = getCurrentPlayerCannon();

        if (cannon != null)
        {
            playerAtCannon = true;
            playerCannon = cannon;
            pendingCannonOperate = false;
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onGameTick(GameTick ignored)
    {
        if (cannonTicksRemaining > 0)
        {
            cannonTicksRemaining--;
        }

        GameObject adjacentCannon = getCurrentPlayerCannon();

        /*
         * Operate was clicked and we've reached the cannon.
         */
        if (pendingCannonOperate && adjacentCannon != null)
        {
            playerAtCannon = true;
            playerCannon = adjacentCannon;
            pendingCannonOperate = false;
        }

        /*
         * Maintain the active cannon state.
         */
        if (playerAtCannon)
        {
            if (adjacentCannon == null)
            {
                playerAtCannon = false;
                playerCannon = null;
                cannonTicksRemaining = 0;
            }
            else
            {
                playerCannon = adjacentCannon;
            }
        }

        for (TrackedMonster tracked : trackedMonsters.values())
        {
            if (tracked.getTicksUntilNextAttack() > 0)
            {
                tracked.setTicksUntilNextAttack(
                        tracked.getTicksUntilNextAttack() - 1
                );
            }
        }

        trackedCorpses.values().removeIf(corpse -> !corpse.tick());
    }

    @Provides
    @SuppressWarnings("unused")
    public ShipCombatConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ShipCombatConfig.class);
    }
}