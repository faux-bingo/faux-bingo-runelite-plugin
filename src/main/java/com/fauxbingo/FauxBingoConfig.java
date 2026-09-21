package com.fauxbingo;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("fauxbingo")
public interface FauxBingoConfig extends Config
{
	@ConfigSection(
		name = "API",
		description = "Enable external API support",
		position = 0
	)
	String apiSection = "api";

	@ConfigSection(
		name = "Team Overlay",
		description = "Configure team name and timestamp overlay display",
		position = 1
	)
	String overlaySection = "overlay";

	@ConfigSection(
		name = "Screenshots",
		description = "Privacy options for evidence screenshots sent to the API",
		position = 2
	)
	String screenshotsSection = "screenshots";

	@ConfigSection(
		name = "Advanced",
		description = "Developer/advanced settings. Most users should never need these.",
		position = 3,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	// ========== API Configuration ==========

	@ConfigItem(
			keyName = "enableBingoApi",
			name = "Enable Bingo API",
			description = "Enables the bingo API for team lookup, presence heartbeat, and reporting drops/deaths.",
			warning = "This feature will submit your character name, account info, and drop/death data to a 3rd-party server not controlled or verified by Runelite developers.",
			position = 1,
			section = apiSection
	)
	default boolean enableBingoApi()
	{
		return false;
	}

	@ConfigItem(
			keyName = "apiToken",
			name = "API Token",
			description = "Your player token from the bingo website, sent to authenticate with the API.",
			position = 2,
			section = apiSection
	)
	default String apiToken()
	{
		return "";
	}

	@ConfigItem(
			keyName = "enableWomAutoUpdate",
			name = "Enable WOM Auto-Update",
			description = "Automatically update your WiseOldMan stats on logout or when gaining 10k+ XP",
			position = 3,
			section = apiSection
	)
	default boolean enableWomAutoUpdate()
	{
		return false;
	}

	@ConfigItem(
			keyName = "showTeamIconsInChat",
			name = "Show Team Icons in Chat",
			description = "Displays a team icon before player names in chat for active bingo event participants, and renders team shortcodes such as :fork: as that team's icon. Requires Bingo API to be enabled.",
			position = 4,
			section = apiSection
	)
	default boolean showTeamIconsInChat()
	{
		return false;
	}


	// ========== Team Overlay Configuration ==========

	@ConfigItem(
		keyName = "displayOverlay",
		name = "Display Overlay",
		description = "Displays the team name and timestamp overlay on your game screen",
		position = 1,
		section = overlaySection
	)
	default boolean displayOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "displayDateTime",
		name = "Date & Time",
		description = "Adds the date and time to the overlay",
		position = 2,
		section = overlaySection
	)
	default boolean displayDateTime()
	{
		return true;
	}

	@ConfigItem(
		keyName = "teamName",
		name = "Team Name",
		description = "Your team name to display in the overlay",
		position = 3,
		section = overlaySection
	)
	default String teamName()
	{
		return "";
	}

	@ConfigItem(
		keyName = "teamNameColor",
		name = "Team Name Color",
		description = "The color of the team name in the overlay",
		position = 4,
		section = overlaySection
	)
	default Color teamNameColor()
	{
		return Color.GREEN;
	}

	@ConfigItem(
		keyName = "dateTimeColor",
		name = "Date & Time Color",
		description = "The color of the date and time in the overlay",
		position = 5,
		section = overlaySection
	)
	default Color dateTimeColor()
	{
		return Color.WHITE;
	}

	// ========== Screenshots Configuration ==========

	@ConfigItem(
		keyName = "screenshotHidePrivateMessages",
		name = "Hide PMs in Screenshots",
		description = "Hide private message windows before taking evidence screenshots for the API, then restore them",
		position = 1,
		section = screenshotsSection
	)
	default boolean screenshotHidePrivateMessages()
	{
		return false;
	}

	@ConfigItem(
		keyName = "screenshotHideChat",
		name = "Hide Chat in Screenshots",
		description = "Hide the main chat area before taking evidence screenshots for the API, then restore it",
		position = 2,
		section = screenshotsSection
	)
	default boolean screenshotHideChat()
	{
		return false;
	}

	// ========== Advanced Configuration ==========

	@ConfigItem(
			keyName = "apiBaseUrl",
			name = "API Base URL",
			description = "Base URL for the bingo API. Only change this for local testing against a dev server; leave default for normal use.",
			position = 1,
			section = advancedSection
	)
	default String apiBaseUrl()
	{
		return "https://fauxbingo.com";
	}
}
