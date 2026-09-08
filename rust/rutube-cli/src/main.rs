use std::{fs::File, io::Write, path::PathBuf, process::ExitCode};

use clap::{Parser, Subcommand};
use itertools::Itertools;
use rutube_core::{
    api,
    download::{Download, DownloadOptions, Quality},
    hls,
    progress::ProgressListener,
    session::Session,
    url,
};

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
    /// Download a video as raw MPEG-TS.
    Dl {
        /// Rutube video URL.
        url: String,

        /// A height such as 720, or "best" / "worst".
        #[arg(short = 'y', long, default_value_t)]
        quality: Quality,

        /// Output path. Defaults to "{title} ({width}x{height}).ts".
        #[arg(short, long)]
        output: Option<PathBuf>,

        /// Segments to fetch at once.
        #[arg(short = 'j', long, default_value_t = 6)]
        workers: usize,
    },
}

#[tokio::main]
async fn main() -> ExitCode {
    let cli = Cli::parse();

    let result = match cli.command {
        Command::Info { url } => info(&url).await,
        Command::Dl { url, quality, output, workers } => download(&url, quality, output, workers).await,
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

async fn download(input: &str, quality: Quality, output: Option<PathBuf>, workers: usize) -> rutube_core::Result<()> {
    let video = url::parse(input)?;
    let session = Session::new()?;
    let options = DownloadOptions { quality, workers };

    let download = Download::probe(&session, &video, &options).await?;
    // The output is raw MPEG-TS, so it is named `name.ts` for now
    let path = output.unwrap_or_else(|| PathBuf::from(default_name(&download, "ts")));

    println!("{} ({}x{})", download.title, download.width, download.height);
    println!("{} segments -> {}", download.segments.len(), path.display());

    let mut file = File::create(&path)?;
    download.fetch(&mut file, &Bar).await?;

    println!("{}", path.display());
    Ok(())
}

fn default_name(download: &Download<'_>, ext: &str) -> String {
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
            ("hello 🎉🎉 world", "hello _ world"),
            ("family 👨‍👩‍👧 here", "family _ here"),
            // Leading and trailing junk is trimmed away entirely.
            ("🎉 hello 🎉", "hello"),
            ("///", ""),
            ("", ""),
        ];

        for (input, expected) in cases {
            assert_eq!(sanitize(input), expected, "{input:?}");
        }
    }
}
