//! JNI bridge between the Android companion and `edl-core`.
//!
//! Kotlin owns the `UsbDeviceConnection`; this crate only ever sees a [`bridge::ByteBridge`]
//! (read/write callbacks) wrapped as an `edl_core::transport::Transport`. Exposed operations
//! are strictly read-only: Sahara command-mode identification plus audit-log helpers. There is
//! no programmer upload, Firehose or write path here.

pub mod bridge;
pub mod ops;

mod jni_glue;

#[cfg(test)]
mod testutil;
