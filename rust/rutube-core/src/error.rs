use std::fmt::Display;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("http request failed: {0}")]
    Http(#[from] reqwest::Error),

    #[error("unsupported url: {0}")]
    UnsupportedUrl(String),

    #[error("video not found or unavailable")]
    NotFound,

    #[error("access denied")]
    Forbidden,

    #[error("could not parse {what}: {detail}")]
    Parse { what: &'static str, detail: String },

    #[error("cookie store: {0}")]
    Cookies(String),

    #[error("io: {0}")]
    Io(#[from] std::io::Error),

    #[error("master playlist has no usable variants")]
    NoVariants,

    #[error("no {wanted}p variant; available: {available}")]
    NoSuchResolution { wanted: u32, available: String },

    #[error("stream is still live; try again once it has ended")]
    LiveStream,

    #[error("segments are {method} encrypted, which is not supported yet")]
    Encrypted { method: String },

    #[error("remux: {0}")]
    Remux(String),
}

/// Here are some Error constructors for the convenience.
impl Error {
    pub(crate) fn parse(what: &'static str, detail: impl Display) -> Self {
        Self::Parse { what, detail: detail.to_string() }
    }

    pub(crate) fn cookies(detail: impl Display) -> Self {
        Self::Cookies(detail.to_string())
    }
}
