package com.aryanyeole.wmp.common.api;

import java.util.List;

/**
 * Envelope for keyset-paginated endpoints: an opaque {@code nextCursor}
 * (null once the result set is exhausted) instead of page/totalPages —
 * deliberately not the same shape as {@link PageResponse}. There is no
 * total count here: computing one would mean a second, separate query
 * over the whole matching set, which defeats the point of avoiding it.
 */
public record CursorPageResponse<T>(List<T> content, String nextCursor) {
}
