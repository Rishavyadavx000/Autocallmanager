# AutoCallManager V1.0.6 Prompt-Ready

V1.0.6 preserves the stable prompt contract introduced in the prior release while standardizing all user-facing clock times on a 12-hour `hh:mm:ss AM/PM` format.

The local prompt catalog is stored in `app/src/main/assets/ai_prompts_v1.json`. Schedules preserve `promptId` and `promptVersion` so later releases can extend prompt management without changing stored schedule meaning.

Admin Panel is intentionally excluded from V1.0.6.
