package com.fauxbingo.services;

import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import com.fauxbingo.services.data.MergedDropEvent;
import com.fauxbingo.services.data.SourceKind;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Reporting is synchronous (grouping happens on report()), but dispatch only happens once the
 * window closes. shutdown() forces an immediate flush of whatever is pending, standing in here
 * for "the correlation window elapsed" without needing to sleep in these tests.
 */
@RunWith(MockitoJUnitRunner.class)
public class DropCorrelationServiceTest
{
	@Mock
	private EventEnvelopeSink envelopeSink;

	@Mock
	private ScheduledExecutorService executor;

	private DropCorrelationService service;

	@Before
	public void before()
	{
		service = new DropCorrelationService(envelopeSink, executor);
	}

	private static DropSignal.DropSignalBuilder lootSignal(DetectionMethod method, String itemName, long value)
	{
		return DropSignal.builder()
			.detectionMethod(method)
			.items(Collections.singletonList(DropItem.builder().name(itemName).quantity(1).unitPriceGe(value).build()))
			.totalValueGe(value)
			.sourceName("Vorkath");
	}

	/** Mirrors ValuableDropHandler: chat-derived, so no itemId. */
	private static DropSignal chatDrop(String itemName, int quantity, long value)
	{
		return DropSignal.builder()
			.detectionMethod(DetectionMethod.CHAT_VALUABLE_DROP)
			.items(Collections.singletonList(
				DropItem.builder().name(itemName).quantity(quantity).unitPriceGe(value / quantity).build()))
			.totalValueGe(value)
			.build();
	}

	/** Mirrors RaidLootHandler: one EXACT signal carrying the chest's combined stacks. */
	private static DropSignal chestDrop(DropItem... items)
	{
		return DropSignal.builder()
			.detectionMethod(DetectionMethod.RAID_CHEST_CONTAINER)
			.sourceName("Theatre of Blood")
			.items(Arrays.asList(items))
			.totalValueGe(1_000_000L)
			.build();
	}

	private static DropItem chestItem(int id, String name, int quantity)
	{
		return DropItem.builder().id(id).name(name).quantity(quantity).unitPriceGe(1000L).build();
	}

	/** Mirrors LootEventHandler: one EXACT signal per kill, carrying the NPC it came from. */
	private static DropSignal npcKill(String name, int npcId, DropItem... items)
	{
		return DropSignal.builder()
			.detectionMethod(DetectionMethod.NPC_LOOT_RECEIVED)
			.sourceKind(SourceKind.NPC)
			.sourceName(name)
			.npcId(npcId)
			.items(Arrays.asList(items))
			.totalValueGe(1_000L)
			.build();
	}

	private static DropItem killItem(int id, String name, int quantity)
	{
		return DropItem.builder().id(id).name(name).quantity(quantity).unitPriceGe(1L).build();
	}

	private MergedDropEvent captureMergedEvent()
	{
		ArgumentCaptor<MergedDropEvent> captor = ArgumentCaptor.forClass(MergedDropEvent.class);
		verify(envelopeSink).accept(captor.capture());
		return captor.getValue();
	}

	@Test
	public void uncorroboratedSignalStillResolvesAlone()
	{
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Dragon bones", 2_000_000).build());

		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals(1, merged.getContributingSignals().size());
	}

	@Test
	public void sameItemFromTwoMethodsMergesIntoOneEnvelope()
	{
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Dragon bones", 500_000).build());
		service.report(lootSignal(DetectionMethod.CHAT_VALUABLE_DROP, "Dragon bones", 2_000_000).build());

		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals(2, merged.getContributingSignals().size());
	}

	@Test
	public void exactConfidenceWinsOverDerivedAsPrimary()
	{
		DropSignal exact = lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Twisted bow", 2_000_000).build();
		DropSignal derived = lootSignal(DetectionMethod.CHAT_VALUABLE_DROP, "Twisted bow", 2_000_000).build();

		// Report the DERIVED signal first to prove arrival order doesn't override confidence tier.
		service.report(derived);
		service.report(exact);
		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals(DetectionMethod.NPC_LOOT_RECEIVED, merged.getPrimarySignal().getDetectionMethod());
	}

