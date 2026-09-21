package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.FauxBingoPlugin;
import com.fauxbingo.services.data.TeamAccountDto;
import com.fauxbingo.services.data.TeamPlayerDto;
import com.fauxbingo.services.data.TeamRosterDto;
import com.fauxbingo.services.data.TeamsResponseDto;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import okhttp3.CacheControl;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches the full team roster from GET /v1/teams and badges clan chat names with their team's
 * icon. Matching is on accounts[].displayName (the RSN) - players[].memberName is a Discord
 * nickname and must never be matched against an RSN (docs/v1-api.md, GET /v1/teams).
 *
 * Icons are downloaded from GET {apiBaseUrl}/v1/teams/{teamId}/icon. Every URL this service
 * contacts is built from the user-configured API base plus a hardcoded path - no URL is ever
 * taken out of an API response.
 *
 * The same icons double as chat emoji: a team with `chatCode` of `fork` is rendered wherever
 * anyone types `:fork:`, in chat or over their head. Any player may use any team's code - unlike
 * the name badge, the code is not checked against who sent the message.
 */
@Slf4j
@Singleton
public class TeamIconService
{
	private static final String TEAMS_PATH = "/v1/teams";
	private static final String ICON_SEGMENT = "icon";
	private static final long ICON_RETRY_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(30);
	private static final int MAX_CHAT_CODE_LENGTH = 16;
	// Public chat's 80-character limit already caps this near 16; the guard is for the message
	// types that have no such limit.
	private static final int MAX_REPLACEMENTS_PER_MESSAGE = 8;

	private final Client client;
	private final FauxBingoConfig config;
	private final Provider<String> apiBaseUrl;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final ClientThread clientThread;

	private final Map<String, String> rsnToTeamId = new HashMap<>();     // standardized RSN -> team id
	private final Map<String, String> chatCodeToTeamId = new HashMap<>(); // normalized chat code -> team id
	private final Map<String, Integer> teamToSpriteIndex = new HashMap<>(); // team id -> mod icon array index
	private final Map<String, Long> iconFetchFailures = new HashMap<>();    // team id -> when its icon last failed to fetch or decode
	private int iconsRegisteredCount = 0;
	private volatile int initialModIconsLength = -1;                        // captured on client thread before first fetch
	private final AtomicBoolean started = new AtomicBoolean(false);
	private volatile ScheduledFuture<?> refreshTask = null;

	@Inject
	public TeamIconService(
		Client client,
		FauxBingoConfig config,
		@Named(FauxBingoPlugin.API_BASE_URL_KEY) Provider<String> apiBaseUrl,
		OkHttpClient okHttpClient,
		Gson gson,
		ScheduledExecutorService executor,
		ClientThread clientThread
	)
	{
		this.client = client;
		this.config = config;
		this.apiBaseUrl = apiBaseUrl;
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.executor = executor;
		this.clientThread = clientThread;
	}

	public void start()
	{
		// Claimed here rather than by checking refreshTask, which stays null until the lambda below
		// reaches the client thread: two start() calls in quick succession - onConfigChanged lands
		// one on every apiToken/apiBaseUrl/enableBingoApi edit - would otherwise both schedule a
		// task, and only the second could ever be cancelled.
		if (!started.compareAndSet(false, true))
		{
			return;
		}

		// BooleanSupplier overload: returning false re-runs this next tick. Plugins start before the
		// game loads, so getModIcons() is null at the login screen and we have to wait for it.
		clientThread.invokeLater(() -> {
			if (!started.get())
			{
				// shutdown() ran before this reached the client thread.
				return true;
			}

			IndexedSprite[] modIcons = client.getModIcons();
			if (modIcons == null)
			{
				return false;
			}

			initialModIconsLength = modIcons.length;
			refreshTask = executor.scheduleAtFixedRate(this::fetchTeamData, 0, 5, TimeUnit.MINUTES);
			return true;
		});
	}

	private void fetchTeamData()
	{
		if (!enabled())
		{
			return;
		}

		try
		{
			Request request = new Request.Builder()
				.url(apiBaseUrl.get() + TEAMS_PATH)
				.header("Authorization", ApiConfigSanitizer.bearer(config.apiToken()))
				.build();

			TeamsResponseDto parsed;
			try (Response response = okHttpClient.newCall(request).execute())
			{
				if (!response.isSuccessful() || response.body() == null)
				{
					// Warn, not debug: an unusable roster silently disables every chat icon, and
					// the sibling services already warn on the same 401 (MeService, PresenceService).
					if (response.code() == 401)
					{
						log.warn("GET /v1/teams returned 401, token is missing or unknown. Team chat icons stay off until it is fixed.");
					}
					else
					{
						log.warn("GET /v1/teams returned {}: {}", response.code(), response.message());
					}
					return;
				}
				parsed = gson.fromJson(response.body().charStream(), TeamsResponseDto.class);
			}

			if (parsed == null || parsed.getTeams() == null)
			{
				return;
			}

			updateRsnMap(parsed.getTeams());
			updateChatCodeMap(parsed.getTeams());
			updateIcons(parsed.getTeams());
		}
		catch (Exception e)
		{
			log.debug("Error fetching team data", e);
		}
	}

