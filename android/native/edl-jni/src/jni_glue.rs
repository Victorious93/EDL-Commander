//! JNI entry points for `ai.edlcommander.companion.nativecore.NativeLib`.
//!
//! Panics never cross the FFI boundary: every entry point runs under `catch_unwind` and turns a
//! panic into a `native_panic` JSON error. Java exceptions raised by the Kotlin callbacks are
//! cleared and reported to the Sahara layer as an I/O failure.

use crate::bridge::{ByteBridge, BRIDGE_IO};
use crate::ops;
use jni::objects::{JByteArray, JObject, JString, JValue};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

struct JavaBridge<'a, 'local> {
    env: &'a mut JNIEnv<'local>,
    target: &'a JObject<'local>,
}

impl JavaBridge<'_, '_> {
    fn call(&mut self, name: &str, arr: &JByteArray<'_>, timeout_ms: i32) -> i32 {
        let res = self.env.call_method(
            self.target,
            name,
            "([BI)I",
            &[JValue::Object(arr), JValue::Int(timeout_ms)],
        );
        match res {
            Ok(v) => v.i().unwrap_or(BRIDGE_IO),
            Err(_) => {
                let _ = self.env.exception_clear();
                BRIDGE_IO
            }
        }
    }
}

impl ByteBridge for JavaBridge<'_, '_> {
    fn bulk_write(&mut self, data: &[u8], timeout_ms: i32) -> i32 {
        let Ok(arr) = self.env.byte_array_from_slice(data) else {
            let _ = self.env.exception_clear();
            return BRIDGE_IO;
        };
        let n = self.call("write", &arr, timeout_ms);
        let _ = self.env.delete_local_ref(arr);
        n
    }

    fn bulk_read(&mut self, buf: &mut [u8], timeout_ms: i32) -> i32 {
        let Ok(len) = i32::try_from(buf.len()) else {
            return BRIDGE_IO;
        };
        let Ok(arr) = self.env.new_byte_array(len) else {
            let _ = self.env.exception_clear();
            return BRIDGE_IO;
        };
        let mut n = self.call("read", &arr, timeout_ms);
        if n > 0 {
            let want = (n as usize).min(buf.len());
            let mut tmp = vec![0i8; want];
            if self.env.get_byte_array_region(&arr, 0, &mut tmp).is_ok() {
                for (dst, src) in buf.iter_mut().zip(tmp) {
                    *dst = src as u8;
                }
            } else {
                let _ = self.env.exception_clear();
                n = BRIDGE_IO;
            }
        }
        let _ = self.env.delete_local_ref(arr);
        n
    }
}

fn panic_json() -> String {
    r#"{"ok":false,"code":"native_panic","message":"internal error in native core"}"#.to_string()
}

fn string_arg(env: &mut JNIEnv<'_>, s: &JString<'_>) -> Option<String> {
    env.get_string(s).ok().map(String::from)
}

fn respond(env: &mut JNIEnv<'_>, body: Option<String>) -> jstring {
    let body = body.unwrap_or_else(|| {
        r#"{"ok":false,"code":"bad_arguments","message":"invalid string argument"}"#.to_string()
    });
    match env.new_string(body) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_ai_edlcommander_companion_nativecore_NativeLib_nativeVersion<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
) -> jstring {
    let body = catch_unwind(ops::version).unwrap_or_else(|_| panic_json());
    respond(&mut env, Some(body))
}

#[no_mangle]
pub extern "system" fn Java_ai_edlcommander_companion_nativecore_NativeLib_nativeFeatures<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
) -> jstring {
    let body = catch_unwind(ops::features).unwrap_or_else(|_| panic_json());
    respond(&mut env, Some(body))
}

#[no_mangle]
pub extern "system" fn Java_ai_edlcommander_companion_nativecore_NativeLib_nativeAuthorizeIdentify<
    'l,
>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    audit: JString<'l>,
    timestamp: JString<'l>,
) -> jstring {
    let body = catch_unwind(AssertUnwindSafe(|| {
        let audit = string_arg(&mut env, &audit)?;
        let ts = string_arg(&mut env, &timestamp)?;
        Some(ops::authorize_identify(&audit, &ts))
    }))
    .unwrap_or_else(|_| Some(panic_json()));
    respond(&mut env, body)
}

#[no_mangle]
pub extern "system" fn Java_ai_edlcommander_companion_nativecore_NativeLib_nativeAuditAppend<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    audit: JString<'l>,
    timestamp: JString<'l>,
    action: JString<'l>,
    outcome: JString<'l>,
    detail: JString<'l>,
) -> jstring {
    let body = catch_unwind(AssertUnwindSafe(|| {
        let audit = string_arg(&mut env, &audit)?;
        let ts = string_arg(&mut env, &timestamp)?;
        let action = string_arg(&mut env, &action)?;
        let outcome = string_arg(&mut env, &outcome)?;
        let detail = string_arg(&mut env, &detail)?;
        Some(ops::audit_append(&audit, &ts, &action, &outcome, &detail))
    }))
    .unwrap_or_else(|_| Some(panic_json()));
    respond(&mut env, body)
}

#[no_mangle]
pub extern "system" fn Java_ai_edlcommander_companion_nativecore_NativeLib_nativeRecordDeniedWrite<
    'l,
>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    audit: JString<'l>,
    timestamp: JString<'l>,
    command: JString<'l>,
) -> jstring {
    let body = catch_unwind(AssertUnwindSafe(|| {
        let audit = string_arg(&mut env, &audit)?;
        let ts = string_arg(&mut env, &timestamp)?;
        let command = string_arg(&mut env, &command)?;
        Some(ops::record_denied_write(&audit, &ts, &command))
    }))
    .unwrap_or_else(|_| Some(panic_json()));
    respond(&mut env, body)
}

#[no_mangle]
pub extern "system" fn Java_ai_edlcommander_companion_nativecore_NativeLib_nativeSaharaIdentify<
    'l,
>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    transport: JObject<'l>,
    audit: JString<'l>,
    timestamp: JString<'l>,
    timeout_ms: jint,
) -> jstring {
    let body = catch_unwind(AssertUnwindSafe(|| {
        let audit = string_arg(&mut env, &audit)?;
        let ts = string_arg(&mut env, &timestamp)?;
        let bridge = JavaBridge {
            env: &mut env,
            target: &transport,
        };
        Some(ops::identify(bridge, &audit, &ts, timeout_ms))
    }))
    .unwrap_or_else(|_| Some(panic_json()));
    respond(&mut env, body)
}

#[no_mangle]
pub extern "system" fn Java_ai_edlcommander_companion_nativecore_NativeLib_nativeSaharaIdentifySimulated<
    'l,
>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    audit: JString<'l>,
    timestamp: JString<'l>,
) -> jstring {
    let body = catch_unwind(AssertUnwindSafe(|| {
        let audit = string_arg(&mut env, &audit)?;
        let ts = string_arg(&mut env, &timestamp)?;
        Some(ops::identify_simulated(&audit, &ts))
    }))
    .unwrap_or_else(|_| Some(panic_json()));
    respond(&mut env, body)
}
