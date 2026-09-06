package com.example.autocallmanager

import android.content.Context

/**
 * Manual-calling entry point for the Phone module (PHASE 2 / PHASE 4 in the
 * master prompt).
 *
 * This deliberately does NOT reimplement Telecom calling. It delegates
 * straight to [CallManager.placeCallDetailed], the exact function the
 * Scheduler already uses for SIM calls -- same number validation, same
 * CALL_PHONE/READ_PHONE_STATE checks, same "already in a call" guard, same
 * multi-SIM PhoneAccountHandle resolution, same ExecutionTraceStore
 * logging. That is the "common CallController" the master prompt's PHASE 4
 * diagram describes (PHONE / SCHEDULER / AI VOICE all ultimately reaching
 * one Telecom call path) -- it already exists, so this object exposes it
 * to the Phone module rather than duplicating it.
 *
 * [taskId]/[executionId]/[attempt] are left at their defaults (0L/""/0),
 * which CallManager's own KDoc documents as the correct choice for any
 * caller that isn't part of the Scheduler's tracing -- it only changes
 * ExecutionTraceStore log labels, never call-placement behavior.
 *
 * [callSource] is explicitly passed as "MANUAL" below (not left at
 * placeCallDetailed's own "SCHEDULED" default). Every call this object
 * places is a free dial from the Phone module with no CallTask behind it
 * at all, so it must never be mistaken in history/diagnostics/trace for an
 * alarm-driven Scheduler attempt on some schedule.
 */
object PhoneCallController {

    data class DialResult(val ok: Boolean, val code: String, val message: String)

    fun isValidNumber(raw: String): Boolean = CallManager.isValidPhoneNumber(raw)

    fun simAccounts(context: Context): List<CallManager.SimAccountOption> =
        CallManager.activeSimAccounts(context)

    fun call(
        context: Context,
        number: String,
        phoneAccountComponent: String = "",
        phoneAccountId: String = ""
    ): DialResult {
        val r = CallManager.placeCallDetailed(
            context = context,
            number = number,
            phoneAccountComponent = phoneAccountComponent,
            phoneAccountId = phoneAccountId,
            callSource = "MANUAL"
        )
        return DialResult(r.ok, r.code, r.message)
    }
}
