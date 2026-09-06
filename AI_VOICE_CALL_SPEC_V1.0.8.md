# AutoCallManager V1.0.7 - AI Voice Call Full Implementation Prompt

AUTO CALL MANAGER V1.0.7
AI VOICE CALL MODE
FULL IMPLEMENTATION / INTEGRATION PROMPT

PROJECT GOAL
Upgrade the existing AutoCallManager V1.0.7 with an optional AI VOICE CALL mode while preserving NORMAL SIM CALL.

CALL MODES
1. NORMAL SIM CALL
2. AI VOICE CALL

Do not remove or break the existing native Android scheduled-call architecture.

NORMAL SIM CALL must continue to use:
Schedule -> AlarmManager -> BroadcastReceiver -> validation -> TelecomManager.placeCall() -> background call-state tracking -> history/timeline.

AI VOICE CALL is a separate architecture:
AutoCallManager -> Backend -> Telephony Provider -> AI Voice Session -> Recipient.

Do NOT inject Android TTS into a cellular-call uplink using unsupported APIs, accessibility automation, screen tapping, audio hacks, forced UI, or hidden dialer automation.

1. CALL MODE
Add:
CALL MODE
[ NORMAL SIM CALL ]
[ AI VOICE CALL ]

Default: NORMAL SIM CALL.

Never silently convert a normal call into an AI voice call.

2. AI VOICE CALL FLOW
User creates schedule
-> selects AI VOICE CALL
-> enters AI instruction
-> schedule saved
-> execution time
-> backend creates outbound provider call
-> recipient answers
-> call connects to AI voice session
-> AI speaks
-> recipient can respond
-> AI processes response
-> AI responds
-> session ends
-> real result stored
-> Android Overview/History updated.

3. BACKEND AUTHORITY
For AI Voice Calls, backend is authoritative because network and telephony-provider access are required.

Use:
scheduleId
executionId
idempotencyKey

Prevent duplicate calls after:
app restart
network retry
webhook retry
server restart
Android retry.

4. SCHEDULE DATA
Extend schedule with:
scheduleId
executionMode
phoneNumber
scheduledAtMillis
timezoneId
localDate
localTime
recurrence
enabled
aiEnabled
promptId
promptVersion
aiVoiceProvider
aiVoiceId
aiLanguage
maxDurationSeconds
retryPolicy
backendExecutionEnabled

executionMode:
NORMAL_SIM
AI_VOICE

Do not delete existing fields.

5. TIMEZONE
Persist the original timezoneId for recurring schedules.
Example:
timezoneId=Asia/Kolkata
localTime=08:00:00
recurrence=DAILY

Recurring schedules must calculate their next occurrence in the original timezone, not the current device timezone.

One-time schedules retain epoch-millisecond execution.

6. AI INSTRUCTION
Show AI instruction only when AI VOICE CALL is selected.

Store:
promptId
promptVersion
userInstruction

Existing schedules must keep their original prompt version.

7. PROMPT ARCHITECTURE
Preserve the existing versioned Gemini/prompt architecture.

Recommended prompt IDs:
voice.call.v1
voice.call.greeting.v1
voice.call.reply.v1
voice.call.end.v1

The backend resolves the exact prompt version.

8. PROVIDER ABSTRACTION
Create a provider interface:
VoiceCallProvider

Methods:
createOutboundCall()
connectVoiceSession()
updateCall()
hangupCall()
getCallStatus()
handleWebhook()
verifyWebhook()
cancelCall()

Implement:
TwilioVoiceCallProvider

Keep provider-specific code isolated.

9. TWILIO OUTBOUND CALL
Use server-side provider credentials only.

Required secrets must NEVER be placed in:
Android APK
HTML
JavaScript bundle
Kotlin source
Git repository
README
frontend responses
logs
GitHub Action output

Use environment variables/secure secret storage.

10. CONVERSATION RELAY
Preferred option:
Twilio ConversationRelay.

Flow:
Outbound call
-> secure TwiML
-> Connect
-> ConversationRelay
-> secure WSS backend
-> AI application
-> text response
-> provider TTS
-> remote recipient.

Support:
session setup
speech events
AI response
session events
interrupt/end events.

Use the official provider message format.

11. MEDIA STREAMS ALTERNATIVE
If ConversationRelay is unsuitable, support bidirectional Media Streams.

Flow:
telephony provider
-> bidirectional WebSocket
-> STT/AI
-> audio response
-> WebSocket
-> call.

Handle:
inbound audio
outbound audio
telephony encoding
buffering
interruption
call end.

