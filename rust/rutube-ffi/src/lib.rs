use std::sync::Arc;

use rutube_core::{Error, download, session::Session, url};

uniffi::setup_scaffolding!();

/// Which variant to pick; mirrors [`download::Quality`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Quality {
    Worst,
    Best,
    Height { height: u32 },
}

impl From<Quality> for download::Quality {
    fn from(quality: Quality) -> Self {
        match quality {
            Quality::Worst => Self::Worst,
            Quality::Best => Self::Best,
            Quality::Height { height } => Self::Height(height),
        }
    }
}

/// What a probe learns before a byte is written: enough to name the file
/// and size a progress bar.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct VideoInfo {
    pub title: String,
    pub width: u32,
    pub height: u32,
    pub segments: u32,
}

impl From<&download::Download> for VideoInfo {
    fn from(download: &download::Download) -> Self {
        Self {
            title: download.title.clone(),
            width: download.width,
            height: download.height,
            segments: download.segments.len() as u32,
        }
    }
}

/// Mirror of [`rutube_core::Error`] with every foreign payload flattened to a string.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum RutubeError {
    #[error("http request failed: {message}")]
    Http { message: String },
    #[error("unsupported url: {url}")]
    UnsupportedUrl { url: String },
    #[error("video not found or unavailable")]
    NotFound,
    #[error("access denied")]
    Forbidden,
    #[error("could not parse {what}: {detail}")]
    Parse { what: String, detail: String },
    #[error("cookie store: {message}")]
    Cookies { message: String },
    #[error("io: {message}")]
    Io { message: String },
    #[error("master playlist has no usable variants")]
    NoVariants,
    #[error("no {wanted}p variant; available: {available}")]
    NoSuchResolution { wanted: u32, available: String },
    #[error("stream is still live; try again once it has ended")]
    LiveStream,
    #[error("segments are {method} encrypted, which is not supported yet")]
    Encrypted { method: String },
    #[error("remux: {message}")]
    Remux { message: String },
}

impl From<Error> for RutubeError {
    fn from(error: Error) -> Self {
        match error {
            Error::Http(e) => Self::Http { message: e.to_string() },
            Error::UnsupportedUrl(url) => Self::UnsupportedUrl { url },
            Error::NotFound => Self::NotFound,
            Error::Forbidden => Self::Forbidden,
            Error::Parse { what, detail } => Self::Parse { what: what.to_owned(), detail },
            Error::Cookies(message) => Self::Cookies { message },
            Error::Io(e) => Self::Io { message: e.to_string() },
            Error::NoVariants => Self::NoVariants,
            Error::NoSuchResolution { wanted, available } => Self::NoSuchResolution { wanted, available },
            Error::LiveStream => Self::LiveStream,
            Error::Encrypted { method } => Self::Encrypted { method },
            Error::Remux(message) => Self::Remux { message },
        }
    }
}

/// This will be the listener of the download progress on the Kotlin side.
/// The `onProgress` implementation must touch UI only from the Kotlin's main thread,
/// not the current thread in the handler.
#[uniffi::export(with_foreign)]
pub trait ProgressListener: Send + Sync {
    fn on_progress(&self, done: u64, total: u64);
}

/// Entry point: one per app, holds the HTTP session and cookies.
#[derive(uniffi::Object)]
pub struct Client {
    session: Arc<Session>,
}

#[uniffi::export(async_runtime = "tokio")]
impl Client {
    #[uniffi::constructor]
    pub fn new() -> Result<Self, RutubeError> {
        Ok(Self { session: Arc::new(Session::new()?) })
    }

    /// Resolve a URL down to a segment list without writing a byte.
    pub async fn probe(&self, url: String, quality: Quality) -> Result<Download, RutubeError> {
        let video = url::parse(&url)?;
        let options = download::DownloadOptions { quality: quality.into(), ..Default::default() };
        let inner = download::Download::probe(Arc::clone(&self.session), &video, &options).await?;
        Ok(Download { inner })
    }
}

/// A probed video, ready to download. Wraps [`download::Download`].
#[derive(uniffi::Object)]
pub struct Download {
    inner: download::Download,
}

#[uniffi::export]
impl Download {
    pub fn info(&self) -> VideoInfo {
        VideoInfo::from(&self.inner)
    }
}
