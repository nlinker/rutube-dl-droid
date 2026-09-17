use std::{
    fs::File,
    io::{BufReader, Seek, SeekFrom},
    path::Path,
    sync::Arc,
};

use rutube_core::{Error, download, progress, remux, session::Session, url};

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
///
/// The `title` is the raw video title (with emoji and other stuff), but
/// `file_name` is `{sanitized(title)} ({width}x{height}).mp4`,
/// so the `file_name` is ready for `createDocument`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct VideoInfo {
    pub title: String,
    pub width: u32,
    pub height: u32,
    pub segments: u32,
    pub file_name: String,
}

impl From<&download::Download> for VideoInfo {
    fn from(download: &download::Download) -> Self {
        Self {
            title: download.title.clone(),
            width: download.width,
            height: download.height,
            segments: download.segments.len() as u32,
            file_name: download.file_name("mp4"),
        }
    }
}

/// Mirror of [`rutube_core::Error`] with every foreign payload flattened to a string.
/// The payload is `detail`, not `message`: the generated Kotlin class extends
/// `kotlin.Exception`, and its `message` field would clash otherwise.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum RutubeError {
    #[error("http request failed: {detail}")]
    Http { detail: String },
    #[error("unsupported url: {url}")]
    UnsupportedUrl { url: String },
    #[error("video not found or unavailable")]
    NotFound,
    #[error("access denied")]
    Forbidden,
    #[error("could not parse {what}: {detail}")]
    Parse { what: String, detail: String },
    #[error("cookie store: {detail}")]
    Cookies { detail: String },
    #[error("io: {detail}")]
    Io { detail: String },
    #[error("master playlist has no usable variants")]
    NoVariants,
    #[error("no {wanted}p variant; available: {available}")]
    NoSuchResolution { wanted: u32, available: String },
    #[error("stream is still live; try again once it has ended")]
    LiveStream,
    #[error("segments are {method} encrypted, which is not supported yet")]
    Encrypted { method: String },
    #[error("remux: {detail}")]
    Remux { detail: String },
}

impl From<Error> for RutubeError {
    fn from(error: Error) -> Self {
        match error {
            Error::Http(e) => Self::Http { detail: e.to_string() },
            Error::UnsupportedUrl(url) => Self::UnsupportedUrl { url },
            Error::NotFound => Self::NotFound,
            Error::Forbidden => Self::Forbidden,
            Error::Parse { what, detail } => Self::Parse { what: what.to_owned(), detail },
            Error::Cookies(detail) => Self::Cookies { detail },
            Error::Io(e) => Self::Io { detail: e.to_string() },
            Error::NoVariants => Self::NoVariants,
            Error::NoSuchResolution { wanted, available } => Self::NoSuchResolution { wanted, available },
            Error::LiveStream => Self::LiveStream,
            Error::Encrypted { method } => Self::Encrypted { method },
            Error::Remux(detail) => Self::Remux { detail },
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

#[uniffi::export(async_runtime = "tokio")]
impl Download {
    pub fn info(&self) -> VideoInfo {
        VideoInfo::from(&self.inner)
    }

    /// Download and remux into an already open file descriptor.
    ///
    /// Rust takes ownership of `fd` and closes it when done, so detach it on the
    /// Kotlin side with `ParcelFileDescriptor.detachFd()`, never `getFd()`. Open
    /// the file in `"rw"` mode: the remux seeks back over what it wrote.
    ///
    /// Segments are saved in a scratch file under `scratch_dir` first.
    /// Pass the `cacheDir` of the Android app, because the
    /// system temp dir is not writable on Android.
    pub async fn save(&self, fd: i32, scratch_dir: String, listener: Arc<dyn ProgressListener>) -> Result<(), RutubeError> {
        self.save_into(file_from_fd(fd)?, Path::new(&scratch_dir), listener).await
    }
}

impl Download {
    /// The part of [`Download::save`] that does not touch raw descriptors.
    /// Reachable from Rust hosts and tests; Kotlin only sees `save`.
    pub async fn save_into(
        &self,
        mut output: File,
        scratch_dir: &Path,
        listener: Arc<dyn ProgressListener>,
    ) -> Result<(), RutubeError> {
        let mut scratch = tempfile::tempfile_in(scratch_dir).map_err(Error::Io)?;
        self.inner.fetch(&mut scratch, &Forward(listener)).await?;
        scratch.seek(SeekFrom::Start(0)).map_err(Error::Io)?;

        // `set_len` is needed, because the caller may have opened an existing file without truncating it.
        output.set_len(0).map_err(Error::Io)?;
        // remux::to_mp4 is I/O sync, that's why it needs spawn_blocking.
        tokio::task::spawn_blocking(move || remux::to_mp4(BufReader::new(scratch), &mut output))
            .await
            .map_err(|e| RutubeError::Remux { detail: e.to_string() })??;
        Ok(())
    }
}

#[cfg(unix)]
fn file_from_fd(fd: i32) -> Result<File, RutubeError> {
    use std::os::fd::FromRawFd;
    // SAFETY: the caller detached `fd`, so nothing else closes or writes through
    // it, and this `File` becomes its sole owner.
    Ok(unsafe { File::from_raw_fd(fd) })
}

#[cfg(not(unix))]
fn file_from_fd(_fd: i32) -> Result<File, RutubeError> {
    Err(RutubeError::Io { detail: "file descriptors are only supported on unix".to_owned() })
}

/// Forwards core progress to the foreign listener. This is a newtype because the core
/// trait cannot be implemented for `Arc<dyn ProgressListener>` directly.
struct Forward(Arc<dyn ProgressListener>);

impl progress::ProgressListener for Forward {
    fn on_progress(&self, done: u64, total: u64) {
        self.0.on_progress(done, total);
    }
}
