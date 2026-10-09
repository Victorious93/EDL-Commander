# Protocols

## Sahara (implemented: HELLO / HELLO_RESP / READ_DATA / END_IMAGE_TX / DONE / DONE_RESP; RESET and RESET_RESP decode only)
Little-endian `cmd:u32, len:u32, payload`. HELLO and HELLO_RESP are 48 bytes, READ_DATA 20, END_IMAGE_TX 16, DONE 8, DONE_RESP 12.

Layouts are written from publicly documented structures and are **not validated against hardware captures**; fixtures are synthetic. Treat byte layouts as unverified until a capture-based test exists.

Host behaviour: accepts only image-transfer-pending mode; replies version `min(2, device)`; rejects `version_compat > 2`; serves READ_DATA from the programmer image (range/size checked); success = DONE_RESP with status 1. Command mode, memory debug, 64-bit reads, multi-image uploads are unsupported and rejected.

Success means "device acknowledged the transfer", not that a programmer is running.

## Firehose
Not started. Will be capability-driven: read support never implies write support.
