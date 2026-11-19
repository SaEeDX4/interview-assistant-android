package com.interviewassistant.core.orchestration

import com.interviewassistant.core.intelligence.ChatMessage
import com.interviewassistant.core.providers.ConnectionStatus
import com.interviewassistant.core.providers.IAnswerProvider
import com.interviewassistant.core.providers.ITranscriber
import com.interviewassistant.core.providers.ProviderException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Mirror of `tests/InterviewAssistant.Tests/Fakes.cs` — the shared test doubles.
 * C# events become the contract listener lists; the async iterator becomes a [Flow]
 * (increments and the throw happen when collection starts, as on first MoveNext).
 */
class FakeTranscriber : ITranscriber {
    override val speechStartedListeners: MutableList<() -> Unit> = mutableListOf()
    override val speechStoppedListeners: MutableList<() -> Unit> = mutableListOf()
    override val partialTranscriptListeners: MutableList<(String, String) -> Unit> = mutableListOf()
    override val segmentCompletedListeners: MutableList<(String, String) -> Unit> = mutableListOf()
    override val statusChangedListeners: MutableList<(ConnectionStatus, String?) -> Unit> = mutableListOf()

    override var status: ConnectionStatus = ConnectionStatus.Disconnected
        private set
    val framesReceived = AtomicLong(0)
    private var id: Int = 0

    override suspend fun startAsync() {
        status = ConnectionStatus.Connected
        for (listener in statusChangedListeners.toList()) listener(status, null)
    }

    override suspend fun stopAsync() {
        status = ConnectionStatus.Disconnected
    }

    override fun sendAudio(pcm16Frame: ByteArray) {
        framesReceived.incrementAndGet()
    }

    override suspend fun disposeAsync() {}

    fun start() {
        for (listener in speechStartedListeners.toList()) listener()
    }

    fun stop() {
        for (listener in speechStoppedListeners.toList()) listener()
    }

    fun complete(text: String, idIn: String? = null): String {
        val itemId = idIn ?: "item" + (++id)
        for (listener in partialTranscriptListeners.toList()) listener(itemId, text.substring(0, minOf(5, text.length)))
        for (listener in segmentCompletedListeners.toList()) listener(itemId, text)
        return itemId
    }

    fun partial(id: String, delta: String) {
        for (listener in partialTranscriptListeners.toList()) listener(id, delta)
    }

    fun raise(s: ConnectionStatus, d: String?) {
        status = s
        for (listener in statusChangedListeners.toList()) listener(s, d)
    }
}

class FakeProvider : IAnswerProvider {
    override val name: String = "fake"
    val requests = AtomicInteger(0)
    val calls: MutableList<List<ChatMessage>> = mutableListOf()
    var responder: (String) -> Array<String> = {
        arrayOf(
            "MODE: HYPOTHETICAL\n",
            "• I'd start by defining the goal ",
            "and the user problem clearly.\n",
            "• Then I'd prioritise by value, risk and effort with Engineering.\n",
            "• Finally I'd measure adoption and retention after release.",
        )
    }
    var throwEx: ProviderException? = null
    var delayMsPerDelta: Int = 0

    override fun streamAsync(messages: List<ChatMessage>, maxTokens: Int): Flow<String> = flow {
        requests.incrementAndGet()
        synchronized(calls) { calls.add(messages) }
        val failure = throwEx
        if (failure != null) throw failure
        for (d in responder(messages.last().content)) {
            if (delayMsPerDelta > 0) delay(delayMsPerDelta.toLong())
            emit(d)
        }
    }
}