	private void updateRsnMap(List<TeamRosterDto> teams)
	{
		synchronized (rsnToTeamId)
		{
			rsnToTeamId.clear();
			for (TeamRosterDto team : teams)
			{
				if (team.getId() == null || team.getPlayers() == null)
				{
					continue;
				}
				for (TeamPlayerDto player : team.getPlayers())
				{
					if (player.getAccounts() == null)
					{
						continue;
					}
					for (TeamAccountDto account : player.getAccounts())
					{
						if (account.getDisplayName() == null)
						{
							continue;
						}
						// Text.standardize, not trim+toLowerCase: the game encodes spaces in chat
						// names as \u00A0, so an RSN like "DH Herc" arrives as "dh\u00a0herc" and
						// would never match the API's plain space. Both sides must normalize the
						// same way or multi-word RSNs are unmatchable.
						String rsn = Text.standardize(account.getDisplayName());
						if (rsn.isEmpty())
						{
							continue;
						}
						rsnToTeamId.put(rsn, team.getId());
					}
				}
			}
		}
	}

	private void updateChatCodeMap(List<TeamRosterDto> teams)
	{
		synchronized (chatCodeToTeamId)
		{
			chatCodeToTeamId.clear();
			for (TeamRosterDto team : teams)
			{
				if (team.getId() == null)
				{
					continue;
				}
				// Trim here rather than in normalizeChatCode: whitespace around a stored code is
				// the server being untidy, but ": fork :" typed in chat is not a shortcode.
				String code = normalizeChatCode(team.getChatCode() == null ? null : team.getChatCode().trim());
				if (code == null)
				{
					continue;
				}
				// Codes are unique per event server-side, so a collision here means the website
				// let one through. First team in the response wins.
				if (chatCodeToTeamId.putIfAbsent(code, team.getId()) != null)
				{
					log.warn("Ignoring duplicate team chat code '{}' on team {}", code, team.getId());
				}
			}
		}
	}

	/**
	 * Both the roster and the text scanned out of chat go through this, so the two can never
	 * disagree about what counts as a code.
	 *
	 * The letter requirement is what keeps timestamps safe: without it a team whose code was "30"
	 * would turn the {@code :30:} inside "10:30:45" into an icon.
	 *
	 * @return the normalized code, or null if this is not a usable shortcode
	 */
	private static String normalizeChatCode(String raw)
	{
		if (raw == null || raw.isEmpty() || raw.length() > MAX_CHAT_CODE_LENGTH)
		{
			return null;
		}

		boolean hasLetter = false;
		for (int i = 0; i < raw.length(); i++)
		{
			char c = Character.toLowerCase(raw.charAt(i));
			if (c >= 'a' && c <= 'z')
			{
				hasLetter = true;
			}
			else if (!((c >= '0' && c <= '9') || c == '_'))
			{
				return null;
			}
		}

		return hasLetter ? raw.toLowerCase(Locale.ROOT) : null;
	}

	/**
	 * @return the mod icon index for this code's team, or null if the code is unknown or that
	 * team's icon has not downloaded yet - callers leave the literal ":fork:" in place
	 */
	private Integer spriteIndexForCode(String rawCode)
	{
		String code = normalizeChatCode(rawCode);
		if (code == null)
		{
			return null;
		}

		String teamId;
		synchronized (chatCodeToTeamId)
		{
			teamId = chatCodeToTeamId.get(code);
		}

		if (teamId == null)
		{
			return null;
		}

		synchronized (teamToSpriteIndex)
		{
			return teamToSpriteIndex.get(teamId);
		}
	}

