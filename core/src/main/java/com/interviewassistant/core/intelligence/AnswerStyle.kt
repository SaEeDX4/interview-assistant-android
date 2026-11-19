package com.interviewassistant.core.intelligence

/**
 * Mirror of `InterviewAssistant.Core/Intelligence/AnswerStyle.cs` (TRANSFER-MAP §2, layer: Mirror).
 * Full 1:1 port.
 */

/** Live answer variants. Balanced is the default live format (3 speakable bullets). */
enum class AnswerStyle { Balanced, VeryShort, Technical, Full, Example, Concise, Rapid }

object AnswerStyleSpec {
    /** The C#'s `(BulletCount, WordsPerBullet, TotalWords, Override, MaxTokens)` value tuple. */
    data class Spec(
        val bulletCount: String,
        val wordsPerBullet: String,
        val totalWords: String,
        val override: String,
        val maxTokens: Int,
    )

    fun `for`(style: AnswerStyle): Spec = when (style) {
        AnswerStyle.Concise -> Spec("3", "8-15", "25-45", "The interviewer is moving fast: keep each bullet short and put the direct answer first.", 150)
        AnswerStyle.Rapid -> Spec("2", "6-12", "15-25", "The interviewer keeps interrupting: bullet 1 must directly answer the question; no background or examples.", 100)
        AnswerStyle.VeryShort -> Spec("2-3", "8-16", "25-45", "Make it as short as possible while still complete.", 140)
        AnswerStyle.Technical -> Spec("3-4", "12-24", "50-90", "Be more technical and precise: name mechanisms (states, idempotency keys, invariants, controls), still speakable.", 260)
        AnswerStyle.Example -> Spec("3", "12-22", "40-80", "Ground the answer in ONE concrete example: a verified story from CANDIDATE EVIDENCE if relevant, otherwise a concrete scenario for this role/company phrased as what they WOULD do.", 220)
        AnswerStyle.Full -> Spec("0", "n/a", "90-130", "OVERRIDE: instead of bullets, write ONE compact natural spoken paragraph after the MODE line (no bullet characters).", 320)
        else -> Spec("exactly 3", "10-22", "35-75", "Bullet 1 must be useful on its own (the direct answer); bullets 2-3 add the approach and how to measure or prove it.", 170)
    }

    /** Hard cap on displayed bullets per style: the default view must fit without scrolling. */
    fun maxBullets(style: AnswerStyle): Int = when (style) {
        AnswerStyle.Rapid -> 2
        AnswerStyle.Technical -> 4
        AnswerStyle.Full -> 2
        else -> 3
    }
}
