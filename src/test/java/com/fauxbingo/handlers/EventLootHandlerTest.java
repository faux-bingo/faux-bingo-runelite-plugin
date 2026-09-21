package com.fauxbingo.handlers;

import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tempoross rewards come from the reward pool, not a kill, so they only ever arrive as a
 * LootReceived from the Loot Tracker. Without this path they are never reported at all.
 *
 * These events are derived from an inventory diff, so they are held until onGameTick and stripped
 * of anything the player unequipped on the same tick.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class EventLootHandlerTest
{
	private static final int SOAKED_PAGE_ID = 25578;
	private static final int SOAKED_PAGE_PRICE = 2815;
	private static final String TEMPOROSS_EVENT = "Reward pool (Tempoross)";

	private static final int RUBY_BRACELET_ID = 11085;
	private static final int HARMONISED_STAFF_ID = 24423;
	private static final int TOME_OF_FIRE_ID = 20714;
	private static final String TARNISHED_BRACELET_EVENT = "Tarnished bracelet";

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

	private LootEventHandler handler;

	@Before
	public void before()
	{
		handler = new LootEventHandler(itemManager, screenshotService, executor, dropCorrelationService, client);

		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(executor).execute(any());

		doAnswer(inv -> {
			java.util.function.Consumer<java.awt.image.BufferedImage> cb = inv.getArgument(0);
			cb.accept(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB));
			return null;
		}).when(screenshotService).requestScreenshot(any());

		stubItem(SOAKED_PAGE_ID, "Soaked page", SOAKED_PAGE_PRICE);
		stubItem(RUBY_BRACELET_ID, "Ruby bracelet", 1_000);
		stubItem(HARMONISED_STAFF_ID, "Harmonised nightmare staff", 500_000_000);
		stubItem(TOME_OF_FIRE_ID, "Tome of fire", 4_000_000);
	}

	private void stubItem(int id, String name, int price)
	{
		ItemComposition composition = mock(ItemComposition.class);
		when(composition.getName()).thenReturn(name);
		when(itemManager.getItemComposition(id)).thenReturn(composition);
		when(itemManager.getItemPrice(id)).thenReturn(price);
	}

	private LootReceived eventLoot(String source, ItemStack... items)
	{
		return new LootReceived(source, -1, LootRecordType.EVENT, Arrays.asList(items), 1, null);
	}

	/**
	 * The first call seeds the equipment snapshot the way the login container update does; later
	 * calls are diffed against it.
	 */
	private void wornChange(int... idQuantityPairs)
	{
		Item[] items = new Item[idQuantityPairs.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idQuantityPairs[i * 2], idQuantityPairs[i * 2 + 1]);
		}

		ItemContainer container = mock(ItemContainer.class);
		when(container.getItems()).thenReturn(items);
		handler.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, container));
	}

	private DropSignal reportedSignal()
	{
		ArgumentCaptor<DropSignal> captor = ArgumentCaptor.forClass(DropSignal.class);
		verify(dropCorrelationService).report(captor.capture());
		return captor.getValue();
	}

	private static List<Integer> itemIds(DropSignal signal)
	{
		return signal.getItems().stream().map(DropItem::getId).collect(Collectors.toList());
	}

	@Test
	public void temporossLootIsReported()
	{
		handler.onLootReceived(eventLoot(TEMPOROSS_EVENT, new ItemStack(SOAKED_PAGE_ID, 1)));
		handler.onGameTick(new GameTick());

		DropSignal signal = reportedSignal();
		org.junit.Assert.assertEquals(DetectionMethod.LOOT_TRACKER_EVENT, signal.getDetectionMethod());
		org.junit.Assert.assertEquals(TEMPOROSS_EVENT, signal.getSourceName());
	}

	/** Nothing reaches DropCorrelationService until the tick's container changes have all landed. */
	@Test
	public void eventLootIsHeldUntilTheTickEnds()
	{
		handler.onLootReceived(eventLoot(TEMPOROSS_EVENT, new ItemStack(SOAKED_PAGE_ID, 1)));

		verifyNoInteractions(dropCorrelationService);

		handler.onGameTick(new GameTick());

		verify(dropCorrelationService).report(any());
	}

	/**
	 * The Maggot King report that prompted this: a Tarnished bracelet cleaned on the same tick as a
	 * mage gear swap put the player's own staff and tome in the inventory diff.
	 */
	@Test
	public void gearUnequippedOnTheSameTickIsNotLoot()
	{
		wornChange(HARMONISED_STAFF_ID, 1, TOME_OF_FIRE_ID, 1);
		wornChange();

		handler.onLootReceived(eventLoot(TARNISHED_BRACELET_EVENT,
			new ItemStack(RUBY_BRACELET_ID, 1),
			new ItemStack(HARMONISED_STAFF_ID, 1),
			new ItemStack(TOME_OF_FIRE_ID, 1)));
		handler.onGameTick(new GameTick());

		DropSignal signal = reportedSignal();
		org.junit.Assert.assertEquals(Collections.singletonList(RUBY_BRACELET_ID), itemIds(signal));
		org.junit.Assert.assertEquals(1_000L, signal.getTotalValueGe().longValue());
	}

	/** With nothing but unequipped gear left there is no drop to report at all. */
	@Test
	public void eventLootOfOnlyUnequippedGearIsDropped()
	{
		wornChange(HARMONISED_STAFF_ID, 1);
		wornChange();

		handler.onLootReceived(eventLoot(TARNISHED_BRACELET_EVENT, new ItemStack(HARMONISED_STAFF_ID, 1)));
		handler.onGameTick(new GameTick());

		verifyNoInteractions(dropCorrelationService);
	}

	/** Only the unequipped count comes off the stack, so a real drop of the same item survives. */
	@Test
	public void onlyTheUnequippedQuantityIsSubtracted()
	{
		wornChange(TOME_OF_FIRE_ID, 1);
		wornChange();

		handler.onLootReceived(eventLoot(TEMPOROSS_EVENT, new ItemStack(TOME_OF_FIRE_ID, 3)));
		handler.onGameTick(new GameTick());

		DropSignal signal = reportedSignal();
		org.junit.Assert.assertEquals(1, signal.getItems().size());
		org.junit.Assert.assertEquals(2, signal.getItems().get(0).getQuantity());
	}

	/** An earlier tick's swap must not strip a later tick's loot. */
	@Test
	public void unequippedGearDoesNotCarryIntoTheNextTick()
	{
		wornChange(HARMONISED_STAFF_ID, 1);
		wornChange();
		handler.onGameTick(new GameTick());

		handler.onLootReceived(eventLoot(TEMPOROSS_EVENT, new ItemStack(HARMONISED_STAFF_ID, 1)));
		handler.onGameTick(new GameTick());

		DropSignal signal = reportedSignal();
		org.junit.Assert.assertEquals(Collections.singletonList(HARMONISED_STAFF_ID), itemIds(signal));
	}

	/** Equipping is the other half of a swap and adds nothing to the inventory diff. */
	@Test
	public void newlyEquippedGearDoesNotStripLoot()
	{
		wornChange();
		wornChange(HARMONISED_STAFF_ID, 1);

		handler.onLootReceived(eventLoot(TEMPOROSS_EVENT, new ItemStack(SOAKED_PAGE_ID, 1)));
		handler.onGameTick(new GameTick());

		DropSignal signal = reportedSignal();
		org.junit.Assert.assertEquals(Collections.singletonList(SOAKED_PAGE_ID), itemIds(signal));
	}

	/** Changes to any other container are irrelevant to what the player is wearing. */
	@Test
	public void nonEquipmentContainerChangesAreIgnored()
	{
		wornChange(HARMONISED_STAFF_ID, 1);

		ItemContainer inventory = mock(ItemContainer.class);
		when(inventory.getItems()).thenReturn(new Item[0]);
		handler.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inventory));

		handler.onLootReceived(eventLoot(TEMPOROSS_EVENT, new ItemStack(HARMONISED_STAFF_ID, 1)));
		handler.onGameTick(new GameTick());

		DropSignal signal = reportedSignal();
		org.junit.Assert.assertEquals(Collections.singletonList(HARMONISED_STAFF_ID), itemIds(signal));
	}

	/** NPC and player kills reach us via LootManager, taking them here too would double post. */
	@Test
	public void npcAndPlayerTypesAreIgnored()
	{
		handler.onLootReceived(new LootReceived("Vorkath", 732, LootRecordType.NPC,
			Collections.singletonList(new ItemStack(SOAKED_PAGE_ID, 1)), 1, null));
		handler.onLootReceived(new LootReceived("Zezima", 126, LootRecordType.PLAYER,
			Collections.singletonList(new ItemStack(SOAKED_PAGE_ID, 1)), 1, null));
		handler.onGameTick(new GameTick());

		verifyNoInteractions(dropCorrelationService);
	}

	/** Pickpocketing fires once per steal. Far too noisy to push at the API. */
	@Test
	public void pickpocketIsIgnored()
	{
		handler.onLootReceived(new LootReceived("Master Farmer", 70, LootRecordType.PICKPOCKET,
			Collections.singletonList(new ItemStack(SOAKED_PAGE_ID, 1)), 1, null));
		handler.onGameTick(new GameTick());

		verifyNoInteractions(dropCorrelationService);
	}

	@Test
	public void emptyAndNullItemsAreSafe()
	{
		handler.onLootReceived(new LootReceived(TEMPOROSS_EVENT, -1, LootRecordType.EVENT, Collections.emptyList(), 1, null));
		handler.onLootReceived(new LootReceived(TEMPOROSS_EVENT, -1, LootRecordType.EVENT, null, 1, null));
		handler.onGameTick(new GameTick());

		verifyNoInteractions(dropCorrelationService);
	}

	/** A tick with no pending loot must not report anything left over. */
	@Test
	public void gameTickWithoutPendingLootIsInert()
	{
		handler.onGameTick(new GameTick());
		handler.onGameTick(new GameTick());

		verifyNoInteractions(dropCorrelationService);
	}
}
