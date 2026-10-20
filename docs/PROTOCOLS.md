# Protocols

## Sahara (implemented: HELLO / HELLO_RESP / READ_DATA / END_IMAGE_TX / DONE / DONE_RESP, plus read-only command mode: CMD_READY / CMD_EXEC / CMD_EXEC_RESP / CMD_EXEC_DATA / SWITCH_MODE; RESET and RESET_RESP decode only)
Little-endian `cmd:u32, len:u32, payload`. HELLO and HELLO_RESP are 48 bytes, READ_DATA 20, END_IMAGE_TX 16, DONE 8, DONE_RESP 12.

Layouts are written from publicly documented structures and are **not validated against hardware captures**; fixtures are synthetic. Treat byte layouts as unverified until a capture-based test exists.

Command-mode layouts: CMD_READY 8 bytes; SWITCH_MODE 12 (`mode`); CMD_EXEC 12 (`client_command`); CMD_EXEC_RESP 16 (`client_command`, `resp_length`); CMD_EXEC_DATA 12 (`client_command`). After CMD_EXEC_DATA the device sends `resp_length` raw bytes (not packet-framed).

Host behaviour (programmer upload, `run_programmer_upload`): accepts only image-transfer-pending mode; replies version `min(2, device)`; rejects `version_compat > 2`; serves READ_DATA from the programmer image (range/size checked); success = DONE_RESP with status 1. Command mode, memory debug, 64-bit reads, multi-image uploads are unsupported and rejected.

Success means "device acknowledged the transfer", not that a programmer is running.

### Read-only identification (`run_identify`)

Sequence: device HELLO (image-transfer-pending or command mode accepted; `version_compat > 2` rejected) -> host HELLO_RESP with `mode = Command` -> device CMD_READY -> for each client command `CMD_EXEC`, expect `CMD_EXEC_RESP` echoing the same command, send `CMD_EXEC_DATA`, read exactly `resp_length` raw bytes -> best-effort `SWITCH_MODE(image-transfer-pending)` so the device returns to its initial protocol state.

Client commands: `0x1` serial (4 bytes, LE u32), `0x2` MSM hardware ID (8 bytes, LE u64; `msm_id = (v>>32)&0xFFFFFF`, `oem_id = (v>>16)&0xFFFF`, `model_id = v&0xFFFF`), `0x3` OEM PK hash (1..64 bytes). A zero `resp_length` is reported as absent. Hostile input is rejected: wrong client command echoed, announced length not 4/8 for serial/HW ID or above 64, more raw bytes than announced, read budget (64 transfers) exceeded.

This path never sends READ_DATA responses, images, DONE, or anything toward Firehose. The field layouts and the hardware-ID split come from public descriptions and are **unverified against hardware**; whether a given device answers these queries in this order, or tolerates the closing SWITCH_MODE, is unknown until a capture exists. Authorization is `security::authorize(Operation::IdentifyDevice)`, which is read-only and needs no device profile.

## Firehose
Not started. Will be capability-driven: read support never implies write support.
