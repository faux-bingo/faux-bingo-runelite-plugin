package com.fauxbingo.services;

import com.fauxbingo.services.data.Confidence;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import com.fauxbingo.services.data.DropType;
import com.fauxbingo.services.data.MergedDropEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Merges the one signal each loot handler produces into a single authoritative/enriched event
 * per physical drop, so EventEnvelopeSink (EventsApiService) only ever sees one event for one
 * item.
 *
 * Two dedup layers exist on purpose. LootEventHandler's TILE_SCAN/SERVER pairing and
 * RaidLootHandler's own chat+chest assembly already solve same-method duplicates with timing
 * this service can't match (raid chat-to-chest latency can exceed the window below). This
 * service only handles the cross-method case: the same item reported via more than one
 * detection method.
 */
@Slf4j
@Singleton
public class DropCorrelationService
{
	private static final long CORRELATION_WINDOW_MS = 5_000;
	private static final long SWEEP_INTERVAL_MS = 500;
	private static final Pattern QUANTITY_PREFIX = Pattern.compile("^[0-9,]+\\s*x\\s+");

	private final EventEnvelopeSink envelopeSink;
	private final ScheduledExecutorService executor;

	private final Deque<PendingGroup> pendingGroups = new ArrayDeque<>();
	private ScheduledFuture<?> sweepTask;

	@Inject
	public DropCorrelationService(EventEnvelopeSink envelopeSink, ScheduledExecutorService executor)
	{
		this.envelopeSink = envelopeSink;
		this.executor = executor;
	}

