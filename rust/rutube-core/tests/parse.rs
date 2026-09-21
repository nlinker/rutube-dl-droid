use rutube_core::hls;

const MASTER: &str = include_str!("fixtures/master.m3u8");
const MEDIA: &str = include_str!("fixtures/media.m3u8");

#[test]
fn real_master_playlist_collapses_to_five_resolutions() {
    let variants = hls::parse_master(MASTER).expect("parse master");

    // The captured playlist carries ten #EXT-X-STREAM-INF entries: five
    // resolutions served by two CDNs each.
    let heights: Vec<u32> = variants.iter().map(|v| v.height).collect();
    assert_eq!(heights, [240, 360, 480, 720, 1080]);

    for variant in &variants {
        assert!(
            variant.reserve_uri.is_some(),
            "{}p should have picked up the second CDN as its reserve path",
            variant.height
        );
        assert_ne!(
            variant.uri,
            variant.reserve_uri.clone().unwrap(),
            "reserve path must be a different host"
        );
    }
}

#[test]
fn every_variant_carries_a_bandwidth() {
    // The size estimate is built on BANDWIDTH, so every entry must have one.
    // The assumption "higher resolution -> higher bandwidth" does not always hold
    // (e.g. 720p and 1080p use a higher H.264 profile and end up below 480p).
    // Do not assume ordering.
    let bandwidths: Vec<u64> = hls::parse_master(MASTER)
        .expect("parse master")
        .iter()
        .map(|v| v.bandwidth)
        .collect();
    assert_eq!(bandwidths, [612000, 1199000, 1212000, 1150000, 1134000]);
}

#[test]
fn variants_are_portrait_so_width_is_the_smaller_side() {
    // Protects the sorting logic: quality is selected on height, not width. This clip is
    // 9:16, so sorting on width would happen to work — sorting on the wrong field
    // only shows up on landscape content.
    for variant in hls::parse_master(MASTER).expect("parse master") {
        assert!(variant.width < variant.height);
    }
}

/// Segment URIs are relative and must be resolved against the variant playlist URL.
///
/// This is the one place the reference implementations looked like they disagreed.
/// Against real data they all produce the same result, because the segment URI's
/// leading directory repeats the variant URL's filename stem:
///
/// ```text
/// variant:  …/0x5000c500e9cf2bbd/42fcc…fb9f.mp4.m3u8?i=136x240_612
/// segment:  42fcc…fb9f.mp4/segment-1-v1-a1.ts
/// resolved: …/0x5000c500e9cf2bbd/42fcc…fb9f.mp4/segment-1-v1-a1.ts
/// ```
#[test]
fn segment_uris_are_relative() {
    let first_segment = MEDIA
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty() && !line.starts_with('#'))
        .expect("media playlist should list segments");

    assert!(
        !first_segment.starts_with("http"),
        "segments are expected to be relative: {first_segment}"
    );

    let variant_url = url::Url::parse(
        "https://river-4-478.rtbcdn.ru/hls-vod/-FhC6d9jSVNwdQzdNBgGSA/1787078423/3298\
         /0x5000c500e9cf2bbd/42fcc33f95004de5a9f4584b7cccfb9f.mp4.m3u8?i=136x240_612",
    )
    .unwrap();

    let resolved = variant_url.join(first_segment).expect("join");

    assert_eq!(
        resolved.path(),
        "/hls-vod/-FhC6d9jSVNwdQzdNBgGSA/1787078423/3298/0x5000c500e9cf2bbd\
         /42fcc33f95004de5a9f4584b7cccfb9f.mp4/segment-1-v1-a1.ts"
    );
    assert_eq!(resolved.query(), None, "the variant query must not leak through");
}

#[test]
fn captured_media_playlist_is_unencrypted_mpeg_ts() {
    assert!(
        !MEDIA.contains("#EXT-X-KEY"),
        "this fixture is the evidence that public VOD is unencrypted; \
         if it ever gains a key, hls::crypto is needed"
    );
    assert!(MEDIA.contains(".ts"), "segments should be MPEG-TS");
    assert!(
        MEDIA.contains("#EXT-X-ENDLIST"),
        "VOD playlists terminate; a live stream would not"
    );
}
