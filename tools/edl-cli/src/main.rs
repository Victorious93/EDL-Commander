//! Developer CLI. Read-only: validates the database and runs the simulator.
//! It performs no USB I/O and cannot write to any device.

use edl_core::device::Database;
use edl_core::diagnostics::DiagLog;
use edl_core::protocol::sahara::{run_programmer_upload, SessionOptions};
use edl_sim::{Fault, SimulatedEdlDevice};
use std::path::Path;
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("validate-db") => {
            let dir = args.get(1).map_or("database", String::as_str);
            match Database::load(Path::new(dir)) {
                Ok(db) => {
                    println!(
                        "OK: {} manufacturers, {} chipsets, {} programmers, {} devices",
                        db.manufacturers.len(),
                        db.chipsets.len(),
                        db.programmers.len(),
                        db.devices.len()
                    );
                    ExitCode::SUCCESS
                }
                Err(e) => {
                    eprintln!("{e}");
                    ExitCode::FAILURE
                }
            }
        }
        Some("sim") => {
            let fault = match args.get(1).map(String::as_str) {
                None | Some("none") => Fault::None,
                Some("silent") => Fault::Silent,
                Some("malformed") => Fault::MalformedHello,
                Some("disconnect") => Fault::DisconnectAfterBytes(4096),
                Some(o) => {
                    eprintln!("unknown fault '{o}' (none|silent|malformed|disconnect)");
                    return ExitCode::from(2);
                }
            };
            let image = vec![0xA5u8; 16 * 1024];
            let mut dev = SimulatedEdlDevice::new(image.len(), 4096, fault);
            let mut log = DiagLog::new();
            let r = run_programmer_upload(&mut dev, &image, &SessionOptions::default(), &mut log);
            for e in &log.events {
                println!("[{:?}] {}: {}", e.level, e.code, e.message);
            }
            match r {
                Ok(rep) => {
                    println!("SIMULATED upload acknowledged: {rep:?} (not hardware evidence)");
                    ExitCode::SUCCESS
                }
                Err(e) => {
                    println!("SIMULATED upload failed: {e}");
                    ExitCode::FAILURE
                }
            }
        }
        _ => {
            eprintln!("usage: edl-cli validate-db [dir] | sim [none|silent|malformed|disconnect]");
            ExitCode::from(2)
        }
    }
}