	/** No local value gating here anymore, envelopeSink.accept must fire regardless of value. */
	@Test
	public void belowValueThresholdStillReachesSink()
	{
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Bones", 100).build());
		service.shutdown();

		verify(envelopeSink).accept(any());
	}

	@Test
	public void petAndCollectionLogMergeLearnsPetName()
	{
		DropSignal pet = DropSignal.builder()
			.detectionMethod(DetectionMethod.CHAT_PET)
			.sourceNameGuess("Vorkath")
			.build();
		DropSignal clog = DropSignal.builder()
			.detectionMethod(DetectionMethod.CHAT_COLLECTION_LOG)
			.items(Collections.singletonList(DropItem.builder().name("Vorki").quantity(1).build()))
			.build();

		service.report(pet);
		service.report(clog);
		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals("Vorki", merged.getPetName());
		assertEquals("Vorkath", merged.getSourceNameGuess());
	}

	@Test
	public void petArrivingAfterCollectionLogStillPairs()
	{
		DropSignal clog = DropSignal.builder()
			.detectionMethod(DetectionMethod.CHAT_COLLECTION_LOG)
			.items(Collections.singletonList(DropItem.builder().name("Baby mole").quantity(1).build()))
			.build();
		DropSignal pet = DropSignal.builder()
			.detectionMethod(DetectionMethod.CHAT_PET)
			.build();

		service.report(clog);
		service.report(pet);
		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals("Baby mole", merged.getPetName());
	}

	@Test
	public void unrelatedItemsResolveIntoSeparateGroups()
	{
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Dragon bones", 2_000_000).build());
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Shark", 2_000_000).build());
		service.shutdown();

		verify(envelopeSink, times(2)).accept(any());
	}

	@Test
	public void screenshotFromWinningSignalIsKept()
	{
		BufferedImage exactImage = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		BufferedImage derivedImage = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);

