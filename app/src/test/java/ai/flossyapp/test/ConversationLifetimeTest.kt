package ai.flossyapp.test

import ai.flossyapp.domain.model.ChatMessage
import ai.flossyapp.ui.common.ConversationStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The conversation-lifetime rules the store owns: a conversation survives a
 * hang-up while any surface shows it, and truly ends only when the model
 * ends the call itself or every surface has closed with no call live.
 */
class ConversationLifetimeTest {

    @Before
    fun reset() {
        // The store is a process-wide singleton, so every test starts from
        // the same observable state through the public API alone.
        ConversationStore.setAppSurfaceOpen(false)
        ConversationStore.setPopupSurfaceOpen(false)
        ConversationStore.setAssistantSurfaceOpen(false)
        ConversationStore.setCallActive(false)
        ConversationStore.clear()
        ConversationStore.markConversationOpen()
    }

    @Test
    fun aConversationEndedByTheModelIsOverAtOnce() {
        ConversationStore.setAppSurfaceOpen(true)
        ConversationStore.markConversationEndedByModel()
        assertTrue(ConversationStore.conversationEnded)
    }

    @Test
    fun aHangUpWithTheAppStillOpenDoesNotEndTheConversation() {
        ConversationStore.setAppSurfaceOpen(true)
        ConversationStore.setCallActive(false) // the call finished, the app is on screen
        assertFalse(ConversationStore.conversationEnded)
    }

    @Test
    fun theAssistantServiceIsASurfaceToo() {
        // The always-listening service claims a surface while its foreground
        // notification is live: a hang-up there ends the call but not the
        // conversation.
        ConversationStore.setAssistantSurfaceOpen(true)
        ConversationStore.setCallActive(false)
        assertFalse(ConversationStore.conversationEnded)
        ConversationStore.setAssistantSurfaceOpen(false)
        assertTrue(ConversationStore.conversationEnded)
    }

    @Test
    fun closingEverySurfaceWithNoCallEndsTheConversation() {
        ConversationStore.setAppSurfaceOpen(true)
        ConversationStore.setPopupSurfaceOpen(true)
        ConversationStore.setPopupSurfaceOpen(false)
        assertFalse(ConversationStore.conversationEnded)
        ConversationStore.setAppSurfaceOpen(false)
        assertTrue(ConversationStore.conversationEnded)
    }

    @Test
    fun aCallKeptAliveAfterEverySurfaceClosedDoesNotEndIt() {
        // The user went home mid-call: no surface, but the call is live.
        ConversationStore.setAppSurfaceOpen(false)
        ConversationStore.setPopupSurfaceOpen(false)
        ConversationStore.setAssistantSurfaceOpen(false)
        ConversationStore.setCallActive(true)
        assertFalse(ConversationStore.conversationEnded)
        // The call then finished on its own: only then is it over.
        ConversationStore.setCallActive(false)
        assertTrue(ConversationStore.conversationEnded)
    }

    @Test
    fun idempotentSurfaceChangesDoNotDriftTheState() {
        ConversationStore.setAppSurfaceOpen(true)
        ConversationStore.setAppSurfaceOpen(true) // duplicate open
        ConversationStore.setAppSurfaceOpen(true)
        ConversationStore.setAppSurfaceOpen(false)
        assertTrue(ConversationStore.conversationEnded)
    }

    @Test
    fun aNewConversationClearsTheEndedMark() {
        ConversationStore.markConversationEndedByModel()
        assertTrue(ConversationStore.conversationEnded)
        ConversationStore.markConversationOpen()
        assertFalse(ConversationStore.conversationEnded)
    }

    @Test
    fun theTranscriptOutlivesTheEndedMarkForTheNextView() {
        ConversationStore.setAppSurfaceOpen(true)
        ConversationStore.addMessage(ChatMessage.user("hello"))
        ConversationStore.setAppSurfaceOpen(false)
        assertTrue(ConversationStore.conversationEnded)
        // The messages stay until a NEW conversation clears them, so the
        // next surface that opens can still show what was said.
        assertTrue(ConversationStore.messages.value.any { it.content == "hello" })
    }
}
