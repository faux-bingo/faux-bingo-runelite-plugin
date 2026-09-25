package com.fauxbingo.handlers;

import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.ScreenshotService;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Yama and The Whisperer drop loot away from their own tile, so LootManager finds nothing to scan
 * and never posts NpcLootReceived. ServerNpcLoot is the only event they produce. Ordinary NPCs fire
 * both, so these also pin down the pairing that stops a double post.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ServerNpcLootHandlerTest
{
	private static final int OATHPLATE_SHARDS_ID = 30765;
	private static final int SHARD_PRICE = 183_000;
	private static final int BONES_ID = 526;

	@Mock
	private ItemManager itemManager;
	@Mock
	private ScreenshotService screenshotService;
	@Mock
	private ScheduledExecutorService executor;
	@Mock
	private DropCorrelationService dropCorrelationService;
	@Mock
	private NPC npc;
	@Mock
	private NPCComposition npcComposition;
	@Mock
	private Client client;

	private LootEventHandler lootEventHandler;

	@Before
	public void before()
	{
		lootEventHandler = new LootEventHandler(itemManager, screenshotService, executor, dropCorrelationService, client);

		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(executor).execute(any());

		doAnswer(inv -> {
			java.util.function.Consumer<java.awt.image.BufferedImage> cb = inv.getArgument(0);
			cb.accept(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB));
			return null;
		}).when(screenshotService).requestScreenshot(any());

		ItemComposition shards = mock(ItemComposition.class);
		when(shards.getName()).thenReturn("Oathplate shards");
		when(itemManager.getItemComposition(OATHPLATE_SHARDS_ID)).thenReturn(shards);
		when(itemManager.getItemPrice(OATHPLATE_SHARDS_ID)).thenReturn(SHARD_PRICE);

		ItemComposition bones = mock(ItemComposition.class);
		when(bones.getName()).thenReturn("Bones");
		when(itemManager.getItemComposition(BONES_ID)).thenReturn(bones);
		when(itemManager.getItemPrice(BONES_ID)).thenReturn(100);
	}

	private ServerNpcLoot serverLoot(String name, List<ItemStack> items)
	{
		when(npcComposition.getName()).thenReturn(name);
		return new ServerNpcLoot(npcComposition, items);
	}

	private NpcLootReceived tileLoot(String name, List<ItemStack> items)
	{
		when(npc.getName()).thenReturn(name);
		return new NpcLootReceived(npc, items);
	}

	private static List<ItemStack> shards()
	{
		return Collections.singletonList(new ItemStack(OATHPLATE_SHARDS_ID, 12, null));
	}

	/** The reported bug: Yama produces no NpcLootReceived, so this is the only signal for the kill. */
	@Test
	public void yamaShardsFireOnServerEventAlone()
	{
		lootEventHandler.onServerNpcLoot(serverLoot("Yama", shards()));

		verify(dropCorrelationService).report(any());
	}

	@Test
	public void whispererLootFiresOnServerEventAlone()
	{
		lootEventHandler.onServerNpcLoot(serverLoot("The Whisperer", shards()));

		verify(dropCorrelationService).report(any());
	}

	/** Ordinary NPC: both events arrive for one kill and only one report comes out. */
	@Test
	public void bothEventsForOneKillReportOnce()
	{
		lootEventHandler.onServerNpcLoot(serverLoot("Vorkath", shards()));
		lootEventHandler.onNpcLootReceived(tileLoot("Vorkath", shards()));

		verify(dropCorrelationService, times(1)).report(any());
	}

	/** Order should not matter, the tile scan can land first. */
	@Test
	public void pairingWorksInEitherOrder()
	{
		lootEventHandler.onNpcLootReceived(tileLoot("Vorkath", shards()));
		lootEventHandler.onServerNpcLoot(serverLoot("Vorkath", shards()));

		verify(dropCorrelationService, times(1)).report(any());
	}

	/** Pairing consumes one entry, so identical repeat kills are not swallowed. */
	@Test
	public void twoIdenticalKillsBothReport()
	{
		List<ItemStack> loot = Collections.singletonList(new ItemStack(BONES_ID, 1, null));

		lootEventHandler.onServerNpcLoot(serverLoot("Goblin", loot));
		lootEventHandler.onNpcLootReceived(tileLoot("Goblin", loot));
		lootEventHandler.onServerNpcLoot(serverLoot("Goblin", loot));
		lootEventHandler.onNpcLootReceived(tileLoot("Goblin", loot));

		verify(dropCorrelationService, times(2)).report(any());
	}

	/** Interleaved, which is what a delayed tile scan looks like against a fast second kill. */
	@Test
	public void interleavedIdenticalKillsBothReport()
	{
		List<ItemStack> loot = Collections.singletonList(new ItemStack(BONES_ID, 1, null));

		lootEventHandler.onServerNpcLoot(serverLoot("Goblin", loot));
		lootEventHandler.onServerNpcLoot(serverLoot("Goblin", loot));
		lootEventHandler.onNpcLootReceived(tileLoot("Goblin", loot));
		lootEventHandler.onNpcLootReceived(tileLoot("Goblin", loot));

		verify(dropCorrelationService, times(2)).report(any());
	}

	/** Two of the same event in a row are never each other's pair. */
	@Test
	public void repeatedServerEventsAreNotPairedTogether()
	{
		lootEventHandler.onServerNpcLoot(serverLoot("Yama", shards()));
		lootEventHandler.onServerNpcLoot(serverLoot("Yama", shards()));

		verify(dropCorrelationService, times(2)).report(any());
	}

	/** Different loot from the same NPC is a different kill, not a pair. */
	@Test
	public void differentLootIsNotPaired()
	{
		lootEventHandler.onServerNpcLoot(serverLoot("Vorkath", shards()));
		lootEventHandler.onNpcLootReceived(tileLoot("Vorkath", Collections.singletonList(new ItemStack(BONES_ID, 1, null))));

		verify(dropCorrelationService, times(2)).report(any());
	}

	/** Item ordering differs between the two events, the key must not care. */
	@Test
	public void itemOrderDoesNotBreakPairing()
	{
		ItemStack shard = new ItemStack(OATHPLATE_SHARDS_ID, 12, null);
		ItemStack bone = new ItemStack(BONES_ID, 1, null);

		lootEventHandler.onServerNpcLoot(serverLoot("Yama", Arrays.asList(shard, bone)));
		lootEventHandler.onNpcLootReceived(tileLoot("Yama", Arrays.asList(bone, shard)));

		verify(dropCorrelationService, times(1)).report(any());
	}

	/**
	 * Mithril dragon: the three unnoted bars come through as separate 1x stacks from one event and
	 * a single 3x stack from the other. Still one kill, so still one report.
	 */
	@Test
	public void differentStackingOfNonStackablesStillPairs()
	{
		ItemStack bone = new ItemStack(BONES_ID, 1, null);

		lootEventHandler.onNpcLootReceived(tileLoot("Mithril dragon", Arrays.asList(
			bone,
			new ItemStack(OATHPLATE_SHARDS_ID, 1, null),
			new ItemStack(OATHPLATE_SHARDS_ID, 1, null),
			new ItemStack(OATHPLATE_SHARDS_ID, 1, null))));
		lootEventHandler.onServerNpcLoot(serverLoot("Mithril dragon", Arrays.asList(
			bone,
			new ItemStack(OATHPLATE_SHARDS_ID, 3, null))));

		verify(dropCorrelationService, times(1)).report(any());
	}

	/** Summing per item must not hide a real difference in how many dropped. */
	@Test
	public void differentTotalOfSameItemIsNotPaired()
	{
		lootEventHandler.onNpcLootReceived(tileLoot("Mithril dragon", Arrays.asList(
			new ItemStack(OATHPLATE_SHARDS_ID, 1, null),
			new ItemStack(OATHPLATE_SHARDS_ID, 1, null))));
		lootEventHandler.onServerNpcLoot(serverLoot("Mithril dragon",
			Collections.singletonList(new ItemStack(OATHPLATE_SHARDS_ID, 3, null))));

		verify(dropCorrelationService, times(2)).report(any());
	}

	@Test
	public void nullCompositionAndEmptyItemsAreSafe()
	{
		lootEventHandler.onServerNpcLoot(new ServerNpcLoot(null, shards()));
		lootEventHandler.onServerNpcLoot(serverLoot("Yama", Collections.emptyList()));

		verify(dropCorrelationService, times(1)).report(any());
	}
}
