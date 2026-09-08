mod pipeline;
mod sink;

use itertools::Itertools;
pub use pipeline::run;
pub use sink::Sink;
use url::Url;

use crate::{
    Error, Result, api,
    hls::{PlaylistKind, Segment, Variant, parse_master, parse_media},
    progress::ProgressListener,
    session::Session,
    url::VideoRef,
};

/// Attempts per segment before giving up.
const RETRIES: usize = 3;

/// Exact vertical resolution, or automatic selection options
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum Quality {
    #[default]
    Worst,
    Best,
    Height(u32),
}

pub struct DownloadOptions {
    pub quality: Quality,
    pub workers: usize,
}

impl Default for DownloadOptions {
    fn default() -> Self {
        Self { quality: Quality::default(), workers: 6 }
    }
}

/// A download resolved down to its segment list, ready to run.
///
/// Built in two phases on purpose: [`Download::probe`] settles everything without
/// writing a byte, so a caller can name the output from `title` and the resolution
/// before it opens the sink. [`Download::fetch`] then does the transfer.
pub struct Download<'a> {
    session: &'a Session,
    workers: usize,
    pub title: String,
    pub width: u32,
    pub height: u32,
    pub segments: Vec<Segment>,
}

impl<'a> Download<'a> {
    /// Resolve metadata, pick a variant, and collect the segment list.
    ///
    /// Refuses live streams and encrypted segments rather than producing a
    /// truncated or corrupt file.
    pub async fn probe(session: &'a Session, video: &VideoRef, options: &DownloadOptions) -> Result<Self> {
        let meta = api::play_options(session, video).await?;
        let master = get_text(session, &meta.video_balancer.m3u8).await?;
        let variants = parse_master(&master)?;
        let variant = select(&variants, options.quality)?;

        // The playlist URL is also the base its segment URIs resolve against, so
        // both travel together. `reserve_uri` is the same quality on a second CDN.
        let (base, media) = match fetch_playlist(session, &variant.uri).await {
            Ok(found) => found,
            Err(primary) => match &variant.reserve_uri {
                Some(reserve) => fetch_playlist(session, reserve).await.map_err(|_| primary)?,
                None => return Err(primary),
            },
        };

        // Segments get a fallback address on whichever CDN we did not read the
        // playlist from.
        let reserve_base = variant
            .reserve_uri
            .as_deref()
            .and_then(|uri| Url::parse(uri).ok())
            .filter(|reserve| *reserve != base);

        let playlist = parse_media(&media, &base, reserve_base.as_ref())?;

        if playlist.kind != PlaylistKind::Vod {
            return Err(Error::LiveStream);
        }
        if let Some(encryption) = playlist.encryption {
            return Err(Error::Encrypted { method: encryption.method });
        }

        Ok(Self {
            session,
            workers: options.workers,
            title: meta.title.unwrap_or_else(|| video.id.clone()),
            width: variant.width,
            height: variant.height,
            segments: playlist.segments,
        })
    }

    /// Download every segment and write them to `sink` in order.
    pub async fn fetch(&self, sink: &mut dyn Sink, progress: &dyn ProgressListener) -> Result<()> {
        let fetch_one = |index: usize| {
            let segment = self.segments[index].clone();
            async move { get_bytes(self.session, &segment).await }
        };

        run(self.segments.len(), self.workers, fetch_one, sink, progress).await
    }
}

/// Variants arrive sorted worst to best, so the ends of the slice are the extremes.
fn select(variants: &[Variant], quality: Quality) -> Result<&Variant> {
    let chosen = match quality {
        Quality::Best => variants.last(),
        Quality::Worst => variants.first(),
        Quality::Height(wanted) => variants.iter().find(|variant| variant.height == wanted),
    };

    chosen.ok_or_else(|| match quality {
        Quality::Height(wanted) => {
            Error::NoSuchResolution { wanted, available: variants.iter().map(|v| v.height).join(", ") }
        }
        _ => Error::NoVariants,
    })
}

async fn fetch_playlist(session: &Session, url: &str) -> Result<(Url, String)> {
    let text = get_text(session, url).await?;
    let base = Url::parse(url).map_err(|e| Error::parse("variant url", e))?;
    Ok((base, text))
}

async fn get_text(session: &Session, url: &str) -> Result<String> {
    let response = session.http().get(url).send().await?.error_for_status()?;
    Ok(response.text().await?)
}

/// Try the primary address, then the second CDN, `RETRIES` times over.
///
/// Primary first: it is the host whose playlist already answered, so it is known
/// good, while the reserve has never been exercised.
async fn get_bytes(session: &Session, segment: &Segment) -> Result<Vec<u8>> {
    let addresses = std::iter::once(&segment.uri).chain(segment.reserve_uri.iter());
    let mut last = None;

    for _ in 0..RETRIES {
        for url in addresses.clone() {
            match session.http().get(url.clone()).send().await {
                Ok(response) => match response.error_for_status() {
                    Ok(ok) => return Ok(ok.bytes().await?.to_vec()),
                    Err(e) => last = Some(e),
                },
                Err(e) => last = Some(e),
            }
        }
    }

    Err(last.map(Error::Http).unwrap_or(Error::NotFound))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn variants() -> Vec<Variant> {
        [240, 720, 1080]
            .into_iter()
            .map(|height| Variant {
                width: height * 9 / 16,
                height,
                uri: format!("https://cdn.example/{height}.m3u8"),
                reserve_uri: None,
            })
            .collect()
    }

    #[test]
    fn select_variant() {
        let variants = variants();

        assert_eq!(select(&variants, Quality::Best).unwrap().height, 1080);
        assert_eq!(select(&variants, Quality::Worst).unwrap().height, 240);
        assert_eq!(select(&variants, Quality::Height(720)).unwrap().height, 720);
        assert_eq!(Quality::default(), Quality::Worst);

        let missing = select(&variants, Quality::Height(480)).unwrap_err().to_string();
        assert!(missing.contains("240, 720, 1080"), "{missing}");

        assert!(matches!(select(&[], Quality::Best), Err(Error::NoVariants)));
    }
}
