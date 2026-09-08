use std::sync::Arc;

use reqwest_cookie_store::{CookieStore, CookieStoreMutex};

use crate::{Error, Result};

/// Pretend to be a normal browser.
const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) \
     AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

pub struct Session {
    http: reqwest::Client,
    jar: Arc<CookieStoreMutex>,
}

impl Session {
    pub fn new() -> Result<Self> {
        Self::build(CookieStore::default())
    }

    /// Rebuild a session from the JSON produced by [`Session::export_cookies`].
    pub fn restore(cookies_json: &str) -> Result<Self> {
        let store = cookie_store::serde::json::load(cookies_json.as_bytes()).map_err(Error::cookies)?;
        Self::build(store)
    }

    fn build(store: CookieStore) -> Result<Self> {
        let jar = Arc::new(CookieStoreMutex::new(store));
        let http = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .cookie_provider(Arc::clone(&jar))
            .build()?;
        Ok(Self { http, jar })
    }

    pub fn http(&self) -> &reqwest::Client {
        &self.http
    }

    /// Serialize unexpired, persistent cookies. Pairs with [`Session::restore`].
    pub fn export_cookies(&self) -> Result<String> {
        let store = self.jar.lock().map_err(Error::cookies)?;
        let mut buf = Vec::new();
        cookie_store::serde::json::save(&store, &mut buf).map_err(Error::cookies)?;
        String::from_utf8(buf).map_err(Error::cookies)
    }
}
