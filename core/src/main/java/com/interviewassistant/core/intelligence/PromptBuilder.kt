package com.interviewassistant.core.intelligence

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/PromptBuilder.cs` (TRANSFER-MAP §2, layer: Mirror).
 *
 * P2 slice: [ChatMessage] only — it is the one type from this file that `:client` consumes
 * (`CloudAnswerProvider`). `ConversationTurn`, `PromptRequest` and the `PromptBuilder` class itself are
 * ported in Phase P3 with the rest of the Intelligence package (TRANSFER-MAP §7); they are not stubbed
 * here, they are simply not part of this file yet.
 */

data class ChatMessage(val role: String, val content: String)