Do not send WAV/MP3 headers when raw telephony audio is expected.

12. WEBSOCKET SECURITY
Use HTTPS/WSS.
Validate provider signatures.
For Twilio, validate X-Twilio-Signature using the official mechanism.
Reject unauthorized callbacks with 403.
Never skip signature validation in production.

13. VOICE SESSION STATES
CREATED
OUTBOUND_REQUESTED
RINGING
ANSWERED
CONNECTING_AI
AI_READY
SPEAKING
LISTENING
PROCESSING
ENDED
FAILED
TIMEOUT
CANCELLED

Never mark success merely because a call request was created.

14. PROVIDER CALL STATES
Track separately:
RINGING
CONNECTED
DISCONNECTED

AI states:
CONNECTING
READY
SPEAKING
LISTENING
ENDED

15. REAL TIMELINE
Example:
10:50:00 PM SCHEDULED
10:50:01 PM BACKEND EXECUTION STARTED
10:50:02 PM OUTBOUND CALL CREATED
10:50:08 PM RINGING
10:50:12 PM ANSWERED
10:50:13 PM AI SESSION CONNECTED
10:50:14 PM AI GREETING STARTED
10:50:20 PM AI SPEECH COMPLETED
10:50:21 PM REMOTE SPEECH DETECTED
10:50:24 PM AI RESPONSE
10:51:00 PM SESSION ENDED
10:51:01 PM CALL COMPLETED

Use actual event timestamps.

16. AI DISCLOSURE
The AI should identify itself as an automated/AI system when appropriate.
Do not deceptively impersonate a real person.
Example:
"Namaste, main AutoCallManager ki AI voice assistant hoon."

17. CONVERSATION RULES
AI must:
stay on configured task
use configured language
handle short interruptions
handle silence
avoid infinite loops
avoid repeated sentences
avoid false claims
stop when task is complete
respect maximum duration.

18. LANGUAGES
Support:
Hindi
English
Hinglish

Do not infer language from a contact name.

19. VOICE SETTINGS
Provider
Voice ID
Language
Speaking speed
Greeting
Maximum duration
Silence timeout

Keep provider-specific fields behind abstraction.

20. USER UI
STEP 1 CONTACT
Phone Number
[ +91 XXXXX XXXXX ]
[ PICK CONTACT ]

No Contact Name/Group fields.

STEP 2 DATE & TIME
Keep current Smart Time design:
12-hour hh:mm:ss AM/PM
direct typing
+/- controls
NOW
+1/+5/+10 MIN
past-time validation
timezone persistence.

STEP 3 CALL MODE
[ NORMAL SIM CALL ]
[ AI VOICE CALL ]

If AI:
AI Message / Instruction
Voice
Language
Maximum duration.

STEP 4 REVIEW
Show:
Contact
Date
Time
Call Mode
SIM or Provider
Prompt
Prompt Version
Voice
Language.

STEP 5 SAVE.

21. DUAL SIM
NORMAL SIM CALL:
keep SIM 1 / SIM 2 PhoneAccountHandle logic.

AI VOICE CALL:
do not use the phone SIM as the AI audio path.
It is provider-originated.
Do not confuse SIM selection with provider voice number.

22. SCREEN OFF / LOCKED
NORMAL SIM CALL:
preserve the native screen-off/locked execution architecture.

AI VOICE CALL:
must not depend on Android UI being open.

Preferred:
backend/provider originates the call.

Therefore, subject to backend/provider/network availability:
screen ON -> AI call can execute
screen OFF -> AI call can execute
phone LOCKED -> AI call can execute
app CLOSED -> AI call can execute.

Do not force-unlock the device or launch hidden activities.

23. NETWORK FAILURE
If backend is unavailable:
do not fake execution.
Use:
BACKEND_UNAVAILABLE

If server-side execution is enabled, backend remains authoritative.

24. NORMAL SIM FALLBACK
Do NOT silently fall back from AI VOICE CALL to NORMAL SIM CALL.

Optional setting:
Fallback to normal SIM call
Default OFF.

When enabled, record the fallback explicitly in history.

25. RETRY
Retry only for eligible temporary failures:
ringing timeout
provider error
temporary network failure
AI session failure.

Do not retry:
successful answer/completion
explicit user end
permanent authentication failure
blocked destination
policy-disabled retry.

Use idempotency to prevent duplicate calls.

26. MAX DURATION
Every AI call must have a maximum duration.
Example 120 seconds.

On timeout:
end AI session
end call
record TIMEOUT.

