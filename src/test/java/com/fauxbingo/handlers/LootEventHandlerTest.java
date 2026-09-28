package com.fauxbingo.handlers;

import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropSignal;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class LootEventHandlerTest
{
	@Mock
	private ItemManager itemManager;

	@Mock
	private ScreenshotService screenshotService;

	@Mock
	private ScheduledExecutorService executor;

	@Mock
	private DropCorrelationService dropCorrelationService;

	@Mock
	private Client client;

	@Mock
	private NPC npc;

	@Mock
	private Player player;

	@Mock
	private ItemComposition itemComposition;

	@Mock
	private Player localPlayer;

	private LootEventHandler lootEventHandler;

	@Before
	public void before()
	{
		lootEventHandler = new LootEventHandler(itemManager, screenshotService, executor, dropCorrelationService, client);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		when(localPlayer.getWorldLocation()).thenReturn(WorldPoint.fromRegion(9007, 12, 12, 0));

		// Run executor tasks inline
		doAnswer(invocation -> {
			Runnable r = invocation.getArgument(0);
			r.run();
			return null;
		}).when(executor).execute(any());

		// Immediately trigger the screenshot callback
		doAnswer(invocation -> {
			java.util.function.Consumer<java.awt.image.BufferedImage> cb = invocation.getArgument(0);
			cb.accept(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB));
			return null;
		}).when(screenshotService).requestScreenshot(any());

		when(itemManager.getItemComposition(anyInt())).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Dragon bones");
		when(itemManager.getItemPrice(anyInt())).thenReturn(2500L);
	}

	@Test
	public void testNpcLoot()
	{
		when(npc.getName()).thenReturn("Vorkath");
		when(npc.getId()).thenReturn(8061);
		when(npc.getCombatLevel()).thenReturn(732);
		ItemStack item = new ItemStack(536, 400, null); // 400 * 2500 = 1,000,000
		NpcLootReceived event = new NpcLootReceived(npc, Arrays.asList(item));

		lootEventHandler.onNpcLootReceived(event);

		ArgumentCaptor<DropSignal> captor = ArgumentCaptor.forClass(DropSignal.class);
		verify(dropCorrelationService).report(captor.capture());
		DropSignal signal = captor.getValue();
		org.junit.Assert.assertEquals(DetectionMethod.NPC_LOOT_RECEIVED, signal.getDetectionMethod());
		org.junit.Assert.assertEquals("Vorkath", signal.getSourceName());
		org.junit.Assert.assertEquals(Integer.valueOf(8061), signal.getNpcId());
		org.junit.Assert.assertEquals(Integer.valueOf(732), signal.getCombatLevel());
		org.junit.Assert.assertEquals(Integer.valueOf(9007), signal.getRegionId());
		org.junit.Assert.assertEquals(1_000_000L, signal.getTotalValueGe().longValue());
	}

	@Test
	public void testPlayerLoot()
	{
		when(player.getName()).thenReturn("PKedPlayer");
		ItemStack item = new ItemStack(536, 400, null);
		PlayerLootReceived event = new PlayerLootReceived(player, Arrays.asList(item));

		lootEventHandler.onPlayerLootReceived(event);

		ArgumentCaptor<DropSignal> captor = ArgumentCaptor.forClass(DropSignal.class);
		verify(dropCorrelationService).report(captor.capture());
		DropSignal signal = captor.getValue();
		org.junit.Assert.assertEquals(DetectionMethod.PLAYER_LOOT_RECEIVED, signal.getDetectionMethod());
		org.junit.Assert.assertEquals("PKedPlayer", signal.getSourceName());
	}

	@Test
	public void testMultipleItemsReportsOneSignal()
	{
		when(npc.getName()).thenReturn("Vorkath");
		ItemStack item1 = new ItemStack(536, 200, null); // 500,000
		ItemStack item2 = new ItemStack(537, 200, null); // 500,000
		NpcLootReceived event = new NpcLootReceived(npc, Arrays.asList(item1, item2));

		lootEventHandler.onNpcLootReceived(event);

		ArgumentCaptor<DropSignal> captor = ArgumentCaptor.forClass(DropSignal.class);
		verify(dropCorrelationService).report(captor.capture());
		org.junit.Assert.assertEquals(2, captor.getValue().getItems().size());
	}

	/** No local value gating here anymore, that decision moved to DropCorrelationService. */
	@Test
	public void testBelowThresholdStillReported()
	{
		when(npc.getName()).thenReturn("Vorkath");
		ItemStack item = new ItemStack(536, 1, null);
		NpcLootReceived event = new NpcLootReceived(npc, Arrays.asList(item));

		lootEventHandler.onNpcLootReceived(event);

		verify(dropCorrelationService).report(any());
	}

	/** Every stack in the drop reaches the signal, even repeated item names across separate stacks. */
	@Test
	public void testMultipleStacksOfSameItemAllReported()
	{
		when(npc.getName()).thenReturn("GLWZ");

		ItemComposition sharkComp1 = mock(ItemComposition.class);
		when(sharkComp1.getName()).thenReturn("Shark");
		ItemComposition sharkComp2 = mock(ItemComposition.class);
		when(sharkComp2.getName()).thenReturn("Shark");
		ItemComposition teleComp = mock(ItemComposition.class);
		when(teleComp.getName()).thenReturn("Teleport to house");

		when(itemManager.getItemComposition(100)).thenReturn(sharkComp1);
		when(itemManager.getItemComposition(101)).thenReturn(sharkComp2);
		when(itemManager.getItemComposition(200)).thenReturn(teleComp);

		when(itemManager.getItemPrice(anyInt())).thenReturn(1L);

		ItemStack shark1 = new ItemStack(100, 1, null);
		ItemStack shark2 = new ItemStack(101, 2, null);
		ItemStack tele = new ItemStack(200, 8, null);
		NpcLootReceived event = new NpcLootReceived(npc, Arrays.asList(shark1, shark2, tele));

		lootEventHandler.onNpcLootReceived(event);

		ArgumentCaptor<DropSignal> captor = ArgumentCaptor.forClass(DropSignal.class);
		verify(dropCorrelationService).report(captor.capture());
		org.junit.Assert.assertEquals(3, captor.getValue().getItems().size());
	}
}
