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
use rutube_core::{
    download::{Download, DownloadOptions, Quality},
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

        /// A height such as 720, "~720" for the highest not above it, or "best" / "worst".
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
    let session = Arc::new(Session::new()?);
    let download = Download::probe(session, &video, &DownloadOptions::default()).await?;

    println!("Title:    {}", download.title);
    println!("Kind:     {}", video.kind.as_str());
    println!("ID:       {}", video.id);
    println!("Duration: {:.0} s", download.duration);
    println!();

    for variant in &download.variants {
        let reserve = if variant.reserve_uri.is_some() { "yes" } else { "no" };
        let size = variant.estimated_size(download.duration) as f64 / 1e6;
        println!("{:>4}x{:<4}  ~{size:.0} MB  (reserve: {reserve})", variant.width, variant.height);
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
    let path = output.unwrap_or_else(|| PathBuf::from(download.file_name(format.extension())));

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
