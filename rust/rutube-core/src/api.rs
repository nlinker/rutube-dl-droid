use serde::Deserialize;

use crate::{Error, Result, session::Session, url::VideoRef};

/// Single-encoded referer, as used by ps-rutube-downloader (newest reference).
const REFERER: &str = "https%3A%2F%2Frutube.ru";

#[derive(Debug, Deserialize)]
pub struct PlayOptions {
    pub title: Option<String>,
    pub video_balancer: VideoBalancer,
}

#[derive(Debug, Deserialize)]
pub struct VideoBalancer {
    /// Master HLS playlist URL.
    pub m3u8: String,
}

fn play_options_url(video: &VideoRef) -> String {
    let mut url = format!(
        "https://rutube.ru/api/play/options/{}/?no_404=true&referer={REFERER}&pver=v2",
        video.id
    );
    // Forwarding the original token to access private videos
    if let Some(token) = &video.token {
        url.push_str("&p=");
        url.push_str(token);
    }
    url
}

pub async fn play_options(session: &Session, video: &VideoRef) -> Result<PlayOptions> {
    let response = session.http().get(play_options_url(video)).send().await?;

    match response.status() {
        reqwest::StatusCode::NOT_FOUND => return Err(Error::NotFound),
        reqwest::StatusCode::UNAUTHORIZED | reqwest::StatusCode::FORBIDDEN => {
            return Err(Error::Forbidden);
        }
        _ => {}
    }

    let body = response.error_for_status()?.text().await?;
    serde_json::from_str(&body).map_err(|e| Error::parse("play options response", e))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::url;

    const ID: &str = "5c5f0ae2d9744d11a05b76bd327cbb51";

    #[test]
    fn url_has_no_query_for_public_videos() {
        let video = url::parse(&format!("https://rutube.ru/video/{ID}/")).unwrap();
        assert_eq!(
            play_options_url(&video),
            format!("https://rutube.ru/api/play/options/{ID}/?no_404=true&referer={REFERER}&pver=v2")
        );
    }

    #[test]
    fn private_token_is_appended_verbatim() {
        let video = url::parse(&format!("https://rutube.ru/video/private/{ID}/?p=TOKEN")).unwrap();
        assert!(play_options_url(&video).ends_with("&pver=v2&p=TOKEN"));
    }
}
