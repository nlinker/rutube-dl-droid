//! Master playlist parsing: the list of quality variants.

use std::collections::BTreeMap;

use crate::{Error, Result};

/// One chunk of the master playlist: a resolution and where to fetch it.
/// Note: `BANDWIDTH` in playlist is in bits per second. The spec says peak, but Rutube's value
/// is effectively the average, so `bandwidth * duration / 8` estimates the file size.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Variant {
    pub width: u32,
    pub height: u32,
    pub bandwidth: u64,
    pub uri: String,
    /// A second URI serving the same resolution, used as a CDN fallback.
    pub reserve_uri: Option<String>,
}

/// Parse a master playlist into variants, sorted worst to best.
pub fn parse_master(text: &str) -> Result<Vec<Variant>> {
    let playlist =
        m3u8_rs::parse_master_playlist_res(text.as_bytes()).map_err(|e| Error::parse("master playlist", e))?;

    // Keyed on (height, width) so iteration order is from lower to higher.
    let mut by_resolution: BTreeMap<(u64, u64), Variant> = BTreeMap::new();

    for stream in playlist.variants {
        // Audio-only or malformed entries carry no resolution; nothing to select on.
        let Some(resolution) = stream.resolution else {
            continue;
        };

        by_resolution
            .entry((resolution.height, resolution.width))
            .and_modify(|existing| {
                if existing.reserve_uri.is_none() {
                    existing.reserve_uri = Some(stream.uri.clone());
                }
            })
            .or_insert_with(|| Variant {
                width: resolution.width as u32,
                height: resolution.height as u32,
                bandwidth: stream.bandwidth,
                uri: stream.uri.clone(),
                reserve_uri: None,
            });
    }

    Ok(by_resolution.into_values().collect())
}

#[cfg(test)]
mod tests {
    use itertools::Itertools;

    use super::*;

    /// Listed worst-last, and with 480p served by two CDNs — the shape Rutube
    /// actually returns.
    const MASTER: &str = "\
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
https://cdn-a.example/1080/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=854x480
https://cdn-a.example/480/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=650000,RESOLUTION=854x480
https://cdn-b.example/480/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
https://cdn-a.example/720/playlist.m3u8
";

    #[test]
    fn dedup_and_sort() {
        let variants = parse_master(MASTER).expect("parse");

        let heights = variants.iter().map(|v| v.height).collect_vec();
        assert_eq!(heights, [480, 720, 1080], "deduplicated and sorted");

        // The second URI for a resolution is a CDN fallback, not another variant.
        assert_eq!(variants[0].uri, "https://cdn-a.example/480/playlist.m3u8");
        assert_eq!(
            variants[0].reserve_uri.as_deref(),
            Some("https://cdn-b.example/480/playlist.m3u8")
        );

        // A resolution served once has no fallback.
        assert_eq!(variants[1].reserve_uri, None);

        // Bandwidth comes from the first entry; the fallback's own value is ignored.
        let bandwidths = variants.iter().map(|v| v.bandwidth).collect_vec();
        assert_eq!(bandwidths, [600000, 1500000, 3000000]);
    }

    #[test]
    fn variant_with_no_resolution() {
        let audio_only = "\
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS=\"mp4a.40.2\"
https://cdn.example/audio/playlist.m3u8
";
        assert!(parse_master(audio_only).expect("parse").is_empty());
    }
}
