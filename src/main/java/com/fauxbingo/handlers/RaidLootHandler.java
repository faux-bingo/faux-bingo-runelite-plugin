package com.fauxbingo.handlers;

import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import com.fauxbingo.services.data.SourceKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Handles raid loot notifications for Chambers of Xeric and Theatre of Blood.
 * Tracks raid completions and unique drops from both raids, and assembles them into one
 * DropSignal per chest - the chat rare/KC assembly happens here because its own timing (chat
 * line to chest open can be many seconds) doesn't fit DropCorrelationService's shorter
 * cross-handler window.
 *
 * The `raidProcessed` latch used to re-arm itself after 60s of inactivity, on the theory that a
 * widget reload after a long gap meant a new attempt. It doesn't: the reward interface can
 * reload for the *same* chest while the player is still standing in front of it (e.g. only
 * getting round to opening it long after the loot screen first populated), which re-armed the
 * latch and let the next ItemContainerChanged for that same, unchanged container report the
 * identical loot a second time. The bundled LootTrackerPlugin's `chestLooted` latch never has
 * this problem because it only resets on a real boundary - entering or leaving an instanced
 * region - so that's what this mirrors instead of a timer.
 *
 * Chambers of Xeric announces its loot in chat when Olm dies, but the chest can be opened any
 * amount of time later, so DropCorrelationService is told to hold chat-only groups open for as
 * long as the player is inside the raid. Nothing else that produces loot can happen before they
 * leave it. The varbit is read every tick rather than trusted to report its own change, so a
 * plugin started mid-raid, or a missed change, can't leave the hold in the wrong state.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RaidLootHandler
{
	private static final Pattern COX_UNIQUE_MESSAGE_PATTERN = Pattern.compile("(.+) - (.+)");
	private static final String COX_DUST_MESSAGE_TEXT = "Dust recipients: ";
	private static final String COX_KIT_MESSAGE_TEXT = "Twisted Kit recipients: ";
	private static final Pattern TOB_UNIQUE_MESSAGE_PATTERN = Pattern.compile("(.+) found something special: (.+)");
	private static final Pattern TOA_UNIQUE_MESSAGE_PATTERN = Pattern.compile("Loot recipient: (.+) - (.+)");
	private static final Pattern KC_MESSAGE_PATTERN = Pattern.compile("([0-9]+)");

	private static final int CoX_Interface_Id = InterfaceID.RAIDS_REWARDS;
	private static final int ToB_Interface_Id = InterfaceID.TOB_CHESTS;
	private static final int ToA_Interface_Id = 775; // InterfaceID.TOA_REWARD_CHEST might not be available in all versions

	private static final int CoX_Container_Id = 581;
	private static final int ToB_Container_Id = 612;
	private static final int ToA_Container_Id = 801;

	enum RaidType
	{
		COX,
		COX_CM,
		TOB,
		TOB_SM,
		TOB_HM,
		TOA_ENTRY,
		TOA_NORMAL,
		TOA_EXPERT
	}

	private final Client client;
	private final ScreenshotService screenshotService;
	private final ScheduledExecutorService executor;
	private final ItemManager itemManager;
	private final DropCorrelationService dropCorrelationService;

	private RaidType raidType;
	private Integer raidKc;
	private final List<String> rareDrops = new ArrayList<>();
	private boolean raidProcessed = false;
	private boolean inInstancedRegion = false;
	private boolean inChambers = false;

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM
			&& event.getType() != ChatMessageType.TRADE
			&& event.getType() != ChatMessageType.FRIENDSCHATNOTIFICATION)
		{
			return;
		}

		handleRaidChatMessage(event.getMessage());
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		setInChambers(client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1);
	}

	private void setInChambers(boolean nowInChambers)
	{
		if (nowInChambers == inChambers)
		{
			return;
		}
		inChambers = nowInChambers;
		if (nowInChambers)
		{
			dropCorrelationService.holdNewGroups();
		}
		else
		{
			dropCorrelationService.releaseHeldGroups();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Logging out ends the raid without the varbit necessarily reporting it.
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			setInChambers(false);
		}

		// Mirrors LootTrackerPlugin's `chestLooted` reset: the latch only re-arms on a genuine
		// boundary (entering or leaving an instance), never on elapsed time, so a chest the player
		// is still standing in front of can't be reported twice just because they took a while to
		// open it.
		boolean nowInInstancedRegion = client.isInInstancedRegion();
		if (event.getGameState() == GameState.LOADING && nowInInstancedRegion != inInstancedRegion)
		{
			inInstancedRegion = nowInInstancedRegion;
			raidProcessed = false;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();
		if (containerId == CoX_Container_Id || containerId == ToB_Container_Id || containerId == ToA_Container_Id)
		{
			handleRaidInventory(containerId, event.getItemContainer());
		}
	}

	private void handleRaidChatMessage(String chatMessage)
	{
		String cleanMessage = Text.removeTags(chatMessage);

		// Check for completion messages - these mark the start of a new raid loot sequence
		if (cleanMessage.startsWith("Your completed Chambers of Xeric count is:") ||
			cleanMessage.startsWith("Your completed Theatre of Blood") ||
			(cleanMessage.contains("Tombs of Amascut") && cleanMessage.contains("completion count is")))
		{
			// Reset state for new raid loot
			resetState();
		}

		// Check for COX completion
		if (cleanMessage.startsWith("Your completed Chambers of Xeric count is:"))
		{
			Matcher matcher = KC_MESSAGE_PATTERN.matcher(cleanMessage);
			if (matcher.find())
			{
				raidType = cleanMessage.contains("Challenge Mode") ? RaidType.COX_CM : RaidType.COX;
				raidKc = Integer.valueOf(matcher.group());
				return;
			}
		}

		// Check for TOB completion
		if (cleanMessage.startsWith("Your completed Theatre of Blood"))
		{
			Matcher matcher = KC_MESSAGE_PATTERN.matcher(cleanMessage);
			if (matcher.find())
			{
				raidType = cleanMessage.contains("Hard Mode") ? RaidType.TOB_HM :
					(cleanMessage.contains("Story Mode") ? RaidType.TOB_SM : RaidType.TOB);
				raidKc = Integer.valueOf(matcher.group());
				return;
			}
		}

		// Check for TOA completion
		if (cleanMessage.contains("Tombs of Amascut") && cleanMessage.contains("completion count is"))
		{
			Matcher matcher = KC_MESSAGE_PATTERN.matcher(cleanMessage);
			if (matcher.find())
			{
				if (cleanMessage.contains("Expert Mode"))
				{
					raidType = RaidType.TOA_EXPERT;
				}
				else if (cleanMessage.contains("Entry Mode"))
				{
					raidType = RaidType.TOA_ENTRY;
				}
				else
				{
					raidType = RaidType.TOA_NORMAL;
				}
				raidKc = Integer.valueOf(matcher.group());
				return;
			}
		}

		// Check for TOA unique drops
		Matcher toaUnique = TOA_UNIQUE_MESSAGE_PATTERN.matcher(chatMessage);
		if (toaUnique.matches())
		{
			final String lootRecipient = Text.sanitize(toaUnique.group(1)).trim();
			if (lootRecipient.equalsIgnoreCase(getLocalPlayerName()))
			{
				rareDrops.add(toaUnique.group(2).trim());
			}
			return;
		}

		// Check for COX unique drops
		Matcher coxUnique = COX_UNIQUE_MESSAGE_PATTERN.matcher(chatMessage);
		if (coxUnique.matches())
		{
			final String lootRecipient = Text.sanitize(coxUnique.group(1)).trim();
			if (lootRecipient.equalsIgnoreCase(getLocalPlayerName()))
			{
				rareDrops.add(coxUnique.group(2).trim());
			}
			return;
		}

		// Check for COX metamorphic dust
		if (cleanMessage.startsWith(COX_DUST_MESSAGE_TEXT))
		{
			if (cleanMessage.toLowerCase().contains(getLocalPlayerName().toLowerCase()))
			{
				rareDrops.add("Metamorphic dust");
			}
			return;
		}

		// Check for COX twisted kit
		if (cleanMessage.startsWith(COX_KIT_MESSAGE_TEXT))
		{
			if (cleanMessage.toLowerCase().contains(getLocalPlayerName().toLowerCase()))
			{
				rareDrops.add("Twisted ancestral colour kit");
			}
			return;
		}

		// Check for TOB unique drops
		Matcher tobUnique = TOB_UNIQUE_MESSAGE_PATTERN.matcher(chatMessage);
		if (tobUnique.matches())
		{
			final String lootRecipient = Text.sanitize(tobUnique.group(1)).trim();
			if (lootRecipient.equalsIgnoreCase(getLocalPlayerName()))
			{
				rareDrops.add(tobUnique.group(2).trim());
			}
			return;
		}
	}

	private void handleRaidInventory(int containerId, ItemContainer itemContainer)
	{
		if (itemContainer == null || raidProcessed)
		{
			return;
		}

		String raidName = getRaidName(containerId);
		if (raidName == null)
		{
			return;
		}

		// Only process if container has items
		Item[] items = itemContainer.getItems();
		if (items == null || items.length == 0 || Arrays.stream(items).allMatch(i -> i.getId() == -1))
		{
			return;
		}

		try
		{
			processRaidLoot(raidName, itemContainer);
		}
		finally
		{
			// Clear per-raid rare drop state after loot is processed to avoid replaying on later chests.
			rareDrops.clear();
		}
		raidProcessed = true;
	}

	private void processRaidLoot(String raidName, ItemContainer itemContainer)
	{
		long totalValue = 0;
		List<DropItem> allItems = new ArrayList<>();

		for (Item item : itemContainer.getItems())
		{
			int itemId = item.getId();
			if (itemId != -1)
			{
				String itemName = itemManager.getItemComposition(itemId).getName();
				int quantity = item.getQuantity();
				int price = itemManager.getItemPrice(itemId);
				totalValue += (long) price * quantity;

				allItems.add(DropItem.builder()
					.id(itemId)
					.name(itemName)
					.quantity(quantity)
					.unitPriceGe((long) price)
					.build());
			}
		}

		reportRaidLoot(raidName, allItems, totalValue);
	}

	private void reportRaidLoot(String raidName, List<DropItem> allItems, long totalValue)
	{
		Integer kc = raidKc;
		String variant = raidType != null ? raidType.name() : null;

		// Read now rather than from inside the async screenshot callback below, same reasoning
		// as LootEventHandler: reflects the raid room, not wherever the player walks to next.
		WorldPoint location = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
		Integer regionId = location != null ? location.getRegionID() : null;
		Integer plane = location != null ? location.getPlane() : null;

		screenshotService.requestScreenshot(image -> executor.execute(() -> {
			DropSignal signal = DropSignal.builder()
				.detectionMethod(DetectionMethod.RAID_CHEST_CONTAINER)
				.sourceKind(SourceKind.RAID)
				.sourceName(raidName)
				.killCount(kc)
				.variant(variant)
				.regionId(regionId)
				.plane(plane)
				.items(allItems)
				.totalValueGe(totalValue)
				.screenshot(image)
				.build();
			dropCorrelationService.report(signal);
		}));
	}

	private String getRaidName(int id)
	{
		if (id == CoX_Interface_Id || id == CoX_Container_Id)
		{
			if (raidType == RaidType.COX_CM)
			{
				return "Chambers of Xeric Challenge Mode";
			}
			return "Chambers of Xeric";
		}
		else if (id == ToB_Interface_Id || id == ToB_Container_Id)
		{
			if (raidType == RaidType.TOB_SM)
			{
				return "Theatre of Blood Story Mode";
			}
			else if (raidType == RaidType.TOB_HM)
			{
				return "Theatre of Blood Hard Mode";
			}
			return "Theatre of Blood";
		}
		else if (id == ToA_Interface_Id || id == ToA_Container_Id)
		{
			if (raidType == RaidType.TOA_EXPERT)
			{
				return "Tombs of Amascut Expert Mode";
			}
			else if (raidType == RaidType.TOA_ENTRY)
			{
				return "Tombs of Amascut Entry Mode";
			}
			return "Tombs of Amascut";
		}
		return null;
	}

	public void resetState()
	{
		raidType = null;
		raidKc = null;
		rareDrops.clear();
	}

	private String getLocalPlayerName()
	{
		return client.getLocalPlayer() != null ? Text.sanitize(client.getLocalPlayer().getName()) : "Player";
	}
}
