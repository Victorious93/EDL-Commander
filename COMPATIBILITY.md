# Compatibility

**Verified hardware: none.** The only device entry (`database/devices/oneplus-6.json`) is a research target at level `unknown`, with unverified model/variant and empty USB IDs.

## Capability levels
`unknown` → `usb_detected` → `sahara_identified` → `compatible_programmer_available` → `read_only_verified` → `backup_verified` → `write_supported` → `recovery_verified`

Rules enforced by `Database::validate`: a level needs evidence supporting it; anything above `usb_detected` needs hardware evidence (simulator evidence is rejected); `compatible_programmer_available`+ needs a listed programmer; `read_only_verified`+ needs `last_verified`; `write_supported`+ needs a known (yes/no) authentication requirement. Authentication and restrictions are separate fields from the level. Writes additionally require `authentication.required == no` — authenticated flows are unsupported.

Target families (investigation only, not claims): OnePlus 3/5/6/7/8/9 and Nord variants. Exact models and chipsets must be identified per variant.
