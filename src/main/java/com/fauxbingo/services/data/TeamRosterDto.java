package com.fauxbingo.services.data;

import java.util.List;
import lombok.Data;

/**
 * One entry in `teams[]` on GET /v1/teams. Unlike TeamDto (from /v1/me) this carries the roster.
 * The response's `iconUrl` is deliberately not mapped: icons are fetched from a URL derived from
 * the configured API base (see TeamIconService), never from a URL the API hands us.
 *
 * `chatCode` is stored without the surrounding colons: the API returns `fork` for a team whose
 * icon players render by typing `:fork:`. Null or empty means the team has no shortcode.
 */
@Data
public class TeamRosterDto
{
	private String id;
	private String name;
	private String chatCode;
	private TeamColorsDto colors;
	private List<TeamPlayerDto> players;
}