	public void start()
	{
		if (sweepTask != null && !sweepTask.isCancelled())
		{
			return;
		}
		sweepTask = executor.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	public void shutdown()
	{
		if (sweepTask != null)
		{
			sweepTask.cancel(false);
			sweepTask = null;
		}
		flushAll();
	}

	/** Handlers call this instead of touching EventsApiService directly. */
	public synchronized void report(DropSignal signal)
	{
		if (signal == null)
		{
			return;
		}

		PendingGroup group = findMatchingGroup(signal);
		if (group == null)
		{
			group = new PendingGroup(System.currentTimeMillis() + CORRELATION_WINDOW_MS);
			pendingGroups.add(group);
		}
		group.signals.add(signal);
	}

	/**
	 * Item-name/id intersection is the normal match, gated on quantity: a raid rolls its normal
	 * reward table several times, so one chest of 110 Vials of blood arrives in chat as separate
	 * "50 x" and "60 x" valuable drops that all belong to it.
	 *
	 * PET signals carry no item identity at all (the game's pet message never names it), so they
	 * pair against the nearest unclaimed COLLECTION_LOG signal instead - the only way the plugin
	 * ever learns a pet's name. That pairing works regardless of which signal lands first.
	 */
	private PendingGroup findMatchingGroup(DropSignal signal)
	{
		boolean isPet = signal.getDetectionMethod().getType() == DropType.PET;
		boolean isCollectionLog = signal.getDetectionMethod().getType() == DropType.COLLECTION_LOG;

		if (isPet)
		{
			return findGroup(g -> g.hasCollectionLog() && !g.hasPet());
		}

		Map<String, Integer> quantities = itemQuantities(signal);
		if (!quantities.isEmpty())
		{
			PendingGroup byItem = signal.getDetectionMethod().getConfidence() == Confidence.EXACT
				? absorbMatchingGroups(signal, quantities)
				: findGroupWithRoom(signal.getDetectionMethod(), quantities);
			if (byItem != null)
			{
				return byItem;
			}
		}

		if (isCollectionLog)
		{
			return findGroup(g -> g.hasPet() && !g.hasCollectionLog());
		}

		return null;
	}

	/**
	 * A DERIVED signal needs a count to fit inside: the EXACT one when the group has it, otherwise
	 * what the group's other chat methods already claimed. So a collection-log line joins the
	 * valuable-drop line for the same unique ahead of the kill's loot event, instead of being left
	 * behind in its own group when that loot event absorbs the valuable drop. Two lines from one
	 * method are still two drops until something authoritative says otherwise.
	 */
	private PendingGroup findGroupWithRoom(DetectionMethod method, Map<String, Integer> quantities)
	{
		for (PendingGroup group : pendingGroups)
		{
			Set<String> groupKeys = group.itemKeys();
			boolean shared = false;
			boolean fits = true;

			for (Map.Entry<String, Integer> entry : quantities.entrySet())
			{
				if (!groupKeys.contains(entry.getKey()))
				{
					continue;
				}
				shared = true;

				int capacity = group.capacityFor(entry.getKey());
				if (capacity <= 0)
				{
					capacity = group.derivedDemand(entry.getKey());
				}
				if (capacity <= 0 || group.claimedFor(entry.getKey(), method) + entry.getValue() > capacity)
				{
					fits = false;
					break;
				}
			}

			if (shared && fits)
			{
				return group;
			}
		}
		return null;
	}

	/**
	 * Claims every group this signal's items cover, not just the first: a chest arrives after
	 * several chat lines that each became their own group. Stops folding groups in once they
	 * exhaust the count for an item, so an unrelated drop of it isn't swallowed too.
	 *
	 * Only a group still holding a DERIVED signal is eligible. Two EXACT signals are two separate
	 * drops and folding them together loses one: resolve() keeps a single primary, and
	 * EventsApiService builds the payload from that primary's items alone. Repeated kills of one
	 * NPC inside the window all share its common drop, which is why a cannon collapsed a whole
	 * window into its first kill.
	 */
	private PendingGroup absorbMatchingGroups(DropSignal signal, Map<String, Integer> quantities)
	{
		Map<String, Integer> remaining = new HashMap<>(quantities);
		List<PendingGroup> absorbed = new ArrayList<>();

		for (PendingGroup group : pendingGroups)
		{
			if (!group.hasDerived() || !group.acceptsExactFrom(signal))
			{
				continue;
			}

			Set<String> shared = new HashSet<>(group.itemKeys());
			shared.retainAll(remaining.keySet());
			if (shared.isEmpty())
			{
				continue;
			}

			boolean fits = true;
			for (String key : shared)
			{
				if (group.derivedDemand(key) > remaining.get(key))
				{
					fits = false;
					break;
				}
			}
			if (!fits)
			{
				continue;
			}

			for (String key : shared)
			{
				remaining.put(key, remaining.get(key) - group.derivedDemand(key));
			}
			absorbed.add(group);
		}

		if (absorbed.isEmpty())
		{
			return null;
		}

		PendingGroup host = absorbed.get(0);
		for (PendingGroup other : absorbed.subList(1, absorbed.size()))
		{
			host.signals.addAll(other.signals);
			pendingGroups.remove(other);
		}
		return host;
	}

	private PendingGroup findGroup(Predicate<PendingGroup> predicate)
	{
		for (PendingGroup group : pendingGroups)
		{
			if (predicate.test(group))
			{
				return group;
			}
		}
		return null;
	}

	/** Item key to the count this signal reports for it. */
	private static Map<String, Integer> itemQuantities(DropSignal signal)
	{
		Map<String, Integer> quantities = new HashMap<>();
		if (signal.getItems() == null)
		{
			return quantities;
		}
		for (DropItem item : signal.getItems())
		{
			int quantity = Math.max(item.getQuantity(), 0);
			if (item.getId() != null)
			{
				quantities.merge("id:" + item.getId(), quantity, Integer::sum);
			}
			String normalized = normalizeName(item.getName());
			if (normalized != null)
			{
				quantities.merge("name:" + normalized, quantity, Integer::sum);
			}
		}
		return quantities;
	}

	/**
	 * npcId when both signals carry one, source name otherwise. Either being absent counts as a
	 * match rather than blocking one: ServerNpcLoot can arrive with a null composition name, and
	 * chest and loot-tracker-event signals never carry an npcId.
	 */
	private static boolean sameSource(DropSignal a, DropSignal b)
	{
		if (a.getNpcId() != null && b.getNpcId() != null)
		{
			return a.getNpcId().equals(b.getNpcId());
		}

		String first = a.getSourceName();
		String second = b.getSourceName();
		return first == null || second == null || first.equalsIgnoreCase(second);
	}

	/** Strips a chat-embedded quantity prefix like "30 x " so names line up across handlers. */
	private static String normalizeName(String name)
	{
		if (name == null)
		{
			return null;
		}
		String stripped = QUANTITY_PREFIX.matcher(name.trim()).replaceFirst("");
		return stripped.toLowerCase(Locale.ROOT);
	}

	private synchronized void sweep()
	{
		long now = System.currentTimeMillis();
		Iterator<PendingGroup> it = pendingGroups.iterator();
		while (it.hasNext())
		{
			PendingGroup group = it.next();
			if (now >= group.deadline)
			{
				it.remove();
				resolveAndDispatch(group);
			}
		}
	}

	/** Called from shutdown so nothing lingers unresolved past a logout/plugin stop. */
	private synchronized void flushAll()
	{
		Iterator<PendingGroup> it = pendingGroups.iterator();
		while (it.hasNext())
		{
			PendingGroup group = it.next();
			it.remove();
			resolveAndDispatch(group);
		}
	}

	private void resolveAndDispatch(PendingGroup group)
	{
		if (group.signals.isEmpty())
		{
			return;
		}

		try
		{
			MergedDropEvent merged = resolve(group.signals, group.dropGroupId);
			dispatch(merged);
		}
		catch (Exception e)
		{
			log.error("Error resolving correlated drop", e);
		}
	}

	private MergedDropEvent resolve(List<DropSignal> signals, String dropGroupId)
	{
		DropSignal primary = pickPrimary(signals);

		DropSignal petSignal = findFirst(signals, s -> s.getDetectionMethod().getType() == DropType.PET);
		DropSignal clogSignal = findFirst(signals, s -> s.getDetectionMethod().getType() == DropType.COLLECTION_LOG);

		String petName = null;
		String sourceNameGuess = petSignal != null ? petSignal.getSourceNameGuess() : null;
		if (petSignal != null && clogSignal != null && clogSignal.getItems() != null && !clogSignal.getItems().isEmpty())
		{
			petName = clogSignal.getItems().get(0).getName();
		}

		// A capture can fail per signal, so fall back rather than let the primary's miss decide it.
		DropSignal withScreenshot = primary.getScreenshot() != null
			? primary
			: findFirst(signals, s -> s.getScreenshot() != null);

		Long corroboratedValue = null;
		if (primary.getTotalValueGe() == null || primary.getTotalValueGe() == 0)
		{
			for (DropSignal other : signals)
			{
				if (other != primary && other.getTotalValueGe() != null && other.getTotalValueGe() > 0)
				{
					corroboratedValue = other.getTotalValueGe();
					break;
				}
			}
		}

		return MergedDropEvent.builder()
			.type(primary.getDetectionMethod().getType())
			.dropGroupId(dropGroupId)
			.primarySignal(primary)
			.contributingSignals(new ArrayList<>(signals))
			.petName(petName)
			.sourceNameGuess(sourceNameGuess)
			.corroboratedValueGe(corroboratedValue)
			.screenshot(withScreenshot != null ? withScreenshot.getScreenshot() : null)
			.build();
	}

	private static DropSignal findFirst(List<DropSignal> signals, Predicate<DropSignal> predicate)
	{
		for (DropSignal signal : signals)
		{
			if (predicate.test(signal))
			{
				return signal;
			}
		}
		return null;
	}

	/** EXACT beats DERIVED; among ties, richer data (has an itemId) wins; final tiebreak is arrival order. */
	private static DropSignal pickPrimary(List<DropSignal> signals)
	{
		DropSignal best = null;
		int bestScore = Integer.MIN_VALUE;
		for (DropSignal signal : signals)
		{
			int score = score(signal);
			if (score > bestScore)
			{
				bestScore = score;
				best = signal;
			}
		}
		return best;
	}

	private static int score(DropSignal signal)
	{
		int score = 0;
		if (signal.getDetectionMethod().getConfidence() == Confidence.EXACT)
		{
			score += 10;
		}
		if (signal.getItems() != null && signal.getItems().stream().anyMatch(i -> i.getId() != null))
		{
			score += 1;
		}
		return score;
	}

	/** Only remaining consumer is the sink, everything reaches the API regardless of value. */
	private void dispatch(MergedDropEvent merged)
	{
		envelopeSink.accept(merged);
	}

	private static class PendingGroup
	{
		private final long deadline;
		private final String dropGroupId = UUID.randomUUID().toString();
		private final List<DropSignal> signals = new ArrayList<>();

		PendingGroup(long deadline)
		{
			this.deadline = deadline;
		}

		boolean hasPet()
		{
			return signals.stream().anyMatch(s -> s.getDetectionMethod().getType() == DropType.PET);
		}

		boolean hasCollectionLog()
		{
			return signals.stream().anyMatch(s -> s.getDetectionMethod().getType() == DropType.COLLECTION_LOG);
		}

		boolean hasDerived()
		{
			return signals.stream().anyMatch(s -> s.getDetectionMethod().getConfidence() != Confidence.EXACT);
		}

		/**
		 * A group anchored by one source's EXACT signal is closed to every other source. Without
		 * this, a chat line sitting between two kills gives them a shared item key, and the second
		 * kill absorbs the first kill's group through it.
		 */
		boolean acceptsExactFrom(DropSignal signal)
		{
			for (DropSignal existing : signals)
			{
				if (existing.getDetectionMethod().getConfidence() == Confidence.EXACT
					&& !sameSource(existing, signal))
				{
					return false;
				}
			}
			return true;
		}

		Set<String> itemKeys()
		{
			Set<String> keys = new HashSet<>();
			for (DropSignal signal : signals)
			{
				keys.addAll(itemQuantities(signal).keySet());
			}
			return keys;
		}

		/** The authoritative count DERIVED signals slot into. Zero if no EXACT signal covers the item. */
		int capacityFor(String key)
		{
			int capacity = 0;
			for (DropSignal signal : signals)
			{
				if (signal.getDetectionMethod().getConfidence() != Confidence.EXACT)
				{
					continue;
				}
				Integer quantity = itemQuantities(signal).get(key);
				if (quantity != null)
				{
					capacity = Math.max(capacity, quantity);
				}
			}
			return capacity;
		}

		/** How much of an item's capacity one detection method has already taken. */
		int claimedFor(String key, DetectionMethod method)
		{
			int claimed = 0;
			for (DropSignal signal : signals)
			{
				if (signal.getDetectionMethod() != method)
				{
					continue;
				}
				Integer quantity = itemQuantities(signal).get(key);
				if (quantity != null)
				{
					claimed += quantity;
				}
			}
			return claimed;
		}

		/**
		 * Methods are compared rather than summed: a collection-log line and a valuable-drop line
		 * describe the same item, while two valuable-drop lines are two separate rolls.
		 */
		int derivedDemand(String key)
		{
			Set<DetectionMethod> methods = EnumSet.noneOf(DetectionMethod.class);
			for (DropSignal signal : signals)
			{
				if (signal.getDetectionMethod().getConfidence() != Confidence.EXACT)
				{
					methods.add(signal.getDetectionMethod());
				}
			}

			int demand = 0;
			for (DetectionMethod method : methods)
			{
				demand = Math.max(demand, claimedFor(key, method));
			}
			return demand;
		}
	}
}
