package com.interviewassistant.app

import android.app.Application

/**
 * Mirror target of `InterviewAssistant.App/App.xaml.cs` (TRANSFER-MAP §2, layer: Platform).
 *
 * P1 PLACEHOLDER — the Windows startup duties (global exception handlers, log prune 14 d,
 * self-test isolation mode) are wired in later phases; nothing here changes behaviour yet.
 */
class InterviewAssistantApplication : Application()
