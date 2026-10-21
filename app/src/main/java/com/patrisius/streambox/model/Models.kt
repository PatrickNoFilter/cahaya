package com.patrisius.streambox.model

import kotlinx.serialization.Serializable

/**
 * App-level media models, decoupled from any single addon's JSON shape.
 * AddonClient maps addon responses into these.
 */
@Serializable
data class MediaItem(
    val id: String,                 // e.g. "tt0111161"
    val type: String,               // "movie" | "series" | ...
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val imdbRating: String? = null,
    val year: String? = null,
    val runtime: String? = null,
    val trailers: List<Trailer> = emptyList(),
    val videos: List<Episode> = emptyList(),   // series only
)

@Serializable
data class Trailer(
    val source: String,
    val type: String = "Trailer",
)

@Serializable
data class Episode(
    val id: String,                // e.g. "tt0903747:1:1" — passed to /stream/
    val season: Int = 0,
    val number: Int = 0,
    val name: String = "",
    val overview: String? = null,
    val thumbnail: String? = null,
    val firstAired: String? = null,
    val rating: String? = null,
)

@Serializable
data class StreamSource(
    val url: String,
    val title: String? = null,
    val quality: String? = null,   // "1080p" etc., parsed from title when present
    val addonId: String = "",
    val addonName: String = "",
    val behaviorHints: Map<String, String> = emptyMap(),
)

@Serializable
data class SubtitleTrack(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String = "",
)
