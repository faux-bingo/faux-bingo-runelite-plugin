package com.fauxbingo.handlers;

import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import com.fauxbingo.services.data.SourceKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Handles loot-related events from NPCs, players, and non-combat sources. Builds one DropSignal
 * per detection and hands it to DropCorrelationService, which decides whether it gets merged
 * with corroborating signals from other handlers (valuable drop chat, collection log, pet)
 * before anything is sent to the API.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LootEventHandler
{
	/**
	 * How long a kill stays eligible to be paired with its counterpart event.
	 */
	private static final long PAIRING_WINDOW_MS = 5_000;

	private enum NpcLootSignal
	{
		TILE_SCAN,
		SERVER;

		NpcLootSignal other()
		{
			return this == TILE_SCAN ? SERVER : TILE_SCAN;
		}
	}

	/**
	 * Kills seen through one NPC loot event and still waiting for the other. This is the
	 * same-method dedup layer (NpcLootReceived vs ServerNpcLoot for one real kill) and stays
	 * separate from DropCorrelationService's cross-method merge.
	 */
	private final Deque<SeenKill> unpairedKills = new ArrayDeque<>();

	private static class SeenKill
	{
		private final String key;
		private final NpcLootSignal signal;
		private final long timestamp;

		SeenKill(String key, NpcLootSignal signal, long timestamp)
		{
			this.key = key;
			this.signal = signal;
			this.timestamp = timestamp;
		}
	}

	/** One deferred LOOT_TRACKER_EVENT, held until the tick's remaining container changes land. */
	private static class PendingEventLoot
	{
		private final String source;
		private final Collection<ItemStack> items;

		PendingEventLoot(String source, Collection<ItemStack> items)
		{
			this.source = source;
			this.items = items;
		}
	}

	private final ItemManager itemManager;
	private final ScreenshotService screenshotService;
	private final ScheduledExecutorService executor;
	private final DropCorrelationService dropCorrelationService;
	private final Client client;

	/** Item id to count for the equipment container, as of the last change to it. */
	private final Map<Integer, Integer> wornItems = new HashMap<>();

	/** False until wornItems has been populated at least once, so the first diff isn't against nothing. */
	private boolean wornKnown = false;

	/** Item id to count for everything that left the equipment container this tick. */
	private final Map<Integer, Integer> unequippedThisTick = new HashMap<>();

	private final List<PendingEventLoot> pendingEventLoot = new ArrayList<>();

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		Integer npcId = event.getNpc() != null ? event.getNpc().getId() : null;
		Integer combatLevel = event.getNpc() != null ? event.getNpc().getCombatLevel() : null;
		processNpcLoot(event.getNpc().getName(), event.getItems(), NpcLootSignal.TILE_SCAN, npcId, combatLevel);
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		processLoot(event.getPlayer().getName(), event.getItems(), SourceKind.PLAYER,
			DetectionMethod.PLAYER_LOOT_RECEIVED, null, null);
	}

	/**
	 * The server driven counterpart to NpcLootReceived. Bosses whose loot does not land on their own
	 * tile (Yama at his throne for example) have no case in LootManager's getDropLocations, 
	 * so this is the only event they ever produce.
	 */
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		NPCComposition npc = event.getComposition();
		Integer npcId = npc != null ? npc.getId() : null;
		Integer combatLevel = npc != null ? npc.getCombatLevel() : null;
		processNpcLoot(npc != null ? npc.getName() : null, event.getItems(), NpcLootSignal.SERVER, npcId, combatLevel);
	}

	/**
	 * Most kills fire both NPC loot events, so the first one through wins and the second is paired
	 * off against it. Pairing consumes a single entry rather than suppressing everything with a
	 * matching key, so back to back kills of the same NPC with identical loot still report once each.
	 */
	private void processNpcLoot(String source, Collection<ItemStack> items, NpcLootSignal signal, Integer npcId, Integer combatLevel)
	{
		if (items == null || items.isEmpty())
		{
			return;
		}

		String key = buildKillKey(source, items);
		long now = System.currentTimeMillis();
		dropStaleKills(now);

		for (Iterator<SeenKill> it = unpairedKills.iterator(); it.hasNext(); )
		{
			SeenKill seen = it.next();
			if (seen.signal == signal.other() && seen.key.equals(key))
			{
				it.remove();
				log.debug("Skipping {} loot for {}, already reported via {}", signal, source, seen.signal);
				return;
			}
		}

		unpairedKills.add(new SeenKill(key, signal, now));
		DetectionMethod method = signal == NpcLootSignal.TILE_SCAN
			? DetectionMethod.NPC_LOOT_RECEIVED
			: DetectionMethod.SERVER_NPC_LOOT;
		processLoot(source, items, SourceKind.NPC, method, npcId, combatLevel);
	}

	private void dropStaleKills(long now)
	{
		while (!unpairedKills.isEmpty() && now - unpairedKills.peek().timestamp > PAIRING_WINDOW_MS)
		{
			unpairedKills.poll();
		}
	}

	/** Source plus its loot, order independent so both events produce the same key. */
	private static String buildKillKey(String source, Collection<ItemStack> items)
	{
		List<String> stacks = new ArrayList<>(items.size());
		for (ItemStack item : items)
		{
			stacks.add(item.getId() + "x" + item.getQuantity());
		}
		stacks.sort(null);
		return source + "|" + String.join(",", stacks);
	}

	/**
	 * Non-combat loot (Tempoross reward pool, Wintertodt crates, clue caskets, chests, Guardians of
	 * the Rift, and friends) never produces an NpcLootReceived. It only surfaces as a LootReceived
	 * from the Loot Tracker plugin, so this is the only way we see any of it.
	 *
	 * Queued rather than reported here: LootTrackerPlugin derives these items by diffing the
	 * inventory, so anything else that entered the inventory on this tick is in the list too. The
	 * gear a player swaps off between kills is the common case - a Tarnished bracelet cleaned on
	 * the same tick as a weapon switch reported the weapon as loot. onGameTick subtracts it.
	 */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// NPC and player kills already reach us through LootManager
		if (event.getType() != LootRecordType.EVENT)
		{
			return;
		}

		Collection<ItemStack> items = event.getItems();
		if (items == null || items.isEmpty())
		{
			return;
		}

		pendingEventLoot.add(new PendingEventLoot(event.getName(), items));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.WORN)
		{
			return;
		}

		Map<Integer, Integer> current = countItems(event.getItemContainer());

		if (wornKnown)
		{
			for (Map.Entry<Integer, Integer> entry : wornItems.entrySet())
			{
				int removed = entry.getValue() - current.getOrDefault(entry.getKey(), 0);
				if (removed > 0)
				{
					unequippedThisTick.merge(entry.getKey(), removed, Integer::sum);
				}
			}
		}

		wornItems.clear();
		wornItems.putAll(current);
		wornKnown = true;
	}

	/**
	 * GameTick fires after all of the tick's packets have been processed, so by here both the
	 * inventory change that produced the loot and the equipment change that ran alongside it have
	 * been seen, whatever order the server sent them in.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Seeds wornItems when the plugin starts mid-session, so the player's first gear swap after
		// startup is diffed against real equipment rather than an empty map.
		if (!wornKnown)
		{
			ItemContainer worn = client.getItemContainer(InventoryID.WORN);
			if (worn != null)
			{
				wornItems.putAll(countItems(worn));
				wornKnown = true;
			}
		}

		for (PendingEventLoot pending : pendingEventLoot)
		{
			Collection<ItemStack> items = withoutUnequipped(pending.source, pending.items);
			if (!items.isEmpty())
			{
				processLoot(pending.source, items, SourceKind.OTHER, DetectionMethod.LOOT_TRACKER_EVENT, null, null);
			}
		}

		pendingEventLoot.clear();
		unequippedThisTick.clear();
	}

	/**
	 * Drops as much of each stack as the equipment container lost this tick. Subtracting the count
	 * rather than the whole stack keeps a genuine drop of an item the player also unequipped: come
	 * back from a crate with two Tomes of fire having taken one off, and one is still loot.
	 */
	private Collection<ItemStack> withoutUnequipped(String source, Collection<ItemStack> items)
	{
		if (unequippedThisTick.isEmpty())
		{
			return items;
		}

		Map<Integer, Integer> budget = new HashMap<>(unequippedThisTick);
		List<ItemStack> kept = new ArrayList<>(items.size());

		for (ItemStack item : items)
		{
			int available = budget.getOrDefault(item.getId(), 0);
			int subtract = Math.min(item.getQuantity(), available);

			if (subtract > 0)
			{
				budget.put(item.getId(), available - subtract);
				log.debug("Dropping {} x {} from {} loot, unequipped this tick", subtract, item.getId(), source);
			}

			int remaining = item.getQuantity() - subtract;
			if (remaining > 0)
			{
				kept.add(new ItemStack(item.getId(), remaining));
			}
		}

		return kept;
	}

	/** Empty slots come through as id -1 or quantity 0 and are left out. */
	private static Map<Integer, Integer> countItems(ItemContainer container)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		if (container == null)
		{
			return counts;
		}

		for (Item item : container.getItems())
		{
			if (item.getId() > 0 && item.getQuantity() > 0)
			{
				counts.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}

		return counts;
	}

	private void processLoot(String source, Collection<ItemStack> items, SourceKind sourceKind,
		DetectionMethod method, Integer npcId, Integer combatLevel)
	{
		long totalValue = 0;
		List<DropItem> dropItems = new ArrayList<>(items.size());

		for (ItemStack itemStack : items)
		{
			int itemId = itemStack.getId();
			int quantity = itemStack.getQuantity();
			int price = itemManager.getItemPrice(itemId);
			totalValue += (long) price * quantity;

			String itemName = itemManager.getItemComposition(itemId).getName();

			dropItems.add(DropItem.builder()
				.id(itemId)
				.name(itemName)
				.quantity(quantity)
				.unitPriceGe((long) price)
				.build());
		}

		long finalTotalValue = totalValue;

		// Read now, not from inside the async screenshot callback, so it reflects where the drop
		// actually happened rather than wherever the player has walked to by the time it fires.
		WorldPoint location = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
		Integer regionId = location != null ? location.getRegionID() : null;
		Integer plane = location != null ? location.getPlane() : null;

		// Captured immediately regardless of value, DropCorrelationService may still merge this
		// with a corroborating signal from another handler before either reaches the API.
		screenshotService.requestScreenshot(image -> executor.execute(() -> {
			DropSignal signal = DropSignal.builder()
				.detectionMethod(method)
				.sourceKind(sourceKind)
				.sourceName(source)
				.npcId(npcId)
				.combatLevel(combatLevel)
				.regionId(regionId)
				.plane(plane)
				.items(dropItems)
				.totalValueGe(finalTotalValue)
				.screenshot(image)
				.build();
			dropCorrelationService.report(signal);
		}));
	}
}
