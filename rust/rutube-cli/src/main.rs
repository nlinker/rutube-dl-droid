use std::{
    fmt,
    fs::File,
    io::{BufReader, Write},
    path::{Path, PathBuf},
    process::ExitCode,
    str::FromStr,
    sync::Arc,
};

use clap::{Parser, Subcommand};
use itertools::Itertools;
use rutube_core::{
    api,
    download::{Download, DownloadOptions, Quality},
    hls,
    progress::ProgressListener,
    remux,
    session::Session,
    url,
};

/// Container to write. Segments arrive as MPEG-TS either way; `Mp4` repackages them.
/// `Ts` is for raw MPEG-TS, exactly as served.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
enum Format {
    #[default]
    Mp4,
    Ts,
}

impl Format {
    fn extension(self) -> &'static str {
        match self {
            Self::Mp4 => "mp4",
            Self::Ts => "ts",
        }
    }
}

impl fmt::Display for Format {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.extension())
    }
}

impl FromStr for Format {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "mp4" => Ok(Self::Mp4),
            "ts" => Ok(Self::Ts),
            other => Err(format!("expected \"mp4\" or \"ts\", got {other:?}")),
        }
    }
}

/// Punctuation kept as-is; everything outside this and letters, digits and spaces
/// is replaced. An allowlist rather than a list of forbidden characters, so emoji
/// and anything else exotic are covered without enumerating Unicode blocks.
const KEPT_PUNCTUATION: &str = "-_.,()[]'!";

#[derive(Parser)]
#[command(name = "rutube-cli", about = "Download videos from Rutube")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Show video metadata and available resolutions.
    Info {
        /// Rutube video URL.
        url: String,
    },
    /// Download a video.
    Dl {
        /// Rutube video URL.
        url: String,

        /// A height such as 720, or "best" / "worst".
        #[arg(short = 'y', long, default_value_t)]
        quality: Quality,

        /// Output path. Defaults to "{title} ({width}x{height}).{format}".
        #[arg(short, long)]
        output: Option<PathBuf>,

        /// Segments to fetch at once.
        #[arg(short = 'j', long, default_value_t = 6)]
        workers: usize,

        /// Container to write: "mp4" or "ts".
        #[arg(short, long, default_value_t)]
        format: Format,
    },
}

#[tokio::main]
async fn main() -> ExitCode {
    let cli = Cli::parse();

    let result = match cli.command {
        Command::Info { url } => info(&url).await,
        Command::Dl { url, quality, output, workers, format } => download(&url, quality, output, workers, format).await,
    };

    if let Err(error) = result {
        eprintln!("error: {error}");
        return ExitCode::FAILURE;
    }

    ExitCode::SUCCESS
}

async fn info(input: &str) -> rutube_core::Result<()> {
    let video = url::parse(input)?;
    let session = Session::new()?;
    let options = api::play_options(&session, &video).await?;

    let master = session
        .http()
        .get(&options.video_balancer.m3u8)
        .send()
        .await?
        .error_for_status()?
        .text()
        .await?;
    let variants = hls::parse_master(&master)?;

    println!("Title:  {}", options.title.as_deref().unwrap_or(&video.id));
    println!("Kind:   {}", video.kind.as_str());
    println!("ID:     {}", video.id);
    println!();

    for variant in &variants {
        let reserve = if variant.reserve_uri.is_some() { "yes" } else { "no" };
        println!("{:>4}x{:<4}  (reserve: {reserve})", variant.width, variant.height);
    }
    Ok(())
}

async fn download(
    input: &str,
    quality: Quality,
    output: Option<PathBuf>,
    workers: usize,
    format: Format,
) -> rutube_core::Result<()> {
    let video = url::parse(input)?;
    let session = Arc::new(Session::new()?);
    let options = DownloadOptions { quality, workers };

    let download = Download::probe(session, &video, &options).await?;
    let path = output.unwrap_or_else(|| PathBuf::from(default_name(&download, format.extension())));

    println!("{} ({}x{})", download.title, download.width, download.height);
    println!("{} segments -> {}", download.segments.len(), path.display());

    // Segments are MPEG-TS whatever the target container, so they always land in a
    // file first. For `ts` that file is the output; for `mp4` it is a scratch file,
    // that is remuxed later.
    let ts_path = match format {
        Format::Ts => path.clone(),
        Format::Mp4 => scratch_path(&path),
    };

    let mut file = File::create(&ts_path)?;
    download.fetch(&mut file, &Bar).await?;
    drop(file);

    if format == Format::Mp4 {
        if let Err(error) = repackage(&ts_path, &path) {
            // Keep the TS: it is still watchable, and it is what a bug report needs.
            eprintln!("remux failed, keeping {}", ts_path.display());
            return Err(error);
        }
        std::fs::remove_file(&ts_path)?;
    }

    println!("{}", path.display());
    Ok(())
}

fn repackage(ts_path: &Path, mp4_path: &Path) -> rutube_core::Result<()> {
    let input = BufReader::new(File::open(ts_path)?);
    let mut output = File::create(mp4_path)?;
    remux::to_mp4(input, &mut output)
}

/// A scratch name for the file, that placed next to the output,
/// so the later the deleting is cheap
fn scratch_path(output: &Path) -> PathBuf {
    let mut name = output.as_os_str().to_owned();
    name.push(".part.ts");
    PathBuf::from(name)
}

fn default_name(download: &Download, ext: &str) -> String {
    let title = sanitize(&download.title);
    let title = if title.is_empty() { "video" } else { &title };

    format!("{title} ({}x{}).{ext}", download.width, download.height)
}

/// Replace anything unsafe in a file name with `_`, collapsing runs.
fn sanitize(title: &str) -> String {
    title
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || c == ' ' || KEPT_PUNCTUATION.contains(c) {
                c
            } else {
                '_'
            }
        })
        .dedup_by(|a, b| *a == '_' && *b == '_')
        .collect::<String>()
        .trim_matches(|c: char| c == '_' || c.is_whitespace())
        .to_owned()
}

struct Bar;

impl ProgressListener for Bar {
    fn on_progress(&self, done: u64, total: u64) {
        eprint!("\r{done}/{total} segments");
        let _ = std::io::stderr().flush();
        if done == total {
            eprintln!();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_titles() {
        let cases = [
            ("Nature 4k", "Nature 4k"),
            ("Тест видео", "Тест видео"),
            // Path separators and other reserved characters.
            ("a/b:c*d?e", "a_b_c_d_e"),
            // Emoji, including a multi-codepoint sequence, collapse to one `_`.
            ("hello 👋👋 world", "hello _ world"),
            ("Elden 🎮 Ring", "Elden _ Ring"),
            // Leading and trailing junk is trimmed away entirely.
            ("👋 hello 👋", "hello"),
            ("///", ""),
            ("", ""),
        ];

        for (input, expected) in cases {
            assert_eq!(sanitize(input), expected, "{input:?}");
        }
    }
}
