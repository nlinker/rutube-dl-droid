use std::io::{Read, Seek, Write};

use crate::{Error, Result};

/// Rewrap TS as MP4, copying the elementary streams untouched. This is just a container change,
/// not a re-encode. `output` needs [`Seek`], because `moov` is written after `mdat` and the offsets
/// are patched afterward.
///
/// Memory: the input is read in 188-byte packets, but the demuxed samples are held
/// in memory until the whole stream is parsed, so peak usage depends on the size of the
/// video.
///
/// TODO revisit it with long recordings on Android.
pub fn to_mp4<R: Read, W: Write + Seek>(input: R, output: &mut W) -> Result<()> {
    ts_to_mp4::remux(input, output).map_err(|e| Error::Remux(e.to_string()))
}
