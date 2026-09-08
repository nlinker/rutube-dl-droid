use std::future::Future;

use futures_util::{StreamExt, stream};

use crate::download::Sink;
use crate::progress::ProgressListener;
use crate::{Error, Result};

/// Fetch `total` items concurrently, write them to `sink` strictly in index order.
///
/// Note, the segments arrive in whatever order the network gives,
/// but a video is only valid if they are concatenated in sequence. That
/// guarantee comes from `futures_util::stream::stream::StreamExt::buffered` method.
pub async fn run<F, Fut>(
    total: usize,
    workers: usize,
    fetch: F,
    sink: &mut dyn Sink,
    progress: &dyn ProgressListener,
) -> Result<()>
where
    F: Fn(usize) -> Fut,
    Fut: Future<Output = Result<Vec<u8>>>,
{
    let mut ordered = stream::iter(0..total).map(&fetch).buffered(workers.max(1));
    let mut done = 0u64;
    while let Some(chunk) = ordered.next().await {
        sink.write_all(&chunk?)?;
        done += 1;
        progress.on_progress(done, total as u64);
    }
    if done != total as u64 {
        return Err(Error::parse("segment count", format!("{done} of {total} written")));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;
    use std::time::Duration;

    use super::*;
    use crate::progress::NoProgress;

    #[tokio::test]
    async fn write_in_order() {
        let mut out: Vec<u8> = Vec::new();
        let total = 8;

        // the sleep makes later fetches finish first
        run(
            total,
            4,
            |i| async move {
                tokio::time::sleep(Duration::from_millis((total - i) as u64 * 5)).await;
                Ok(vec![b'a' + i as u8])
            },
            &mut out,
            &NoProgress,
        )
        .await
        .unwrap();

        assert_eq!(out, b"abcdefgh");
    }

    #[tokio::test]
    async fn progress_counts_every_segment() {
        struct Seen(Mutex<Vec<u64>>);
        impl ProgressListener for Seen {
            fn on_progress(&self, done: u64, _total: u64) {
                self.0.lock().unwrap().push(done);
            }
        }

        let seen = Seen(Mutex::new(Vec::new()));
        let mut out: Vec<u8> = Vec::new();

        run(3, 2, |_| async { Ok(vec![0]) }, &mut out, &seen).await.unwrap();

        assert_eq!(*seen.0.lock().unwrap(), [1, 2, 3]);
    }

    #[tokio::test]
    async fn stop_on_error() {
        let mut out: Vec<u8> = Vec::new();

        let result = run(
            4,
            2,
            |i| async move {
                if i == 2 {
                    Err(Error::NotFound)
                } else {
                    Ok(vec![b'a' + i as u8])
                }
            },
            &mut out,
            &NoProgress,
        )
        .await;

        assert!(result.is_err());
        // everything before the failure is already written, nothing after.
        assert_eq!(out, b"ab");
    }
}
