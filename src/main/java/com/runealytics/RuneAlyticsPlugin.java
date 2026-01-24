package com.runealytics;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;

@Slf4j
@PluginDescriptor(
        name = "RuneAlytics",
        description = "Complete analytics and tracking for Old School RuneScape",
        tags = {"analytics", "tracking", "loot", "stats", "runealytics"}
)
public class RuneAlyticsPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private RunealyticsConfig config;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;
    @Inject private ConfigManager configManager;
    @Inject private ItemManager itemManager;
    @Inject private LootTrackerManager lootManager;
    @Inject private RuneAlyticsState state;

    @Getter
    private LootTrackerPanel lootPanel;

    private NavigationButton navButton;

    // Boss death tracking for ground loot attribution
    private NPC lastKilledBoss;
    private Instant lastKillTime;

    // ==================== LIFECYCLE ====================

    @Override
    protected void startUp()
    {
        log.info("RuneAlytics started");

        lootPanel = injector.getInstance(LootTrackerPanel.class);

        navButton = NavigationButton.builder()
                .tooltip("RuneAlytics - Loot Tracker")
                .icon(loadPluginIcon())
                .priority(5)
                .panel(lootPanel) // now valid
                .build();

        clientToolbar.addNavigation(navButton);
        lootManager.initialize();
    }

    @Override
    protected void shutDown()
    {
        clientToolbar.removeNavigation(navButton);

        lootManager.shutdown();
        lootPanel = null;
        navButton = null;
        lastKilledBoss = null;
        lastKillTime = null;

        log.info("RuneAlytics stopped");
    }

    @Provides
    RunealyticsConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(RunealyticsConfig.class);
    }

    // ==================== NPC LOOT ====================

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        NPC npc = event.getNpc();
        if (npc == null)
        {
            return;
        }

        Collection<ItemStack> items = event.getItems();
        if (items == null || items.isEmpty())
        {
            return;
        }

        if (!lootManager.isBoss(npc.getId(), npc.getName()))
        {
            return;
        }

        lootManager.processBossLoot(npc, items);
    }

    // ==================== BOSS DEATH ====================

    @Subscribe
    public void onActorDeath(ActorDeath event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        if (!(event.getActor() instanceof NPC))
        {
            return;
        }

        NPC npc = (NPC) event.getActor();

        if (lootManager.isBoss(npc.getId(), npc.getName()))
        {
            lastKilledBoss = npc;
            lastKillTime = Instant.now();

            log.debug("Boss killed: {}", npc.getName());
        }
    }

    // ==================== GROUND ITEM SPAWN ====================
    // Correct modern RuneLite event

    @Subscribe
    public void onItemSpawned(ItemSpawned event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        if (lastKilledBoss == null || lastKillTime == null)
        {
            return;
        }

        if (ChronoUnit.SECONDS.between(lastKillTime, Instant.now()) > 10)
        {
            return;
        }

        TileItem item = event.getItem();
        WorldPoint itemLoc = event.getTile().getWorldLocation();
        WorldPoint bossLoc = lastKilledBoss.getWorldLocation();

        if (itemLoc.distanceTo(bossLoc) <= 5)
        {
            log.debug(
                    "Ground loot near {}: itemId={} qty={}",
                    lastKilledBoss.getName(),
                    item.getId(),
                    item.getQuantity()
            );

            lootManager.processGroundItem(lastKilledBoss, item);
        }
    }

    // ==================== CHAT PARSING ====================

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!config.enableLootTracking())
        {
            return;
        }

        if (event.getType() != ChatMessageType.GAMEMESSAGE
                && event.getType() != ChatMessageType.SPAM)
        {
            return;
        }

        String msg = event.getMessage();

        if (msg.contains("kill count is:"))
        {
            lootManager.parseKillCountMessage(msg);
        }
    }

    // ==================== GAME STATE ====================

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (lastKillTime != null &&
                ChronoUnit.SECONDS.between(lastKillTime, Instant.now()) > 30)
        {
            lastKilledBoss = null;
            lastKillTime = null;
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        state.setLoggedIn(event.getGameState() == GameState.LOGGED_IN);
    }

    // ==================== AUTO SYNC ====================

    @Schedule(
            period = 60000,
            unit = ChronoUnit.MILLIS,
            asynchronous = true
    )
    public void syncDataScheduled()
    {
        if (!config.syncLootToServer())
        {
            return;
        }

        if (!state.isLoggedIn())
        {
            return;
        }

        lootManager.syncPendingLoot();
    }

    // ==================== HELPERS ====================

    private BufferedImage loadPluginIcon()
    {
        try
        {
            return ImageUtil.loadImageResource(getClass(), "/runealytics_icon.png");
        }
        catch (Exception e)
        {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            var g = img.createGraphics();
            g.setColor(new java.awt.Color(255, 215, 0));
            g.fillRect(0, 0, 16, 16);
            g.dispose();
            return img;
        }
    }

    public void refreshLootPanel()
    {
        if (lootPanel != null)
        {
            SwingUtilities.invokeLater(lootPanel::onDataRefresh);
        }
    }
}
