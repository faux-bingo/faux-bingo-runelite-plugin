package com.fauxbingo.handlers;

import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class RaidLootHandlerTest
{
	@Mock
	private Client client;

	@Mock
	private ScreenshotService screenshotService;

	@Mock
	private ScheduledExecutorService executor;

	@Mock
	private DropCorrelationService dropCorrelationService;

	@Mock
	private ItemManager itemManager;

	@Mock
	private Player player;

	@Mock
	private ItemComposition itemComposition;

	@Mock
	private ItemContainer itemContainer;

	private RaidLootHandler raidLootHandler;

	@Before
	public void before()
	{
		raidLootHandler = new RaidLootHandler(client, screenshotService, executor, itemManager, dropCorrelationService);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("TestPlayer");
		when(player.getWorldLocation()).thenReturn(WorldPoint.fromRegion(12889, 32, 32, 0));

		doAnswer(invocation -> {
			Runnable r = invocation.getArgument(0);
			r.run();
			return null;
		}).when(executor).execute(any());

		doAnswer(invocation -> {
			java.util.function.Consumer<java.awt.image.BufferedImage> cb = invocation.getArgument(0);
			cb.accept(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB));
			return null;
		}).when(screenshotService).requestScreenshot(any());
	}

	private DropSignal captureSignal()
	{
		ArgumentCaptor<DropSignal> captor = ArgumentCaptor.forClass(DropSignal.class);
		verify(dropCorrelationService).report(captor.capture());
		return captor.getValue();
	}

	@Test
	public void testCoxLootSequence()
	{
		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.onChatMessage(kcEvent);

		// 2. Unique Message
		ChatMessage uniqueEvent = new ChatMessage();
		uniqueEvent.setType(ChatMessageType.FRIENDSCHATNOTIFICATION);
		uniqueEvent.setMessage("TestPlayer - Twisted bow");
		raidLootHandler.onChatMessage(uniqueEvent);

		// 3. Container Changed
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(20997, 1)});
		when(itemManager.getItemComposition(20997)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Twisted bow");

		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(containerEvent);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals(DetectionMethod.RAID_CHEST_CONTAINER, signal.getDetectionMethod());
		org.junit.Assert.assertEquals(Integer.valueOf(100), signal.getKillCount());
		org.junit.Assert.assertEquals("COX", signal.getVariant());
		org.junit.Assert.assertEquals(Integer.valueOf(12889), signal.getRegionId());
		org.junit.Assert.assertEquals("Twisted bow", signal.getItems().get(0).getName());
		org.junit.Assert.assertEquals(1, signal.getItems().get(0).getQuantity());
	}

	@Test
	public void testTobLootSequence()
	{
		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Theatre of Blood count is: 50.");
		raidLootHandler.onChatMessage(kcEvent);

		// 2. Unique Message
		ChatMessage uniqueEvent = new ChatMessage();
		uniqueEvent.setType(ChatMessageType.GAMEMESSAGE);
		uniqueEvent.setMessage("TestPlayer found something special: Scythe of vitur (Uncharged)");
		raidLootHandler.onChatMessage(uniqueEvent);

		// 3. Container Changed
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(22477, 1)});
		when(itemManager.getItemComposition(22477)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Scythe of vitur (Uncharged)");

		ItemContainerChanged containerEvent = new ItemContainerChanged(612, itemContainer);
		raidLootHandler.onItemContainerChanged(containerEvent);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals("Scythe of vitur (Uncharged)", signal.getItems().get(0).getName());
	}

	@Test
	public void testNoUniqueAndNotValuableStillReported()
	{
		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.onChatMessage(kcEvent);

		// 2. Container Changed with cheap items
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(1234, 100)});
		when(itemManager.getItemComposition(1234)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Pure essence");
		when(itemManager.getItemPrice(1234)).thenReturn(2L);

		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(containerEvent);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals(200L, signal.getTotalValueGe().longValue());
	}

	@Test
	public void testConsolidatedNotification()
	{
		// Test multiple rare drops in one raid
		// 1. KC
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.onChatMessage(kcEvent);

		// 2. Unique 1
		ChatMessage u1 = new ChatMessage();
		u1.setType(ChatMessageType.FRIENDSCHATNOTIFICATION);
		u1.setMessage("TestPlayer - Twisted bow");
		raidLootHandler.onChatMessage(u1);

		// 3. Unique 2 (Dust)
		ChatMessage u2 = new ChatMessage();
		u2.setType(ChatMessageType.GAMEMESSAGE);
		u2.setMessage("Dust recipients: TestPlayer");
		raidLootHandler.onChatMessage(u2);

		// 4. Container
		when(itemContainer.getItems()).thenReturn(new Item[]{
			new Item(20997, 1), // T-bow
			new Item(1234, 100), // Soul runes
			new Item(5678, 1)    // Dust (assuming it's in container)
		});

		ItemComposition tbowComp = mock(ItemComposition.class);
		when(tbowComp.getName()).thenReturn("Twisted bow");
		when(itemManager.getItemComposition(20997)).thenReturn(tbowComp);

		ItemComposition soulComp = mock(ItemComposition.class);
		when(soulComp.getName()).thenReturn("Soul runes");
		when(itemManager.getItemComposition(1234)).thenReturn(soulComp);

		ItemComposition dustComp = mock(ItemComposition.class);
		when(dustComp.getName()).thenReturn("Metamorphic dust");
		when(itemManager.getItemComposition(5678)).thenReturn(dustComp);

		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(containerEvent);

		// Should report ONE signal with everything folded in
		DropSignal signal = captureSignal();
		List<String> itemNames = signal.getItems().stream().map(DropItem::getName).collect(Collectors.toList());
		org.junit.Assert.assertTrue(itemNames.contains("Twisted bow"));
		org.junit.Assert.assertTrue(itemNames.contains("Metamorphic dust"));
		org.junit.Assert.assertTrue(itemNames.contains("Soul runes"));
	}

	@Test
	public void testValuableLootNotification()
	{
		when(itemContainer.getItems()).thenReturn(new Item[]{
			new Item(1, 100), // Dragon arrow
			new Item(2, 1)    // Dexterous prayer scroll
		});

		ItemComposition arrowComp = mock(ItemComposition.class);
		when(arrowComp.getName()).thenReturn("Dragon arrow");
		when(itemManager.getItemComposition(1)).thenReturn(arrowComp);
		when(itemManager.getItemPrice(1)).thenReturn(2000L);

		ItemComposition scrollComp = mock(ItemComposition.class);
		when(scrollComp.getName()).thenReturn("Dexterous prayer scroll");
		when(itemManager.getItemComposition(2)).thenReturn(scrollComp);
		when(itemManager.getItemPrice(2)).thenReturn(1200000L);

		// KC Message to set context
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.onChatMessage(kcEvent);

		// Container Changed event
		ItemContainerChanged event = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals(1_400_000L, signal.getTotalValueGe().longValue());
	}

	/** A teammate's unique chat line must not make the local container loot look richer than it is. */
	@Test
	public void testToaTeammateUniqueIgnored()
	{
		// ToA unique drop message for teammate
		ChatMessage uniqueEvent = new ChatMessage();
		uniqueEvent.setType(ChatMessageType.GAMEMESSAGE);
		uniqueEvent.setMessage("Loot recipient: Teammate - Tumeken's shadow (uncharged)");
		raidLootHandler.onChatMessage(uniqueEvent);

		// Container change for ToA
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(1, 1)}); // Just some loot
		when(itemManager.getItemComposition(1)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Coins");
		when(itemManager.getItemPrice(1)).thenReturn(1L);

		ItemContainerChanged event = new ItemContainerChanged(801, itemContainer);
		raidLootHandler.onItemContainerChanged(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals(1, signal.getItems().size());
		org.junit.Assert.assertEquals("Coins", signal.getItems().get(0).getName());
	}

	@Test
	public void testMissingKcMessageStillProcesses()
	{
		// NO KC message received

		// Container Changed event
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(20997, 1)});
		when(itemManager.getItemComposition(20997)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Twisted bow");

		// Set it as a rare drop via chat anyway (unlikely if KC missed but possible)
		ChatMessage uniqueEvent = new ChatMessage();
		uniqueEvent.setType(ChatMessageType.FRIENDSCHATNOTIFICATION);
		uniqueEvent.setMessage("TestPlayer - Twisted bow");
		raidLootHandler.onChatMessage(uniqueEvent);

		ItemContainerChanged event = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(event);

		// Should still process and use default raid name
		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals("Chambers of Xeric", signal.getSourceName());
		org.junit.Assert.assertEquals("Twisted bow", signal.getItems().get(0).getName());
	}

	/**
	 * Regression test for the double-report bug: the reward interface reloading for the *same*
	 * chest (e.g. the player finally interacting with it long after the loot screen first
	 * populated) must not re-arm the latch. Only a real instance boundary may do that.
	 */
	@Test
	public void testWidgetReloadWithoutInstanceChangeDoesNotDoubleReport()
	{
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.onChatMessage(kcEvent);

		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(20997, 1)});
		when(itemManager.getItemComposition(20997)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Twisted bow");

		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(containerEvent);

		// Player is still standing in the same instance, but the reward interface reloads (e.g.
		// they finally click through it). isInInstancedRegion() hasn't changed, so this must be a
		// no-op for the latch.
		when(client.isInInstancedRegion()).thenReturn(false);
		GameStateChanged reload = new GameStateChanged();
		reload.setGameState(GameState.LOADING);
		raidLootHandler.onGameStateChanged(reload);

		// The same, unchanged container reports again.
		raidLootHandler.onItemContainerChanged(containerEvent);

		verify(dropCorrelationService, times(1)).report(any());
	}

	/** A genuine instance boundary must still re-arm the latch for the next raid's loot. */
	@Test
	public void testNewRaidAfterLeavingInstanceIsReportedAgain()
	{
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(20997, 1)});
		when(itemManager.getItemComposition(20997)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Twisted bow");

		// Enter the raid instance.
		when(client.isInInstancedRegion()).thenReturn(true);
		GameStateChanged enter = new GameStateChanged();
		enter.setGameState(GameState.LOADING);
		raidLootHandler.onGameStateChanged(enter);

		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(containerEvent);

		// Leave the instance after collecting loot.
		when(client.isInInstancedRegion()).thenReturn(false);
		GameStateChanged leave = new GameStateChanged();
		leave.setGameState(GameState.LOADING);
		raidLootHandler.onGameStateChanged(leave);

		// Enter a second raid instance and get a fresh chest.
		when(client.isInInstancedRegion()).thenReturn(true);
		GameStateChanged enterAgain = new GameStateChanged();
		enterAgain.setGameState(GameState.LOADING);
		raidLootHandler.onGameStateChanged(enterAgain);

		raidLootHandler.onItemContainerChanged(containerEvent);

		verify(dropCorrelationService, times(2)).report(any());
	}

	private void tickWithInRaidVarbit(int value)
	{
		when(client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON)).thenReturn(value);
		raidLootHandler.onGameTick(new GameTick());
	}

	/** Chat lands when Olm dies, the chest can be opened much later: hold until they leave. */
	@Test
	public void testCorrelationHeldForAsLongAsThePlayerIsInChambers()
	{
		tickWithInRaidVarbit(1);
		tickWithInRaidVarbit(1);
		verify(dropCorrelationService).holdNewGroups();
		verify(dropCorrelationService, never()).releaseHeldGroups();

		tickWithInRaidVarbit(0);
		verify(dropCorrelationService).releaseHeldGroups();
	}

	@Test
	public void testLoggingOutOfChambersReleasesTheHold()
	{
		tickWithInRaidVarbit(1);

		GameStateChanged logout = new GameStateChanged();
		logout.setGameState(GameState.LOGIN_SCREEN);
		raidLootHandler.onGameStateChanged(logout);

		verify(dropCorrelationService).releaseHeldGroups();
	}

	/** Ticks outside the raid leave the hold alone. */
	@Test
	public void testTicksOutsideChambersDoNotTouchTheHold()
	{
		tickWithInRaidVarbit(0);
		tickWithInRaidVarbit(0);

		verify(dropCorrelationService, never()).holdNewGroups();
		verify(dropCorrelationService, never()).releaseHeldGroups();
	}
}