	/**
	 * Replaces every {@code :code:} that resolves to a registered team icon with its {@code <img>}
	 * tag, scanning left to right.
	 *
	 * @return the rewritten message, or null if nothing matched
	 */
	String updateMessage(String message)
	{
		if (message == null || message.length() < 3)
		{
			return null;
		}

		StringBuilder out = null;
		int copied = 0;
		int replacements = 0;
		int search = 0;

		while (replacements < MAX_REPLACEMENTS_PER_MESSAGE)
		{
			int open = message.indexOf(':', search);
			if (open < 0)
			{
				break;
			}

			int close = message.indexOf(':', open + 1);
			if (close < 0)
			{
				break;
			}

			Integer index = close - open - 1 > MAX_CHAT_CODE_LENGTH
				? null
				: spriteIndexForCode(message.substring(open + 1, close));

			if (index == null)
			{
				// Resume from the closing colon rather than past it: in ":x:fork:" the colon that
				// ended the non-match is the one that opens ":fork:".
				search = close;
				continue;
			}

			if (out == null)
			{
				out = new StringBuilder(message.length());
			}
			out.append(message, copied, open).append("<img=").append(index).append('>');
			copied = close + 1;
			search = close + 1;
			replacements++;
		}

		if (out == null)
		{
			return null;
		}

		return out.append(message, copied, message.length()).toString();
	}

	/**
	 * The player-authored channels, matching RuneLite's own Emojis plugin. Game and system
	 * messages are excluded so Jagex-worded lines containing a colon pair cannot be rewritten.
	 */
	private static boolean isPlayerChat(ChatMessageType type)
	{
		switch (type)
		{
			case PUBLICCHAT:
			case MODCHAT:
			case FRIENDSCHAT:
			case CLAN_CHAT:
			case CLAN_GUEST_CHAT:
			case CLAN_GIM_CHAT:
			case PRIVATECHAT:
			case PRIVATECHATOUT:
			case MODPRIVATECHAT:
				return true;
			default:
				return false;
		}
	}

	private void updateIcons(List<TeamRosterDto> teams)
	{
		for (TeamRosterDto team : teams)
		{
			String teamId = team.getId();

			if (teamId == null || teamId.trim().isEmpty())
			{
				continue;
			}

			// A team whose sprite is already registered is done for this session - the icon URL is
			// derived from the team id, so it never changes.
			synchronized (teamToSpriteIndex)
			{
				if (teamToSpriteIndex.containsKey(teamId))
				{
					continue;
				}
			}
			// A failure is remembered for a cooldown rather than the whole session: an icon that
			// 404'd because it had not been uploaded yet, or a download that hit a network blip,
			// has to be able to recover without the user restarting the client.
			synchronized (iconFetchFailures)
			{
				Long failedAt = iconFetchFailures.get(teamId);
				if (failedAt != null && System.currentTimeMillis() - failedAt < ICON_RETRY_COOLDOWN_MS)
				{
					continue;
				}
			}

			downloadAndRegisterIcon(teamId);
		}
	}

	/**
	 * Builds {apiBaseUrl}/v1/teams/{teamId}/icon. The team id is added as an encoded path segment so
	 * a server-supplied id cannot inject extra segments, and the result is rejected unless it is
	 * still under the hardcoded /v1/teams path.
	 */
	private HttpUrl iconUrl(String teamId)
	{
		HttpUrl base = HttpUrl.parse(apiBaseUrl.get());
		if (base == null)
		{
			return null;
		}

		HttpUrl teamsUrl = base.newBuilder()
			.addPathSegment("v1")
			.addPathSegment("teams")
			.build();

		HttpUrl url = teamsUrl.newBuilder()
			.addPathSegment(teamId)
			.addPathSegment(ICON_SEGMENT)
			.build();

		if (!url.encodedPath().startsWith(teamsUrl.encodedPath() + "/"))
		{
			log.debug("Refusing icon URL for team id {}: escapes {}", teamId, teamsUrl.encodedPath());
			return null;
		}

		return url;
	}

	private void downloadAndRegisterIcon(String teamId)
	{
		HttpUrl url = iconUrl(teamId);
		if (url == null)
		{
			rememberIconFailure(teamId);
			return;
		}

		// RuneLite's injected OkHttpClient carries a shared on-disk cache, and the icon route
		// answers with `Cache-Control: public, max-age=3600` and no validator - so without this an
		// icon replaced on the site stays stale for an hour, across plugin toggles and client
		// restarts alike. This runs once per team per session, so always going to the network is
		// cheap.
		Request request = new Request.Builder()
			.url(url)
			.cacheControl(CacheControl.FORCE_NETWORK)
			.header("Authorization", ApiConfigSanitizer.bearer(config.apiToken()))
			.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				log.debug("Failed to download icon for team {}: {}", teamId, response);
				rememberIconFailure(teamId);
				return;
			}

			// A redirect would send us to a host the server picked rather than one derived from the
			// configured API base, so only accept a body that came back from the URL we asked for.
			if (!url.equals(response.request().url()))
			{
				log.debug("Ignoring redirected icon response for team {}: {}", teamId, response.request().url());
				rememberIconFailure(teamId);
				return;
			}