27. SILENCE
Use bounded silence handling.
Example:
10 seconds -> one polite prompt
additional silence -> graceful end.

28. INTERRUPTION / BARGE-IN
If supported by the provider architecture, stop/interruption AI speech when recipient begins speaking.
Do not speak indefinitely over the recipient.

29. CALL RECORDING
OFF by default.
If later added:
explicit setting
retention policy
secure storage
privacy/compliance handling
never in APK.

30. PRIVACY
Store minimum required data.
Never place API keys, tokens, passwords, or provider secrets in client logs.
Mask phone numbers in admin logs where appropriate.

31. ADMIN CONTROL CENTER
Add AI VOICE PROVIDER section.

Fields:
Provider
Voice number (masked)
Connection status
Test connection
AI Voice ON/OFF
Default language
Default voice
Max duration
Fallback policy.

32. API MANAGER
Separate:
AI PROVIDERS
TELEPHONY PROVIDERS

Example:
AI = Gemini
Telephony = Twilio

Never mix their credentials.

33. PROMPT MANAGER
Add:
voice.call.greeting.v1
voice.call.main.v1
voice.call.reply.v1
voice.call.end.v1

Admin can:
Create
Edit
Version
Publish
Disable
Rollback
Test.

34. ADMIN RBAC
OWNER = full AI voice configuration
ADMIN = AI voice + prompt management
EDITOR = prompt management
VIEWER = read-only

Permissions must be enforced on the backend, not only in the UI.

35. BACKEND ENDPOINTS
Implement secure endpoints similar to:

POST /api/voice/calls
GET /api/voice/calls/:executionId
POST /api/voice/calls/:executionId/cancel
POST /api/voice/webhooks/status
POST /api/voice/webhooks/twilio
GET /api/voice/providers/status

WebSocket:
WSS /api/voice/session

Adapt routing to the existing backend without breaking old routes.

36. WEBHOOK IDEMPOTENCY
Provider webhooks can be retried.
Track:
providerEventId
callSid
executionId

Already-processed events must not duplicate state transitions.

37. STATE MACHINE
Allowed transitions:

CREATED -> OUTBOUND_REQUESTED
OUTBOUND_REQUESTED -> RINGING or FAILED
RINGING -> ANSWERED, FAILED, TIMEOUT
ANSWERED -> CONNECTING_AI or FAILED
CONNECTING_AI -> AI_READY or FAILED
AI_READY -> SPEAKING, LISTENING, ENDED
LISTENING -> PROCESSING -> SPEAKING or ENDED
ENDED -> COMPLETED
FAILED -> RETRYING -> OUTBOUND_REQUESTED

Reject impossible transitions.

38. OVERVIEW
Separate AI Voice metrics from Normal SIM metrics.

AI Voice Calls
AI Voice Connected
AI Voice Completed
AI Voice Failed
AI Voice Duration
AI Voice Success Rate

Do not combine provider calls and SIM calls into one misleading counter.

39. HISTORY
Show:
Mode
Provider
Call ID
Execution ID
Prompt ID
Prompt Version
Voice
Language
Duration
Final status
Failure reason.

40. REAL STATUS
Provider:
NOT CONFIGURED
CONNECTING
CONNECTED
ERROR

AI:
NOT CONFIGURED
ONLINE
ERROR
RATE LIMITED

Call:
SCHEDULED
RINGING
CONNECTED
SPEAKING
ENDED
FAILED

Never show ONLINE without a real health check.

41. ADMIN TEST CALL
Admin-only:
select provider
test number
voice
prompt
[ TEST AI VOICE CALL ]

Clearly label TEST and apply rate limits.
Keep test calls separate from normal schedules.

42. RATE LIMITING
Backend limits for:
requests/minute
calls/hour/day
AI generation
admin test calls.

Prevent loops and runaway call creation.

43. USAGE / QUOTA
Admin dashboard can show:
estimated usage
provider call count
AI request count
safe quota/rate-limit status.

Never expose secrets.

44. ERROR HANDLING
Use explicit errors:
PROVIDER_NOT_CONFIGURED
PROVIDER_AUTH_FAILED
PROVIDER_RATE_LIMITED
OUTBOUND_CALL_FAILED
CALL_NOT_ANSWERED
AI_SESSION_FAILED
WEBSOCKET_FAILED
WEBHOOK_AUTH_FAILED
BACKEND_UNAVAILABLE
VOICE_CONFIGURATION_INVALID
TIMEOUT

Never replace important errors with a generic success message.

