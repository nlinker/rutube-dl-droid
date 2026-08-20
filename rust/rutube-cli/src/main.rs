use std::process::ExitCode;

use clap::{Parser, Subcommand};

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

async fn info(_input: &str) -> rutube_core::Result<()> {
    Ok(())
}

async fn download(_input: &str) -> rutube_core::Result<()> {
    Ok(())
}