			BufferedImage image = ImageIO.read(response.body().byteStream());
			if (image == null)
			{
				log.debug("Failed to decode icon for team {}", teamId);
				rememberIconFailure(teamId);
				return;
			}

			BufferedImage resized = ImageUtil.resizeImage(image, 18, 16);
			clientThread.invokeLater(() -> {
				IndexedSprite sprite = ImageUtil.getImageIndexedSprite(resized, client);
				IndexedSprite[] current = client.getModIcons();
				IndexedSprite[] updated = Arrays.copyOf(current, current.length + 1);
				updated[current.length] = sprite;
				client.setModIcons(updated);
				iconsRegisteredCount++;

				synchronized (teamToSpriteIndex)
				{
					teamToSpriteIndex.put(teamId, current.length);
				}
			});
		}
		catch (Exception e)
		{
			log.debug("Error registering icon for team " + teamId, e);
			rememberIconFailure(teamId);
		}
	}

	private void rememberIconFailure(String teamId)
	{
		synchronized (iconFetchFailures)
		{
			iconFetchFailures.put(teamId, System.currentTimeMillis());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!enabled() || !config.showTeamIconsInChat())
		{
			return;
		}

		MessageNode node = event.getMessageNode();
		if (node == null)
		{
			return;
		}

		boolean changed = badgeSenderName(event, node);
		changed |= replaceChatCodes(event, node);

		if (changed)
		{
			// The chatbox line for this message may already be built, and nothing rebuilds it on
			// its own - without this the edit does not show until some later message forces a
			// rebuild.
			client.refreshChat();
		}
	}

	private boolean badgeSenderName(ChatMessage event, MessageNode node)
	{
		String name = event.getName();
		if (name == null)
		{
			return false;
		}

		String sanitizedName = Text.standardize(name);
		String teamId;
		synchronized (rsnToTeamId)
		{
			teamId = rsnToTeamId.get(sanitizedName);
		}

		if (teamId == null)
		{
			return false;
		}

		Integer index;
		synchronized (teamToSpriteIndex)
		{
			index = teamToSpriteIndex.get(teamId);
		}

		if (index == null)
		{
			return false;
		}

		node.setName("<img=" + index + ">" + name);
		return true;
	}

	private boolean replaceChatCodes(ChatMessage event, MessageNode node)
	{
		if (event.getType() == null || !isPlayerChat(event.getType()))
		{
			return false;
		}

		String updated = updateMessage(node.getValue());
		if (updated == null)
		{
			return false;
		}

		// setValue, not setRuneLiteFormatMessage: RuneLite's own Emojis plugin edits the same
		// field, so both plugins' replacements survive whichever order the two handlers run in.
		node.setValue(updated);
		return true;
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		if (!enabled() || !config.showTeamIconsInChat())
		{
			return;
		}

		Actor actor = event.getActor();
		if (!(actor instanceof Player))
		{
			return;
		}

		String updated = updateMessage(event.getOverheadText());
		if (updated == null)
		{
			return;
		}

		actor.setOverheadText(updated);
	}

	public void shutdown()
	{
		started.set(false);

		if (refreshTask != null)
		{
			refreshTask.cancel(false);
			refreshTask = null;
		}

		clientThread.invokeLater(() -> {
			if (initialModIconsLength >= 0)
			{
				int expectedLength = initialModIconsLength + iconsRegisteredCount;
				if (client.getModIcons().length == expectedLength)
				{
					// No other plugin has appended after us — safe to truncate.
					client.setModIcons(Arrays.copyOf(client.getModIcons(), initialModIconsLength));
				}
				else
				{
					// Another plugin has appended sprites after ours. Truncating would corrupt
					// their indices. Leave the array intact and accept that our sprites are leaked
					// for the remainder of this session.
					log.debug("Skipping modIcons truncation: array length {} does not match expected {}. " +
							"Another plugin has appended sprites after TeamIconService.",
						client.getModIcons().length, expectedLength);
				}
			}
		});

		iconsRegisteredCount = 0;
		initialModIconsLength = -1;

		synchronized (rsnToTeamId)
		{
			rsnToTeamId.clear();
		}
		synchronized (chatCodeToTeamId)
		{
			chatCodeToTeamId.clear();
		}
		synchronized (teamToSpriteIndex)
		{
			teamToSpriteIndex.clear();
		}
		synchronized (iconFetchFailures)
		{
			iconFetchFailures.clear();
		}
	}

	private boolean enabled()
	{
		return config.enableBingoApi() && !ApiConfigSanitizer.sanitize(config.apiToken()).isEmpty()
			&& !apiBaseUrl.get().isEmpty();
	}
}
