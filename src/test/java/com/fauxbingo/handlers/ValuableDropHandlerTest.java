package com.fauxbingo.handlers;

import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropSignal;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class ValuableDropHandlerTest
{
	@Mock
	private ScreenshotService screenshotService;

	@Mock
	private ScheduledExecutorService executor;

	@Mock
	private DropCorrelationService dropCorrelationService;

	private ValuableDropHandler valuableDropHandler;

	@Before
	public void before()
	{
		valuableDropHandler = new ValuableDropHandler(screenshotService, executor, dropCorrelationService);

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
	public void testValuableDrop()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon metal sheet (1,155,320 coins)");

		valuableDropHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals(DetectionMethod.CHAT_VALUABLE_DROP, signal.getDetectionMethod());
		org.junit.Assert.assertEquals("Dragon metal sheet", signal.getItems().get(0).getName());
		org.junit.Assert.assertEquals(1_155_320L, signal.getTotalValueGe().longValue());
	}

	@Test
	public void testValuableDropWithTags()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("<col=ef1020>Valuable drop: Dragon metal sheet (1,155,320 coins)</col>");

		valuableDropHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals("Dragon metal sheet", signal.getItems().get(0).getName());
	}

	/** No local value gating here anymore, that decision moved to DropCorrelationService. */
	@Test
	public void testBelowThresholdStillReported()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon bones (2,500 coins)");

		valuableDropHandler.onChatMessage(event);

		verify(dropCorrelationService).report(any());
	}

	@Test
	public void testScreenshotRequested()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon metal sheet (1,155,320 coins)");

		valuableDropHandler.onChatMessage(event);

		verify(screenshotService).requestScreenshot(any());
	}

	@Test
	public void testValuableDropWithQuantity()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: 30 x Chaos rune (1,680 coins)");

		valuableDropHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		// The bundling key (cleaned) should be "Chaos rune", quantity parsed out of the chat text
		org.junit.Assert.assertEquals("Chaos rune", signal.getItems().get(0).getName());
		org.junit.Assert.assertEquals(30, signal.getItems().get(0).getQuantity());
	}

	@Test
	public void testValuableDropWithLargeQuantity()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: 1,000 x Chaos rune (56,000 coins)");

		valuableDropHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals("Chaos rune", signal.getItems().get(0).getName());
		org.junit.Assert.assertEquals(1000, signal.getItems().get(0).getQuantity());
	}

	/** Splitting on the first " (" cut the (u) off, so the line never matched its kill. */
	@Test
	public void testValuableDropKeepsParenthesesInTheItemName()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("<col=ef1020>Valuable drop: Craw's bow (u) (14,400,000 coins)</col>");

		valuableDropHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals("Craw's bow (u)", signal.getItems().get(0).getName());
		org.junit.Assert.assertEquals(14_400_000L, signal.getTotalValueGe().longValue());
	}

	/** Only the final bracket is the value, whatever the name's own brackets hold. */
	@Test
	public void testValuableDropKeepsEveryBracketedSuffix()
	{
		String[][] cases = {
			{"Valuable drop: Tumeken's shadow (uncharged) (1,234,567,890 coins)", "Tumeken's shadow (uncharged)"},
			{"Valuable drop: Ring of wealth (5) (15,000 coins)", "Ring of wealth (5)"},
			{"Valuable drop: 2 x Dragon pickaxe (or) (100,000 coins)", "Dragon pickaxe (or)"},
		};

		for (String[] c : cases)
		{
			ChatMessage event = new ChatMessage();
			event.setType(ChatMessageType.GAMEMESSAGE);
			event.setMessage(c[0]);
			valuableDropHandler.onChatMessage(event);
		}

		ArgumentCaptor<DropSignal> captor = ArgumentCaptor.forClass(DropSignal.class);
		verify(dropCorrelationService, times(cases.length)).report(captor.capture());
		for (int i = 0; i < cases.length; i++)
		{
			org.junit.Assert.assertEquals(cases[i][1], captor.getAllValues().get(i).getItems().get(0).getName());
		}
		org.junit.Assert.assertEquals(1_234_567_890L, captor.getAllValues().get(0).getTotalValueGe().longValue());
		org.junit.Assert.assertEquals(2, captor.getAllValues().get(2).getItems().get(0).getQuantity());
	}
}
