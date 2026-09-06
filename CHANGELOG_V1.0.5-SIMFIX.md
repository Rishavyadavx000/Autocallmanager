# AutoCallManager V1.0.6 SIM + Contact Picker Fix

- Removed AI Message from Add Schedule wizard.
- Contact picker now supports search by name and number.
- Added dual-SIM selector in Call Function.
- SIM selector is hidden on single-SIM devices.
- Selected SIM/PhoneAccountHandle persists with each schedule.
- Scheduled calls use the selected PhoneAccountHandle when enabled.
- Default outgoing SIM is used when specific SIM selection is disabled.
- Kept Gemini AI Studio and prompt architecture separate from schedule creation.

- SIM-to-PhoneAccount mapping uses TelephonyManager subscription IDs on Android 11+ for better dual-SIM accuracy.
- Removed AI badge from schedule cards because AI Message is no longer part of the Add Schedule wizard.

### Direct Time Input Update
- Hour and minute fields are editable text inputs.
- Stepper +/- controls remain available.
- Inputs accept 1-2 digits and normalize to two digits.
- Hour range is 1-12; minute range is 0-59.
- Invalid/blank values are corrected on blur and validated before advancing.
- 12-hour AM/PM behavior remains unchanged.
