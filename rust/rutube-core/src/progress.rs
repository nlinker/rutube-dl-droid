/// Download progress, reported as segments complete.
/// Android receives this as a UniFFI callback interface.
pub trait ProgressListener: Send + Sync {
    fn on_progress(&self, done: u64, total: u64);
}

/// Discards progress, for callers that do not care.
pub struct NoProgress;

impl ProgressListener for NoProgress {
    fn on_progress(&self, _done: u64, _total: u64) {}
}
