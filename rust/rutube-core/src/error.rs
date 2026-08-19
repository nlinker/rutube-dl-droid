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
}
