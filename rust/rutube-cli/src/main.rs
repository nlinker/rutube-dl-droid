use std::process::ExitCode;

use clap::{Parser, Subcommand};
use rutube_core::{api, hls, session::Session, url};

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
    Dl {
        /// Rutube video URL.
        url: String,
    },
}

#[tokio::main]
async fn main() -> ExitCode {
    let cli = Cli::parse();

    let result = match cli.command {
        Command::Info { url } => info(&url).await,
        Command::Dl { url } => download(&url).await,
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

async fn download(_input: &str) -> rutube_core::Result<()> {
    Ok(())
}