		service.report(lootSignal(DetectionMethod.CHAT_VALUABLE_DROP, "Fang", 2_000_000).screenshot(derivedImage).build());
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Fang", 2_000_000).screenshot(exactImage).build());
		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals(exactImage, merged.getScreenshot());
	}

	/** Theatre of Blood rolls its normal table three times and the chest combines the stacks. */
	@Test
	public void repeatedChatRollsFoldIntoTheChestThatCombinesThem()
	{
		service.report(chatDrop("Vial of blood", 50, 500_000));
		service.report(chatDrop("Vial of blood", 60, 600_000));
		service.report(chestDrop(chestItem(22446, "Vial of blood", 110)));

		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals(DetectionMethod.RAID_CHEST_CONTAINER, merged.getPrimarySignal().getDetectionMethod());
		assertEquals(110, merged.getPrimarySignal().getItems().get(0).getQuantity());
		assertEquals(3, merged.getContributingSignals().size());
	}

	/** The death rune line arriving first used to win the chest and strand the vial lines. */
	@Test
	public void chestAbsorbsEveryChatGroupItCoversNotJustTheFirst()
	{
		service.report(chatDrop("Death rune", 15, 300_000));
		service.report(chatDrop("Vial of blood", 50, 500_000));
		service.report(chatDrop("Vial of blood", 60, 600_000));
		service.report(chestDrop(
			chestItem(560, "Death rune", 15),
			chestItem(22446, "Vial of blood", 110)));

		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals(4, merged.getContributingSignals().size());
	}

	/** Two kills dropping the same item are two drops, with no chest to tie them together. */
	@Test
	public void repeatedChatRollsWithoutAChestStaySeparateEvents()
	{
		service.report(chatDrop("Rune scimitar", 1, 25_000));
		service.report(chatDrop("Rune scimitar", 1, 25_000));

		service.shutdown();

		verify(envelopeSink, times(2)).accept(any());
	}

	/** A chat line beyond what the chest held belongs to some other drop. */
	@Test
	public void chatRollsBeyondTheChestCountAreLeftOut()
	{
		service.report(chatDrop("Vial of blood", 50, 500_000));
		service.report(chatDrop("Vial of blood", 60, 600_000));
		service.report(chatDrop("Vial of blood", 30, 300_000));
		service.report(chestDrop(chestItem(22446, "Vial of blood", 110)));

		service.shutdown();

		ArgumentCaptor<MergedDropEvent> captor = ArgumentCaptor.forClass(MergedDropEvent.class);
		verify(envelopeSink, times(2)).accept(captor.capture());

		List<MergedDropEvent> events = captor.getAllValues();
		MergedDropEvent chestEvent = events.get(0).getContributingSignals().size() == 3 ? events.get(0) : events.get(1);
		MergedDropEvent leftover = chestEvent == events.get(0) ? events.get(1) : events.get(0);

		assertEquals(3, chestEvent.getContributingSignals().size());
		assertEquals(DetectionMethod.RAID_CHEST_CONTAINER, chestEvent.getPrimarySignal().getDetectionMethod());
		assertEquals(1, leftover.getContributingSignals().size());
		assertEquals(30, leftover.getPrimarySignal().getItems().get(0).getQuantity());
	}

	/**
	 * A cannon kills the same NPC repeatedly inside the window and every kill shares its common
	 * drop. Folding them into one group reported the first kill and discarded the rest.
	 */
	@Test
	public void rapidKillsOfTheSameNpcStaySeparateEvents()
	{
		service.report(npcKill("Ogre", 117, killItem(532, "Big bones", 1)));
		service.report(npcKill("Ogre", 117, killItem(532, "Big bones", 1)));
		service.report(npcKill("Ogre", 117, killItem(532, "Big bones", 1), killItem(11840, "Dragon boots", 1)));

		service.shutdown();

		ArgumentCaptor<MergedDropEvent> captor = ArgumentCaptor.forClass(MergedDropEvent.class);
		verify(envelopeSink, times(3)).accept(captor.capture());

		assertTrue("the rare from the third kill must survive", captor.getAllValues().stream()
			.anyMatch(e -> e.getPrimarySignal().getItems().stream()
				.anyMatch(i -> "Dragon boots".equals(i.getName()))));
	}

	/** Different NPCs sharing a common drop are still different kills. */
	@Test
	public void differentNpcsSharingACommonDropStaySeparate()
	{
		service.report(npcKill("Hill Giant", 2098, killItem(532, "Big bones", 1)));
		service.report(npcKill("Moss Giant", 2090, killItem(532, "Big bones", 1), killItem(1149, "Dragon med helm", 1)));

		service.shutdown();

		verify(envelopeSink, times(2)).accept(any());
	}

	/** A chat line the first kill already claimed must not pull a later kill into its group. */
	@Test
	public void exactSignalDoesNotClaimAGroupAnotherSourceAnchored()
	{
		service.report(chatDrop("Dragon boots", 1, 150_000));
		service.report(npcKill("Hill Giant", 2098, killItem(11840, "Dragon boots", 1)));
		service.report(npcKill("Moss Giant", 2090, killItem(11840, "Dragon boots", 1)));

		service.shutdown();

		ArgumentCaptor<MergedDropEvent> captor = ArgumentCaptor.forClass(MergedDropEvent.class);
		verify(envelopeSink, times(2)).accept(captor.capture());

		MergedDropEvent anchored = captor.getAllValues().stream()
			.filter(e -> e.getContributingSignals().size() == 2)
			.findFirst()
			.orElseThrow(() -> new AssertionError("chat line should have been claimed by one kill"));
		assertEquals("Hill Giant", anchored.getPrimarySignal().getSourceName());
	}

	/** Both DERIVED lines describe the one item, so they must not compete for its single unit. */
	@Test
	public void collectionLogAndValuableDropForOneUniqueStillShareAGroup()
	{
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Twisted bow", 1_000_000_000).build());
		service.report(DropSignal.builder()
			.detectionMethod(DetectionMethod.CHAT_COLLECTION_LOG)
			.items(Collections.singletonList(DropItem.builder().name("Twisted bow").quantity(1).build()))
			.build());
		service.report(lootSignal(DetectionMethod.CHAT_VALUABLE_DROP, "Twisted bow", 1_000_000_000).build());

		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals(3, merged.getContributingSignals().size());
	}
}
