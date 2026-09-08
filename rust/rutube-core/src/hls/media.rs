use itertools::Itertools;
use m3u8_rs::{KeyMethod, MediaPlaylistType};
use url::Url;

use crate::{Error, Result};

/// Playlist state (note, playlists are mutable).
/// `#EXT-X-ENDLIST` and `#EXT-X-PLAYLIST-TYPE` are authoritative;
/// the API's `stream_type` is only an early hint.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlaylistKind {
    /// Finished and immutable, safe to download.
    Vod,
    /// Append-only: a broadcast being recorded. Gains `#EXT-X-ENDLIST` when it ends.
    Event,
    /// Sliding window; earlier segments are dropped as it advances.
    Live,
}

/// Declared encryption. Detected, not implemented — so callers can refuse loudly
/// instead of writing a corrupt file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Encryption {
    pub method: String,
    pub uri: Option<String>,
    pub iv: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Segment {
    pub uri: Url,
    pub duration: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MediaPlaylist {
    pub kind: PlaylistKind,
    pub segments: Vec<Segment>,
    pub encryption: Option<Encryption>,
}

/// Parse a media playlist, resolving relative segment URIs against `base` — the URL
/// the playlist itself came from. `Url::join` drops the variant's `?i=` query.
pub fn parse_media(text: &str, base: &Url) -> Result<MediaPlaylist> {
    let playlist = m3u8_rs::parse_media_playlist_res(text.as_bytes())
        .map_err(|e| Error::parse("media playlist", e))?;

    let segments: Vec<Segment> = playlist
        .segments
        .iter()
        .map(|segment| {
            Result::Ok(Segment {
                uri: base.join(&segment.uri).map_err(|e| Error::parse("segment uri", e))?,
                duration: segment.duration,
            })
        })
        .try_collect()?;

    // A key applies until the next one, so take the first that declares encryption.
    let encryption = playlist
        .segments
        .iter()
        .filter_map(|segment| segment.key.as_ref())
        .find(|key| !matches!(key.method, KeyMethod::None))
        .map(|key| Encryption {
            method: method_name(&key.method),
            uri: key.uri.clone(),
            iv: key.iv.clone(),
        });

    Ok(MediaPlaylist { kind: kind_of(&playlist), segments, encryption })
}

fn kind_of(playlist: &m3u8_rs::MediaPlaylist) -> PlaylistKind {
    // End list first: a finished EVENT carries both tags and is a recording by then.
    if playlist.end_list {
        PlaylistKind::Vod
    } else if playlist.playlist_type == Some(MediaPlaylistType::Event) {
        PlaylistKind::Event
    } else {
        PlaylistKind::Live
    }
}

fn method_name(method: &KeyMethod) -> String {
    match method {
        KeyMethod::None => "NONE".to_owned(),
        KeyMethod::AES128 => "AES-128".to_owned(),
        KeyMethod::SampleAES => "SAMPLE-AES".to_owned(),
        KeyMethod::Other(other) => other.clone(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Real variant URL shape: a stem the segment URIs repeat, plus the `?i=` query.
    const BASE: &str = "https://cdn.example/hls-vod/tok/42fcc.mp4.m3u8?i=136x240_612";

    const TWO_SEGMENTS: &str = "\
#EXTINF:6.0,
42fcc.mp4/segment-1-v1-a1.ts
#EXTINF:4.5,
42fcc.mp4/segment-2-v1-a1.ts
";

    fn base() -> Url {
        Url::parse(BASE).unwrap()
    }

    fn vod(body: &str) -> String {
        format!("#EXTM3U\n#EXT-X-TARGETDURATION:6\n{body}#EXT-X-ENDLIST\n")
    }

    #[test]
    fn resolve_segment_uris() {
        let playlist = parse_media(&vod(TWO_SEGMENTS), &base()).expect("parse");

        assert_eq!(playlist.kind, PlaylistKind::Vod);
        assert_eq!(playlist.encryption, None);

        let uris = playlist.segments.iter().map(|s| s.uri.as_str()).collect_vec();
        assert_eq!(
            uris,
            [
                "https://cdn.example/hls-vod/tok/42fcc.mp4/segment-1-v1-a1.ts",
                "https://cdn.example/hls-vod/tok/42fcc.mp4/segment-2-v1-a1.ts",
            ],
            "the variant's ?i= query must not leak into segment urls"
        );
        assert_eq!(playlist.segments[1].duration, 4.5);
    }

    #[test]
    fn playlist_kind() {
        let event = format!("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-PLAYLIST-TYPE:EVENT\n{TWO_SEGMENTS}");
        let cases = [
            (vod(TWO_SEGMENTS), PlaylistKind::Vod),
            (event.clone(), PlaylistKind::Event),
            (format!("#EXTM3U\n#EXT-X-TARGETDURATION:6\n{TWO_SEGMENTS}"), PlaylistKind::Live),
            // A finished EVENT is just a recording.
            (format!("{event}#EXT-X-ENDLIST\n"), PlaylistKind::Vod),
        ];

        for (text, expected) in cases {
            assert_eq!(parse_media(&text, &base()).expect("parse").kind, expected, "{text}");
        }
    }

    #[test]
    fn detect_encryption() {
        let encrypted = vod(
            "#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.example/key.bin\",IV=0x0123456789abcdef0123456789abcdef\n\
             #EXTINF:6.0,\n\
             42fcc.mp4/segment-1-v1-a1.ts\n",
        );
        let encryption = parse_media(&encrypted, &base())
            .expect("parse")
            .encryption
            .expect("key should be reported");

        assert_eq!(encryption.method, "AES-128");
        assert_eq!(encryption.uri.as_deref(), Some("https://cdn.example/key.bin"));
        assert!(encryption.iv.is_some());

        // METHOD=NONE is an explicit "not encrypted", not a key.
        let plain = vod("#EXT-X-KEY:METHOD=NONE\n#EXTINF:6.0,\n42fcc.mp4/segment-1-v1-a1.ts\n");
        assert_eq!(parse_media(&plain, &base()).expect("parse").encryption, None);
    }
}
