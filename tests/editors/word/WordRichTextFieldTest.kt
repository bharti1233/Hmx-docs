package dev.kern.editors.word

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FIELD = "paragraph"

/**
 * The rich-text pipeline against a real Compose text field, run on the JVM by Robolectric.
 *
 * [WordRichTextTest] checks the merge policy on its own and feeds it the span-less values a
 * value-based text field is known to produce. This test drives the field itself, through the
 * same `EditProcessor` command path an IME uses, so that a Compose change in how the field
 * reports an edit shows up here rather than in a user's document. It proves the field keeps
 * the editor's formatting after real input; it does not prove a particular device IME's
 * command batches (that is the device checklist, docs/docx-preservation.md §8 step 24).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordRichTextFieldTest {

    @get:Rule
    val rule = createComposeRule()

    private val bold = WordDocument.RunStyle(bold = true)
    private val plain = WordDocument.RunStyle()

    /** Every value the field handed back, in order. */
    private val emissions = mutableListOf<TextFieldValue>()

    /**
     * One paragraph field wired exactly like `EditableParagraph` in the editor: the field's
     * value is the editor's, and every emission is merged into it.
     */
    private fun showField(initialRuns: List<WordDocument.Run>, caret: Int): MutableState<TextFieldValue> {
        emissions.clear()
        val state = mutableStateOf(TextFieldValue(WordRichText.toAnnotated(initialRuns), TextRange(caret)))
        rule.setContent {
            BasicTextField(
                value = state.value,
                onValueChange = { emitted ->
                    emissions.add(emitted)
                    state.value = WordRichText.mergeEmission(state.value, emitted)
                },
                modifier = Modifier.testTag(FIELD),
            )
        }
        return state
    }

    private fun type(text: String) {
        rule.onNodeWithTag(FIELD).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag(FIELD).performTextInput(text)
        rule.waitForIdle()
    }

    /** True when the character at [position] sits in a bold run. */
    private fun boldAt(value: TextFieldValue, position: Int): Boolean {
        var end = 0
        for (run in WordRichText.toRuns(value.annotatedString)) {
            end += run.text.length
            if (position < end) return run.style.bold
        }
        return false
    }

    /**
     * The premise of the merge policy, checked against the real field: what it hands back
     * carries text and selection, never spans. If a future Compose starts preserving them,
     * this is the test to delete and `mergeEmission` the code to simplify.
     */
    @Test
    fun theFieldEmitsValuesWithoutFormatting() {
        showField(listOf(WordDocument.Run("bold", bold)), caret = 4)
        type("!")
        assertTrue("the field emitted something", emissions.isNotEmpty())
        assertTrue(
            "emissions carried spans: ${emissions.map { it.annotatedString.spanStyles.size }}",
            emissions.all { it.annotatedString.spanStyles.isEmpty() },
        )
    }

    @Test
    fun typedTextKeepsTheBoldParagraphBold() {
        val state = showField(listOf(WordDocument.Run("bold", bold)), caret = 4)
        type("!")
        assertEquals(5, state.value.text.length)
        assertTrue(state.value.text.contains("!"))
        assertEquals(1, WordRichText.toRuns(state.value.annotatedString).size)
        assertTrue("the run is still bold", boldAt(state.value, 0))
    }

    @Test
    fun typingKeepsBothRunsOfAMixedParagraph() {
        val state = showField(
            listOf(WordDocument.Run("bold", bold), WordDocument.Run(" plain", plain)),
            caret = 9,
        )
        type("!")
        assertEquals(10, state.value.text.length)
        assertEquals(2, WordRichText.toRuns(state.value.annotatedString).size)
        assertTrue("the bold run is still bold", boldAt(state.value, state.value.text.indexOf('b')))
        assertFalse("the plain run is still plain", boldAt(state.value, state.value.text.indexOf('p')))
    }

    @Test
    fun tappingTheTextKeepsTheFormatting() {
        val state = showField(
            listOf(WordDocument.Run("bold", bold), WordDocument.Run(" plain", plain)),
            caret = 9,
        )
        rule.onNodeWithTag(FIELD).performClick()
        rule.waitForIdle()
        assertEquals("bold plain", state.value.text)
        assertEquals(2, WordRichText.toRuns(state.value.annotatedString).size)
        assertTrue(boldAt(state.value, 0))
        assertFalse(boldAt(state.value, state.value.text.indexOf('p')))
    }

    /** The reported sequence: tap Bold (a formatting change), then type. */
    @Test
    fun typingAfterAFormattingChangeKeepsIt() {
        val state = showField(listOf(WordDocument.Run("hello", plain)), caret = 0)
        state.value = WordRichText.restyleSelection(state.value) { it.copy(bold = true) }
        rule.waitForIdle()
        type("X")
        assertEquals(6, state.value.text.length)
        assertEquals(1, WordRichText.toRuns(state.value.annotatedString).size)
        assertTrue("the formatting change survived the keystroke", boldAt(state.value, 0))
    }
}
