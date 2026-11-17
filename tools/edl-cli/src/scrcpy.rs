//! Optional host-side scrcpy launcher.
//!
//! This module talks only to host `adb` and `scrcpy` binaries. It never opens a
//! USB device, never speaks Sahara or Firehose, and never calls
//! `security::authorize` or any write gate. It is for viewing a phone once that
//! phone is already back in normal Android / ADB mode during recovery — not for
//! Qualcomm EDL (USB 9008) sessions.

use edl_core::diagnostics::audit::AuditLog;
use edl_core::diagnostics::redact::device_detail;
use std::env;
use std::ffi::OsString;
use std::fmt;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode, Stdio};
use std::time::{SystemTime, UNIX_EPOCH};

const SCRCPY_PROJECT: &str = "https://github.com/Genymobile/scrcpy";

pub fn main_from_args(args: &[String]) -> ExitCode {
    match parse_args(args) {
        Ok(opts) => match run(&opts, &Host::from_env()) {
            Ok(outcome) => {
                print_outcome(&outcome);
                if let ScrcpyOutcome::Launched { status } = outcome {
                    if status != 0 {
                        return ExitCode::FAILURE;
                    }
                }
                ExitCode::SUCCESS
            }
            Err(e) => {
                eprintln!("{e}");
                ExitCode::FAILURE
            }
        },
        Err(e) => {
            eprintln!("{e}");
            eprintln!("usage: edl-cli scrcpy [--serial X] [--dry-run] [-- extra args]");
            ExitCode::from(2)
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Options {
    pub serial: Option<String>,
    pub dry_run: bool,
    pub extra: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct Host {
    pub path: OsString,
    pub scrcpy_bin: Option<PathBuf>,
    pub audit_log: Option<PathBuf>,
}

impl Host {
    pub fn from_env() -> Self {
        Self {
            path: env::var_os("PATH").unwrap_or_default(),
            scrcpy_bin: env::var_os("SCRCPY_BIN").map(PathBuf::from),
            audit_log: env::var_os("EDL_AUDIT_LOG").map(PathBuf::from),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ScrcpyOutcome {
    DryRun { command: String },
    Launched { status: i32 },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ScrcpyError {
    Usage(String),
    ScrcpyNotInstalled { scrcpy_bin_set: bool },
    AdbMissing,
    NoDevice,
    NotReady { serial: String, state: String },
    SerialNotFound { serial: String },
    Ambiguous { ready: Vec<String> },
    AdbFailed { message: String },
    LaunchFailed { message: String },
}

impl fmt::Display for ScrcpyError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ScrcpyError::Usage(msg) => write!(f, "{msg}"),
            ScrcpyError::ScrcpyNotInstalled { scrcpy_bin_set } => {
                if *scrcpy_bin_set {
                    write!(
                        f,
                        "SCRCPY_BIN is set but does not point to a usable scrcpy executable.\n\
                         Install scrcpy from {SCRCPY_PROJECT} or set SCRCPY_BIN to the executable.\n\
                         scrcpy is optional and is not bundled with EDL-Commander."
                    )
                } else {
                    write!(
                        f,
                        "scrcpy is not installed (not found on PATH).\n\
                         Install it from {SCRCPY_PROJECT} and make sure it is on PATH, \
                         or set SCRCPY_BIN to the executable.\n\
                         scrcpy is optional and is not bundled with EDL-Commander."
                    )
                }
            }
            ScrcpyError::AdbMissing => write!(
                f,
                "adb was not found on PATH. The scrcpy command only runs when a device is already \
                 in normal ADB mode; it does not talk to EDL (USB 9008) devices.\n\
                 Install Android platform-tools and retry."
            ),
            ScrcpyError::NoDevice => write!(
                f,
                "No device listed by `adb devices`. Connect a device in normal Android / ADB mode \
                 (not EDL / Qualcomm 9008) and authorize this computer if prompted."
            ),
            ScrcpyError::NotReady { serial, state } => match state.as_str() {
                "unauthorized" => write!(
                    f,
                    "Device {serial} is unauthorized. Unlock the device and accept the ADB \
                     authorization prompt, then retry."
                ),
                "offline" => write!(
                    f,
                    "Device {serial} is offline. Reconnect it in normal ADB mode and retry."
                ),
                other => write!(
                    f,
                    "Device {serial} is in state `{other}`, not normal ADB mode (`device`). \
                     The scrcpy command only runs once the phone is back in normal Android / ADB \
                     mode; it does not talk to EDL (USB 9008) devices."
                ),
            },
            ScrcpyError::SerialNotFound { serial } => write!(
                f,
                "Device {serial} was not listed by `adb devices`. Check the serial, or connect \
                 the device in normal ADB mode (not EDL / Qualcomm 9008)."
            ),
            ScrcpyError::Ambiguous { ready } => write!(
                f,
                "Several ADB devices are ready ({}). Pass --serial to choose one.",
                ready.join(", ")
            ),
            ScrcpyError::AdbFailed { message } => write!(f, "{message}"),
            ScrcpyError::LaunchFailed { message } => {
                write!(f, "failed to launch scrcpy: {message}")
            }
        }
    }
}

pub fn parse_args(args: &[String]) -> Result<Options, ScrcpyError> {
    let mut serial = None;
    let mut dry_run = false;
    let mut extra = Vec::new();
    let mut i = 0;
    while i < args.len() {
        let a = &args[i];
        if a == "--" {
            extra.extend(args[i + 1..].iter().cloned());
            break;
        }
        if a == "--dry-run" {
            dry_run = true;
            i += 1;
            continue;
        }
        if a == "--serial" || a == "-s" {
            let value = args
                .get(i + 1)
                .ok_or_else(|| ScrcpyError::Usage(format!("{a} requires a device serial")))?;
            if value.starts_with('-') {
                return Err(ScrcpyError::Usage(format!("{a} requires a device serial")));
            }
            serial = Some(value.clone());
            i += 2;
            continue;
        }
        if let Some(value) = a.strip_prefix("--serial=") {
            if value.is_empty() {
                return Err(ScrcpyError::Usage(
                    "--serial requires a device serial".into(),
                ));
            }
            serial = Some(value.to_string());
            i += 1;
            continue;
        }
        return Err(ScrcpyError::Usage(format!("unknown argument: {a}")));
    }
    Ok(Options {
        serial,
        dry_run,
        extra,
    })
}

pub fn run(opts: &Options, host: &Host) -> Result<ScrcpyOutcome, ScrcpyError> {
    let scrcpy_bin_set = host.scrcpy_bin.is_some();
    let scrcpy = match resolve_scrcpy(host) {
        Some(p) => p,
        None => {
            let err = ScrcpyError::ScrcpyNotInstalled { scrcpy_bin_set };
            record_audit(host, "scrcpy.launch", "error", "scrcpy not installed");
            return Err(err);
        }
    };

    let adb = match find_on_path("adb", &host.path) {
        Some(p) => p,
        None => {
            record_audit(host, "scrcpy.launch", "error", "adb not found");
            return Err(ScrcpyError::AdbMissing);
        }
    };

    let devices = match list_adb_devices(&adb, host) {
        Ok(d) => d,
        Err(e) => {
            record_audit(host, "scrcpy.launch", "error", "adb devices failed");
            return Err(e);
        }
    };

    let serial = match pick_serial(opts.serial.as_deref(), &devices) {
        Ok(s) => s,
        Err(e) => {
            let detail = audit_detail_for_error(&e);
            record_audit(host, "scrcpy.launch", "denied", &detail);
            return Err(e);
        }
    };

    let mut args = vec!["-s".to_string(), serial.clone()];
    args.extend(opts.extra.iter().cloned());
    let command = format_command(&scrcpy, &args);
    let detail = device_detail(&serial);

    if opts.dry_run {
        record_audit(host, "scrcpy.launch", "dry_run", &detail);
        return Ok(ScrcpyOutcome::DryRun { command });
    }

    match launch_scrcpy(&scrcpy, &args, host) {
        Ok(status) => {
            let outcome = if status == 0 { "ok" } else { "error" };
            record_audit(host, "scrcpy.launch", outcome, &detail);
            Ok(ScrcpyOutcome::Launched { status })
        }
        Err(e) => {
            record_audit(host, "scrcpy.launch", "error", &detail);
            Err(e)
        }
    }
}

fn print_outcome(outcome: &ScrcpyOutcome) {
    match outcome {
        ScrcpyOutcome::DryRun { command } => {
            println!("dry-run: {command}");
        }
        ScrcpyOutcome::Launched { status } if *status != 0 => {
            eprintln!("scrcpy exited with status {status}");
        }
        ScrcpyOutcome::Launched { .. } => {}
    }
}

fn audit_detail_for_error(err: &ScrcpyError) -> String {
    match err {
        ScrcpyError::NoDevice => "no adb device".into(),
        ScrcpyError::NotReady { serial, state } => {
            format!("{} state={state}", device_detail(serial))
        }
        ScrcpyError::SerialNotFound { serial } => {
            format!("{} not listed", device_detail(serial))
        }
        ScrcpyError::Ambiguous { ready } => {
            let tags: Vec<String> = ready.iter().map(|s| device_detail(s)).collect();
            format!("ambiguous {}", tags.join(" "))
        }
        _ => "denied".into(),
    }
}

fn resolve_scrcpy(host: &Host) -> Option<PathBuf> {
    if let Some(p) = &host.scrcpy_bin {
        return is_executable(p).then(|| p.clone());
    }
    find_on_path("scrcpy", &host.path)
}

fn find_on_path(name: &str, path: &OsString) -> Option<PathBuf> {
    for dir in env::split_paths(path) {
        if dir.as_os_str().is_empty() {
            continue;
        }
        let candidate = dir.join(name);
        if is_executable(&candidate) {
            return Some(candidate);
        }
        #[cfg(windows)]
        {
            let exe = dir.join(format!("{name}.exe"));
            if is_executable(&exe) {
                return Some(exe);
            }
        }
    }
    None
}

fn is_executable(path: &Path) -> bool {
    if !path.is_file() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        path.metadata()
            .map(|m| m.permissions().mode() & 0o111 != 0)
            .unwrap_or(false)
    }
    #[cfg(not(unix))]
    {
        true
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct AdbDevice {
    serial: String,
    state: String,
}

fn list_adb_devices(adb: &Path, host: &Host) -> Result<Vec<AdbDevice>, ScrcpyError> {
    let output = Command::new(adb)
        .arg("devices")
        .env("PATH", &host.path)
        .output()
        .map_err(|e| ScrcpyError::AdbFailed {
            message: format!("failed to run adb devices: {e}"),
        })?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        let detail = stderr.trim();
        let fallback = stdout.trim();
        let msg = if !detail.is_empty() {
            detail.to_string()
        } else if !fallback.is_empty() {
            fallback.to_string()
        } else {
            format!(
                "adb devices exited with status {}",
                output.status.code().unwrap_or(-1)
            )
        };
        return Err(ScrcpyError::AdbFailed { message: msg });
    }
    parse_adb_devices(&String::from_utf8_lossy(&output.stdout))
}

fn parse_adb_devices(text: &str) -> Result<Vec<AdbDevice>, ScrcpyError> {
    let mut devices = Vec::new();
    let mut seen_header = false;
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() {
            continue;
        }
        if !seen_header {
            if line.eq_ignore_ascii_case("list of devices attached") {
                seen_header = true;
            }
            // Ignore banner / version lines before the standard header.
            continue;
        }
        if line.starts_with('*') {
            continue;
        }
        let mut parts = line.split_whitespace();
        let Some(serial) = parts.next() else {
            continue;
        };
        let Some(state) = parts.next() else {
            continue;
        };
        devices.push(AdbDevice {
            serial: serial.to_string(),
            state: state.to_string(),
        });
    }
    // Some stripped test stubs print only the rows. Accept that too if the
    // header was missing but the lines look like adb rows.
    if !seen_header {
        devices.clear();
        for raw in text.lines() {
            let line = raw.trim();
            if line.is_empty() || line.eq_ignore_ascii_case("list of devices attached") {
                continue;
            }
            let mut parts = line.split_whitespace();
            let (Some(serial), Some(state)) = (parts.next(), parts.next()) else {
                continue;
            };
            devices.push(AdbDevice {
                serial: serial.to_string(),
                state: state.to_string(),
            });
        }
    }
    Ok(devices)
}

fn pick_serial(requested: Option<&str>, devices: &[AdbDevice]) -> Result<String, ScrcpyError> {
    if let Some(serial) = requested {
        match devices.iter().find(|d| d.serial == serial) {
            Some(d) if d.state == "device" => Ok(d.serial.clone()),
            Some(d) => Err(ScrcpyError::NotReady {
                serial: d.serial.clone(),
                state: d.state.clone(),
            }),
            None => {
                if devices.is_empty() {
                    Err(ScrcpyError::NoDevice)
                } else {
                    Err(ScrcpyError::SerialNotFound {
                        serial: serial.to_string(),
                    })
                }
            }
        }
    } else {
        let ready: Vec<&AdbDevice> = devices.iter().filter(|d| d.state == "device").collect();
        match (ready.len(), devices.len()) {
            (1, _) => Ok(ready[0].serial.clone()),
            (n, _) if n > 1 => Err(ScrcpyError::Ambiguous {
                ready: ready.into_iter().map(|d| d.serial.clone()).collect(),
            }),
            (0, 0) => Err(ScrcpyError::NoDevice),
            (0, _) => {
                // No ready device. If every listed device shares one failure
                // state, report that; otherwise report the first non-ready one.
                if let Some(d) = single_unready(devices) {
                    Err(ScrcpyError::NotReady {
                        serial: d.serial.clone(),
                        state: d.state.clone(),
                    })
                } else {
                    Err(ScrcpyError::NotReady {
                        serial: devices[0].serial.clone(),
                        state: devices[0].state.clone(),
                    })
                }
            }
            _ => unreachable!(),
        }
    }
}

fn single_unready(devices: &[AdbDevice]) -> Option<&AdbDevice> {
    if devices.len() == 1 {
        return Some(&devices[0]);
    }
    let first = devices.first()?;
    if devices.iter().all(|d| d.state == first.state) {
        Some(first)
    } else {
        None
    }
}

fn format_command(bin: &Path, args: &[String]) -> String {
    let mut out = quote(&bin.display().to_string());
    for a in args {
        out.push(' ');
        out.push_str(&quote(a));
    }
    out
}

fn quote(s: &str) -> String {
    if s.is_empty() || s.chars().any(|c| c.is_whitespace() || "\"'\\$".contains(c)) {
        let escaped = s.replace('\'', "'\\''");
        format!("'{escaped}'")
    } else {
        s.to_string()
    }
}

fn launch_scrcpy(bin: &Path, args: &[String], host: &Host) -> Result<i32, ScrcpyError> {
    let status = Command::new(bin)
        .args(args)
        .env("PATH", &host.path)
        .stdin(Stdio::inherit())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .status()
        .map_err(|e| ScrcpyError::LaunchFailed {
            message: e.to_string(),
        })?;
    Ok(status.code().unwrap_or(1))
}

fn record_audit(host: &Host, action: &str, outcome: &str, detail: &str) {
    let Some(path) = &host.audit_log else {
        return;
    };
    let ts = utc_rfc3339(SystemTime::now());
    let mut log = if path.exists() {
        match fs::read_to_string(path) {
            Ok(text) => match AuditLog::from_jsonl(&text) {
                Ok(log) => log,
                Err(e) => {
                    eprintln!("warning: audit log not updated ({e})");
                    return;
                }
            },
            Err(e) => {
                eprintln!("warning: could not read audit log: {e}");
                return;
            }
        }
    } else {
        AuditLog::new()
    };
    log.append(&ts, action, outcome, detail);
    if let Some(parent) = path.parent() {
        if let Err(e) = fs::create_dir_all(parent) {
            eprintln!("warning: could not write audit log: {e}");
            return;
        }
    }
    if let Err(e) = write_audit(path, &log) {
        eprintln!("warning: could not write audit log: {e}");
    }
}

fn write_audit(path: &Path, log: &AuditLog) -> io::Result<()> {
    let mut buf = Vec::new();
    log.write_jsonl(&mut buf)?;
    fs::write(path, buf)
}

/// UTC RFC 3339 timestamp at second resolution, no extra date-time crate.
fn utc_rfc3339(now: SystemTime) -> String {
    let secs = now.duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as i64;
    let (y, m, d, hh, mm, ss) = civil_from_unix(secs);
    format!("{y:04}-{m:02}-{d:02}T{hh:02}:{mm:02}:{ss:02}Z")
}

/// Howard Hinnant's civil-from-days, plus time-of-day, for Unix seconds (UTC).
fn civil_from_unix(secs: i64) -> (i32, u32, u32, u32, u32, u32) {
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400) as u32;
    let hh = rem / 3600;
    let mm = (rem % 3600) / 60;
    let ss = rem % 60;
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = (z - era * 146_097) as u32;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    (y as i32, m, d, hh, mm, ss)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_DIR_SEQ: AtomicU64 = AtomicU64::new(0);

    struct Fixture {
        dir: PathBuf,
        host: Host,
        scrcpy_log: PathBuf,
        audit: PathBuf,
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.dir);
        }
    }

    fn write_exec(path: &Path, body: &str) {
        fs::write(path, body).unwrap();
        let mut perms = fs::metadata(path).unwrap().permissions();
        perms.set_mode(0o755);
        fs::set_permissions(path, perms).unwrap();
    }

    fn adb_script(rows: &str) -> String {
        let rows = rows.trim_end_matches('\n');
        format!(
            "#!/bin/sh\nif [ \"$1\" = devices ]; then\ncat <<'EOF' || exit 1\nList of devices attached\n{rows}\nEOF\nexit 0\nfi\necho \"unexpected adb args: $*\" >&2\nexit 1\n"
        )
    }

    fn scrcpy_script(log: &Path) -> String {
        format!(
            "#!/bin/sh\nprintf '%s\\n' \"$0\" \"$@\" > '{}'\nexit 0\n",
            log.display()
        )
    }

    fn setup(rows: &str, with_scrcpy: bool) -> Fixture {
        let seq = TEST_DIR_SEQ.fetch_add(1, Ordering::Relaxed);
        let dir = env::temp_dir().join(format!("edl-scrcpy-test-{}-{}", std::process::id(), seq));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        write_exec(&dir.join("adb"), &adb_script(rows));
        let scrcpy_log = dir.join("scrcpy-args.log");
        if with_scrcpy {
            write_exec(&dir.join("scrcpy"), &scrcpy_script(&scrcpy_log));
        }
        let audit = dir.join("audit.jsonl");
        // Prepend the fixture dir to the real PATH. Replacing PATH entirely would
        // hide /bin/cat and make the fake adb script exit 0 with empty output.
        let mut path_dirs = vec![dir.clone()];
        if let Some(orig) = env::var_os("PATH") {
            path_dirs.extend(env::split_paths(&orig));
        } else {
            path_dirs.push(PathBuf::from("/bin"));
            path_dirs.push(PathBuf::from("/usr/bin"));
        }
        let host = Host {
            path: env::join_paths(path_dirs).expect("PATH join"),
            scrcpy_bin: None,
            audit_log: Some(audit.clone()),
        };
        Fixture {
            dir,
            host,
            scrcpy_log,
            audit,
        }
    }

    fn opts(serial: Option<&str>, dry_run: bool, extra: &[&str]) -> Options {
        Options {
            serial: serial.map(str::to_string),
            dry_run,
            extra: extra.iter().map(|s| (*s).to_string()).collect(),
        }
    }

    fn audit_text(fx: &Fixture) -> String {
        fs::read_to_string(&fx.audit).unwrap_or_default()
    }

    #[test]
    fn not_installed_is_a_clean_error() {
        let fx = setup("SERIAL\tdevice\n", false);
        let err = run(&opts(None, false, &[]), &fx.host).unwrap_err();
        assert!(matches!(
            err,
            ScrcpyError::ScrcpyNotInstalled {
                scrcpy_bin_set: false
            }
        ));
        let msg = err.to_string();
        assert!(msg.contains(SCRCPY_PROJECT), "{msg}");
        assert!(!msg.to_lowercase().contains("panic"), "{msg}");
        assert!(!fx.scrcpy_log.exists());
        let log = audit_text(&fx);
        assert!(log.contains("scrcpy not installed"));
        assert!(!log.contains("SERIAL"));
    }

    #[test]
    fn ready_device_launches_scrcpy_with_discovered_serial() {
        let fx = setup("ABC123\tdevice\n", true);
        let out = run(&opts(None, false, &[]), &fx.host).unwrap();
        assert_eq!(out, ScrcpyOutcome::Launched { status: 0 });
        let logged = fs::read_to_string(&fx.scrcpy_log).unwrap();
        let lines: Vec<&str> = logged.lines().collect();
        assert_eq!(lines[0], fx.dir.join("scrcpy").to_str().unwrap());
        assert_eq!(lines[1], "-s");
        assert_eq!(lines[2], "ABC123");
        let log = audit_text(&fx);
        assert!(log.contains("\"outcome\":\"ok\""));
        assert!(log.contains(&device_detail("ABC123")));
        assert!(!log.contains("ABC123"));
    }

    #[test]
    fn no_device_is_refused() {
        let fx = setup("", true);
        let err = run(&opts(None, false, &[]), &fx.host).unwrap_err();
        assert_eq!(err, ScrcpyError::NoDevice);
        assert!(err.to_string().contains("No device"));
        assert!(!fx.scrcpy_log.exists());
        let log = audit_text(&fx);
        assert!(log.contains("denied"));
        assert!(log.contains("no adb device"));
    }

    #[test]
    fn unauthorized_is_refused() {
        let fx = setup("PHONE\tunauthorized\n", true);
        let err = run(&opts(None, false, &[]), &fx.host).unwrap_err();
        assert_eq!(
            err,
            ScrcpyError::NotReady {
                serial: "PHONE".into(),
                state: "unauthorized".into()
            }
        );
        let msg = err.to_string();
        assert!(msg.contains("unauthorized"), "{msg}");
        assert!(msg.contains("PHONE"), "{msg}");
        assert!(!fx.scrcpy_log.exists());
        let log = audit_text(&fx);
        assert!(log.contains("denied"));
        assert!(log.contains(&device_detail("PHONE")));
        assert!(!log.contains("PHONE"));
    }

    #[test]
    fn offline_is_refused() {
        let fx = setup("PHONE\toffline\n", true);
        let err = run(&opts(None, false, &[]), &fx.host).unwrap_err();
        assert_eq!(
            err,
            ScrcpyError::NotReady {
                serial: "PHONE".into(),
                state: "offline".into()
            }
        );
        let msg = err.to_string();
        assert!(msg.contains("offline"), "{msg}");
        assert!(!fx.scrcpy_log.exists());
    }

    #[test]
    fn dry_run_prints_the_exact_command_and_does_not_launch() {
        let fx = setup("XYZ\tdevice\n", true);
        let out = run(&opts(Some("XYZ"), true, &["--turn-screen-off"]), &fx.host).unwrap();
        let command = match out {
            ScrcpyOutcome::DryRun { command } => command,
            other => panic!("expected dry-run, got {other:?}"),
        };
        let bin = fx.dir.join("scrcpy");
        assert_eq!(
            command,
            format!("{} -s XYZ --turn-screen-off", bin.display())
        );
        assert!(!fx.scrcpy_log.exists());
        let log = audit_text(&fx);
        assert!(log.contains("dry_run"));
        assert!(log.contains(&device_detail("XYZ")));
        assert!(!log.contains("XYZ"));
    }

    #[test]
    fn serial_is_passed_as_dash_s() {
        let fx = setup("ONE\tdevice\nTWO\tdevice\n", true);
        let out = run(&opts(Some("TWO"), false, &["--stay-awake"]), &fx.host).unwrap();
        assert_eq!(out, ScrcpyOutcome::Launched { status: 0 });
        let logged = fs::read_to_string(&fx.scrcpy_log).unwrap();
        let lines: Vec<&str> = logged.lines().collect();
        assert_eq!(
            lines,
            vec![
                fx.dir.join("scrcpy").to_str().unwrap(),
                "-s",
                "TWO",
                "--stay-awake",
            ]
        );
    }

    #[test]
    fn scrcpy_bin_override_is_used_when_not_on_path() {
        let fx = setup("ABC\tdevice\n", false);
        let hidden = fx.dir.join("hidden");
        fs::create_dir(&hidden).unwrap();
        let bin = hidden.join("myscrcpy");
        write_exec(&bin, &scrcpy_script(&fx.scrcpy_log));
        let mut host = fx.host.clone();
        host.scrcpy_bin = Some(bin.clone());
        let out = run(&opts(Some("ABC"), true, &[]), &host).unwrap();
        match out {
            ScrcpyOutcome::DryRun { command } => {
                assert_eq!(command, format!("{} -s ABC", bin.display()));
            }
            other => panic!("expected dry-run, got {other:?}"),
        }
    }

    #[test]
    fn adb_missing_is_a_clean_error() {
        let fx = setup("ABC\tdevice\n", true);
        fs::remove_file(fx.dir.join("adb")).unwrap();
        let err = run(&opts(None, false, &[]), &fx.host).unwrap_err();
        assert_eq!(err, ScrcpyError::AdbMissing);
        assert!(err.to_string().contains("adb was not found"));
        assert!(!fx.scrcpy_log.exists());
    }

    #[test]
    fn requested_serial_unauthorized_is_refused() {
        let fx = setup("ONE\tdevice\nTWO\tunauthorized\n", true);
        let err = run(&opts(Some("TWO"), false, &[]), &fx.host).unwrap_err();
        assert_eq!(
            err,
            ScrcpyError::NotReady {
                serial: "TWO".into(),
                state: "unauthorized".into()
            }
        );
        assert!(!fx.scrcpy_log.exists());
    }

    #[test]
    fn parse_args_accepts_documented_flags() {
        let parsed = parse_args(&[
            "--dry-run".into(),
            "--serial".into(),
            "ABC".into(),
            "--".into(),
            "--bit-rate".into(),
            "8M".into(),
        ])
        .unwrap();
        assert_eq!(
            parsed,
            Options {
                serial: Some("ABC".into()),
                dry_run: true,
                extra: vec!["--bit-rate".into(), "8M".into()],
            }
        );
        assert!(parse_args(&["--nope".into()]).is_err());
        assert!(parse_args(&["--serial".into()]).is_err());
    }

    #[test]
    fn utc_rfc3339_known_instants() {
        let t = |secs: u64| UNIX_EPOCH + std::time::Duration::from_secs(secs);
        assert_eq!(utc_rfc3339(t(0)), "1970-01-01T00:00:00Z");
        assert_eq!(utc_rfc3339(t(1_767_571_200)), "2026-01-05T00:00:00Z");
        // 29 Feb 2024
        assert_eq!(utc_rfc3339(t(1_709_164_800)), "2024-02-29T00:00:00Z");
    }
}
