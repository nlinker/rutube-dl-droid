use crate::{Error, Result};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoKind {
    Video,
    Shorts,
    Yappy,
    Private,
}

impl VideoKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Video => "Video",
            Self::Shorts => "Shorts",
            Self::Yappy => "Yappy",
            Self::Private => "Private",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VideoRef {
    pub kind: VideoKind,
    /// Video id: exactly 32 lowercase hex digits.
    pub id: String,
    /// Access token from the `?p=` query parameter, percent-decoded.
    pub token: Option<String>,
}

/// Every Rutube id observed so far is exactly 32 lowercase hex digits — a 128-bit
/// value rendered like an MD5 sum.
fn is_video_id(id: &str) -> bool {
    id.len() == 32 && id.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

pub fn parse(input: &str) -> Result<VideoRef> {
    let unsupported = || Error::UnsupportedUrl(input.to_string());
    let url = url::Url::parse(input).map_err(|_| unsupported())?;

    match url.host_str() {
        Some(host) if host == "rutube.ru" || host.ends_with(".rutube.ru") => {}
        _ => return Err(unsupported()),
    }

    let segments: Vec<&str> = url
        .path_segments()
        .map(|s| s.filter(|p: &&str| !p.is_empty()).collect())
        .unwrap_or_default();

    // Order matters: `/video/private/<id>` must be tried before `/video/<id>`
    let (kind, id) = match segments.as_slice() {
        ["video", "private", id, ..] => (VideoKind::Private, *id),
        ["video", id, ..] => (VideoKind::Video, *id),
        ["shorts", id, ..] => (VideoKind::Shorts, *id),
        ["yappy", id, ..] => (VideoKind::Yappy, *id),
        _ => return Err(unsupported()),
    };

    if !is_video_id(id) {
        return Err(unsupported());
    }

    // A real private share link has p=<token> parameter
    let token = url
        .query_pairs()
        .find(|(key, _)| key == "p")
        .map(|(_, value)| value.into_owned());

    Ok(VideoRef {
        kind,
        id: id.to_string(),
        token,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A real public video id.
    const ID: &str = "5c5f0ae2d9744d11a05b76bd327cbb51";

    fn do_parse(input: &str) -> VideoRef {
        parse(input).expect("should parse")
    }

    #[test]
    fn accept_known_urls() {
        let cases = [
            (format!("https://rutube.ru/video/{ID}/"), VideoKind::Video),
            (format!("https://rutube.ru/video/{ID}"), VideoKind::Video), // no trailing slash
            (format!("https://www.rutube.ru/video/{ID}/"), VideoKind::Video), // subdomain
            (format!("https://rutube.ru/shorts/{ID}/"), VideoKind::Shorts),
            (format!("https://rutube.ru/yappy/{ID}/"), VideoKind::Yappy),
            (
                format!("https://rutube.ru/video/private/{ID}/"),
                VideoKind::Private,
            ),
        ];
        for (url, kind) in cases {
            let v = do_parse(&url);
            assert_eq!(v.kind, kind, "{url}");
            assert_eq!(v.id, ID, "{url}");
            assert_eq!(v.token, None, "{url}");
        }
    }

    #[test]
    fn parse_private_url() {
        let v = do_parse(
            "https://rutube.ru/video/private/56058c9669a49d153cd382e00c1d558e/?r=a&p=Kv8E1wKob-5-JCPpTQ1BGg",
        );
        assert_eq!(v.kind, VideoKind::Private);
        assert_eq!(v.id, "56058c9669a49d153cd382e00c1d558e");
        assert_eq!(v.token.as_deref(), Some("Kv8E1wKob-5-JCPpTQ1BGg"));

        // Tracking parameters alone leave no token behind.
        let v = do_parse(&format!("https://rutube.ru/video/{ID}/?utm_source=vk&t=120"));
        assert_eq!(v.token, None);

        // The token arrives percent-decoded, as a value rather than a wire fragment.
        let v = do_parse(&format!("https://rutube.ru/video/private/{ID}/?p=a%2Bb%3Dc"));
        assert_eq!(v.token.as_deref(), Some("a+b=c"));
    }

    #[test]
    fn reject_unrecognized() {
        let bad: Vec<String> = vec![
            "not a url".into(),
            format!("https://youtube.com/watch?v={ID}"),
            format!("https://notrutube.ru/video/{ID}/"),
            "https://rutube.ru/".into(),
            "https://rutube.ru/channel/12345/".into(),
            // A bare `/private/<id>` is rejected on purpose. The only thing that
            // ever suggested this form was a match pattern in one Chrome
            // extension, and a real share link turned out to be
            // `/video/private/<id>`. Accepting a shape we have no evidence for
            // would trade a clear "unsupported url" here for a confusing API
            // failure later.
            format!("https://rutube.ru/private/{ID}/"),
            // Path traversal in place of an id.
            "https://rutube.ru/video/../etc/".into(),
            // Ids that are not 32 lowercase hex digits.
            "https://rutube.ru/video/abc123/".into(),
            "https://rutube.ru/video/5c5f0ae2d9744d11a05b76bd327cbb5/".into(),
            "https://rutube.ru/video/5c5f0ae2d9744d11a05b76bd327cbb511/".into(),
            "https://rutube.ru/video/5C5F0AE2D9744D11A05B76BD327CBB51/".into(),
            "https://rutube.ru/video/5c5f0ae2d9744d11a05b76bd327cbb5g/".into(),
        ];
        for url in bad {
            assert!(parse(&url).is_err(), "should have rejected {url}");
        }
    }
}
