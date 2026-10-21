# Cahaya native plugin contract (v0.3)

A Cahaya plugin is a plain HTTP(S) service. No code is downloaded or
executed on the device — the app only fetches JSON and media URLs from it.
This keeps plugins sandbox-free and reviewable.

## Install

The user pastes the plugin's **base URL** (e.g. `https://example.com/my-plugin`).
Cahaya fetches:

```
GET {base}/plugin.json
```

which must return:

```json
{
  "name": "My Plugin",
  "version": "1.0.0",
  "description": "What it provides"
}
```

If `name` is missing, installation is rejected.

## Endpoints

All endpoints answer JSON. A `404` means "nothing for this title" and is
treated as an empty result, not an error.

### Streams

```
GET {base}/streams?type=movie&id=tt0111161
GET {base}/streams?type=series&id=tt0903747:1:1
```

Response:

```json
{
  "streams": [
    { "url": "https://cdn.example.com/v.mp4", "title": "1080p" },
    { "url": "https://cdn.example.com/v.m3u8", "title": "720p HLS" }
  ]
}
```

`type` is `movie` or `series`. For series, `id` is the episode id
(`{imdbId}:{season}:{episode}`), exactly as the meta `videos[]` entries
provide it. `url` must be directly playable by Android's MediaPlayer
(progressive MP4 or HLS).

### Subtitles

```
GET {base}/subtitles?type=movie&id=tt0111161
```

Response:

```json
{
  "subtitles": [
    { "url": "https://cdn.example.com/en.srt", "lang": "eng", "label": "English" },
    { "url": "https://cdn.example.com/id.srt", "lang": "ind", "label": "Indonesia" }
  ]
}
```

Subtitle files must be SubRip (`.srt`); the player renders them as timed text.

## Notes

- Plugins are enabled by default on install; the user can toggle or remove
  them from the Plugins screen (long-press a row to remove).
- Stream results from addons and plugins are merged in the stream picker,
  each labeled with its source host.
- A minimal reference implementation is ~30 lines in any web framework.
- A future phase may add an on-device JavaScript scraper runtime; the HTTP
  contract above stays valid regardless.
