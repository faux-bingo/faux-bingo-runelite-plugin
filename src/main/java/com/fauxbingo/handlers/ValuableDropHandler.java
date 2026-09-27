package com.fauxbingo.handlers;

import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import com.fauxbingo.services.data.SourceKind;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;

/**
 * Handles valuable drop notifications from chat messages. Detects when the game announces a
 * valuable drop and reports it to DropCorrelationService, which decides whether it stands alone
 * or corroborates a LOOT signal from LootEventHandler/RaidLootHandler for the same item.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ValuableDropHandler
{
	/**
	 * The name is captured apart from the trailing coin value because it can hold parentheses of
	 * its own: "Craw's bow (u) (14,400,000 coins)" is still named "Craw's bow (u)".
	 */
	private static final Pattern VALUABLE_DROP_PATTERN = Pattern.compile(
		".*Valuable drop: ([^<>]+?) \\(((?:\\d+,?)+) coins\\)(?:</col>)?"
	);

	/** The chat line embeds quantity in the name, as in "30 x Dragon bones". */
	private static final Pattern QUANTITY_PREFIX = Pattern.compile("^([0-9,]+) x ");

	private final ScreenshotService screenshotService;
	private final ScheduledExecutorService executor;
	private final DropCorrelationService dropCorrelationService;

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String chatMessage = event.getMessage();
		Matcher matcher = VALUABLE_DROP_PATTERN.matcher(chatMessage);
		
		if (matcher.matches())
		{
			long valuableDropValue = Long.parseLong(matcher.group(2).replaceAll(",", ""));
			String valuableDropNameWithQuantity = matcher.group(1);
			reportValuableDrop(chatMessage, valuableDropNameWithQuantity, valuableDropValue);
		}
	}

	private void reportValuableDrop(String rawChatLine, String itemNameWithQuantity, long value)
	{
		String itemName = cleanItemName(itemNameWithQuantity);
		int quantity = parseQuantity(itemNameWithQuantity);

		DropItem dropItem = DropItem.builder()
			.name(itemName)
			.quantity(quantity)
			.unitPriceGe(quantity > 0 ? value / quantity : value)
			.build();

		screenshotService.requestScreenshot(image -> executor.execute(() -> {
			DropSignal signal = DropSignal.builder()
				.detectionMethod(DetectionMethod.CHAT_VALUABLE_DROP)
				.raw(rawChatLine)
				.sourceKind(SourceKind.OTHER)
				.items(Collections.singletonList(dropItem))
				.totalValueGe(value)
				.screenshot(image)
				.build();
			dropCorrelationService.report(signal);
		}));
	}

	private String cleanItemName(String itemName)
	{
		if (itemName == null)
		{
			return null;
		}
		// Strip quantity prefix like "30 x " or "1,000 x " to help with bundling across different handlers
		return QUANTITY_PREFIX.matcher(itemName).replaceFirst("");
	}

	/** The chat text is the only source for quantity, so parse it out rather than assuming 1. */
	private static int parseQuantity(String itemNameWithQuantity)
	{
		Matcher matcher = QUANTITY_PREFIX.matcher(itemNameWithQuantity);
		if (!matcher.find())
		{
			return 1;
		}
		try
		{
			return Math.max(1, Integer.parseInt(matcher.group(1).replaceAll(",", "")));
		}
		catch (NumberFormatException e)
		{
			return 1;
		}
	}
}