45. LOGGING
Log:
executionId
scheduleId
provider call ID
timestamp
state transition
provider status
AI state
error code

Never log:
API key
AuthToken
password
raw authorization header
full secret.

46. SECURITY
Production:
HTTPS
WSS
webhook signature validation
secure server secrets
server-side authorization
rate limiting
idempotency
audit log
input validation
phone-number normalization
request size limits.

Never trust frontend role or hidden controls.

47. CONTACT VALIDATION
Normalize valid phone numbers.
Reject malformed/empty numbers.
Do not silently alter contact numbers.

48. AI SAFETY
AI must not:
deceptively impersonate a real person
claim to be human when it is AI
claim an action was completed when it was not
continue indefinitely
expose system prompts
expose provider secrets.

49. ANDROID BACKGROUND RULES
Do NOT:
force unlock
simulate touches
use accessibility to operate the dialer
inject unsupported audio into cellular calls
force-launch hidden UI
keep a permanent unnecessary wake lock.

For AI Voice Call, provider/backend owns the telephone audio path.
For Normal SIM Call, retain the current native Android architecture.

50. OFFLINE
Normal SIM schedules can remain local/native.

AI Voice:
if server execution configured -> server authoritative.
if backend unavailable -> report AI Voice unavailable.
Do not report completion falsely.

51. BACKUP / RESTORE
Can include:
executionMode
schedule configuration
timezone
promptId
promptVersion
voice settings.

Must NOT include:
provider AuthToken
Gemini API key
passwords
admin secrets.

52. MIGRATION
Existing schedules without executionMode migrate to NORMAL_SIM.
Never silently convert existing schedules to AI_VOICE.

53. TESTS
Add tests for:
normal SIM compatibility
AI Voice schedule CRUD
provider configuration
invalid credentials
provider health
outbound call creation
webhook signature validation
invalid webhook rejection
WebSocket authentication
AI session connection
greeting
AI response
interruption
silence timeout
max duration
call end
retry
duplicate webhook
duplicate execution
idempotency
backend unavailable
rate limit
prompt version pinning
rollback
RBAC
secret masking
backup excludes secrets
screen OFF AI execution
app CLOSED AI execution
reboot synchronization
Overview counters
History/timeline
failure reasons.

54. REAL DEVICE / REAL TELEPHONY TEST
Separate:
CODE TEST
ANDROID BUILD
BACKEND TEST
PROVIDER TEST
REAL PHONE CALL

Do not claim AI Voice Call works merely because code compiles.

Real test must verify:
phone rings
recipient answers
AI voice is audible
recipient speech is detected when conversational mode is enabled
AI replies
call ends
history records the actual result.

55. ACCEPTANCE CRITERIA
Complete only when:

✓ Normal SIM Call still works
✓ AI Voice Call is separate
✓ AI Voice does not depend on Android screen state
✓ AI Voice does not depend on Activity
✓ backend can initiate call
✓ provider creates real outbound call
✓ recipient hears AI voice
✓ real-time conversation works when enabled
✓ webhook security works
✓ WebSocket security works
✓ prompt version is preserved
✓ secrets stay server-side
✓ provider abstraction exists
✓ duplicate calls are prevented
✓ retries are idempotent
✓ max duration works
✓ silence handling works
✓ real call state is recorded
✓ Overview updates
✓ History updates
✓ Admin controls are protected
✓ normal users cannot edit provider credentials
✓ no unsupported audio injection
✓ no accessibility automation
✓ no forced unlock
✓ no fake success.

56. IMPORTANT ARCHITECTURE DECISION
Do NOT build:
Android TTS + TelecomManager.placeCall() = AI voice heard by recipient.

That is NOT the target.

Target:
AutoCallManager
↓
Admin/Backend
↓
Telephony Provider
↓
Recipient
↕
AI Voice Session

Use ConversationRelay when appropriate, or bidirectional Media Streams when raw audio control is required.

57. FINAL DELIVERABLES
Deliver:
1. Android changes
2. Backend changes
3. Admin Panel changes
4. Provider abstraction
5. Twilio provider implementation
6. AI Voice WebSocket implementation
7. Prompt integration
8. Database/schema migration
9. API routes
10. Webhook handlers
11. Authentication/security middleware
12. Tests
13. README setup instructions
14. Environment-variable template
15. Architecture diagram
16. Migration notes

Provide exact changed file list.

Do not claim completion until:
- syntax checks pass
- backend tests pass
- Android compile passes
- provider integration tests pass
- real phone AI voice test succeeds.
