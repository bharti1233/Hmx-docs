package dev.kern.editors.word

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What a failed Word open tells the user, decided without Android: a parser's refusal of
 * the bytes, a denied grant, and an unknown provider or storage failure must not be
 * described as each other. The screen shows this decision as-is, so it is pinned here in
 * a plain JVM test as well as through the ViewModel in
 * [WordEditorViewModelStructureTest].
 */
class WordOpenFailureTest {

    /** A refusal that really came out of the parser, not a hand-made one. */
    private fun parserRefusal(): WordDocument.UnreadableContent =
        runCatching { WordDocument.read(ByteArray(0)) }
            .exceptionOrNull() as WordDocument.UnreadableContent

    @Test
    fun aParserRefusal_isReportedAsWhatTheBytesAre_andKeepsItsRetryDecision() {
        val refusal = parserRefusal()
        val problem = wordOpenFailure(refusal, readGrantHeld = false)

        assertEquals(refusal.userMessage, problem.message)
        assertEquals(refusal.canRetry, problem.canRetry)
        assertFalse(
            "a bad document must not be blamed on the permission, was: ${problem.message}",
            problem.message.contains("expired", ignoreCase = true),
        )
    }

    @Test
    fun aDeniedGrant_stillSaysTheAccessExpired_andIsNotBlamedOnTheDocument() {
        val denial = SecurityException(
            "Permission Denial: reading com.android.providers.media.MediaDocumentsProvider URI " +
                "content://com.android.providers.media.documents/document/document:1 from pid=1, uid=1",
        )
        val problem = wordOpenFailure(denial, readGrantHeld = false)

        assertTrue("was: ${problem.message}", problem.message.contains("expired", ignoreCase = true))
        assertFalse("no document wording, was: ${problem.message}", problem.message.contains(".docx"))
        assertTrue("picking the file again is the way out", problem.canRetry)
    }

    @Test
    fun anUnknownProviderFailure_keepsTheGenericText_ratherThanBlamingTheDocument() {
        // Requirement: not every IOException is a malformed DOCX — a stalled stream is the
        // provider's problem and the message must stay true for both.
        val problem = wordOpenFailure(IOException("the stream stalled"), readGrantHeld = false)

        assertEquals("Could not open the document.", problem.message)
        assertFalse("the document is not called damaged", problem.message.contains("damaged"))
        assertTrue("a provider that comes back can be tried again", problem.canRetry)
    }
}
