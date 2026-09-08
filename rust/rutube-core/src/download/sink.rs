use std::io::Write;

use crate::Result;

/// Where downloaded bytes go. Never a path.
///
/// The CLI passes a `File`. Android passes a descriptor obtained from SAF, which
/// becomes a `File` through `File::from_raw_fd` — Rust owns and closes it there.
pub trait Sink: Send {
    fn write_all(&mut self, buf: &[u8]) -> Result<()>;
}

impl<W: Write + Send> Sink for W {
    fn write_all(&mut self, buf: &[u8]) -> Result<()> {
        Write::write_all(self, buf)?;
        Ok(())
    }
}
