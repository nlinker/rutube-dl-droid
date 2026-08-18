use clap::Parser;

#[derive(Parser)]
#[command(name = "rutube-cli", about = "Download videos from Rutube")]
struct Cli {}

#[tokio::main]
async fn main() {
    let _cli = Cli::parse();
}

