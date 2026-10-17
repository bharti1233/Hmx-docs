package dev.kern.editors.word

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The editing pipeline: what the text field emits versus what the editor stores.
 *
 * A value-based BasicTextField keeps its own plain-text buffer, so every value it emits —
 * after a tap, a caret move, typing, backspace, paste, autocorrect or an IME composition
 * step — carries the text and the selection but no formatting spans. These tests feed such
 * span-less emissions through [WordRichText.mergeEmission] and [WordRichText.replaceRuns]
 * and check that formatting survives, that real text edits still land, and that the styled
 * result still round-trips through save and reopen.
 */
class WordRichTextTest {

    private val bold = WordDocument.RunStyle(bold = true)
    private val italic = WordDocument.RunStyle(italic = true)
    private val plain = WordDocument.RunStyle()

    private fun run(text: String, style: WordDocument.RunStyle = WordDocument.RunStyle()) =
        WordDocument.Run(text, style)

    /** A stored value with formatting, as the editor holds it. */
    private fun stored(vararg runs: WordDocument.Run, selection: Int = 0) =
        TextFieldValue(WordRichText.toAnnotated(runs.toList()), TextRange(selection))

    /** What the text field really emits: text and selection, never spans. */
    private fun emission(text: String, selection: Int) =
        TextFieldValue(AnnotatedString(text), TextRange(selection))

    private fun runsOf(value: TextFieldValue) = WordRichText.toRuns(value.annotatedString)

    private fun onlyRun(value: TextFieldValue) = runsOf(value).single()

    // ---- value emissions ----------------------------------------------------

    /**
     * The reported defect: a text field emission without spans used to become the stored
     * value, so the formatting of the paragraph disappeared on the next tap, caret move or
     * keystroke. The emission now only contributes text and selection.
     */
    @Test
    fun spanlessEmission_keepsTheFormattingOfUnchangedText() {
        val emitted = emission("Bold words", 7)
        assertTrue("this test is about span-less emissions", emitted.annotatedString.spanStyles.isEmpty())

        val merged = WordRichText.mergeEmission(stored(run("Bold words", bold), selection = 3), emitted)

        assertEquals(7, merged.selection.start)               // the caret move is kept
        assertEquals(bold, onlyRun(merged).style)             // the formatting is kept
        assertEquals(bold, WordRichText.styleAt(merged))      // so the toolbar still shows Bold
    }

    /** Every character attribute the toolbar offers survives a caret move, and the fonts
     *  and link targets the editor only stores for round-tripping survive with them. */
    @Test
    fun spanlessEmission_keepsEveryCharacterAttribute() {
        val styles = listOf(
            bold,
            italic,
            WordDocument.RunStyle(underline = true),
            WordDocument.RunStyle(strike = true),
            WordDocument.RunStyle(sizePt = 18f),
            WordDocument.RunStyle(colorHex = "FF0000"),
            WordDocument.RunStyle(fontFamily = "Cambria"),
            WordDocument.RunStyle(linkUrl = "https://example.example/page"),
        )
        for (style in styles) {
            val merged = WordRichText.mergeEmission(stored(run("Styled", style), selection = 3), emission("Styled", 3))
            assertEquals(style, onlyRun(merged).style)
        }
    }

    /** Several attributes on one run are kept together, not one at a time. */
    @Test
    fun spanlessEmission_keepsCombinedAttributes() {
        val combined = WordDocument.RunStyle(bold = true, italic = true, underline = true, sizePt = 12f, colorHex = "00AA55")
        val merged = WordRichText.mergeEmission(stored(run("Styled", combined), selection = 6), emission("Styled", 2))
        assertEquals(combined, onlyRun(merged).style)
    }

    // ---- typing, deleting, replacing ----------------------------------------

    @Test
    fun typingInsideAStyledRun_keepsTheRunStyled() {
        for (style in listOf(bold, italic, WordDocument.RunStyle(sizePt = 18f), WordDocument.RunStyle(colorHex = "00AA55"))) {
            val merged = WordRichText.mergeEmission(stored(run("set", style), selection = 1), emission("sXet", 2))
            assertEquals(listOf(run("sXet", style)), runsOf(merged))
        }
    }

    @Test
    fun typingAtTheEndOfAStyledRun_inheritsThatStyle() {
        val merged = WordRichText.mergeEmission(stored(run("bold", bold), selection = 4), emission("boldX", 5))
        assertEquals(listOf(run("boldX", bold)), runsOf(merged))
    }

    @Test
    fun typingAtTheStartOfAStyledRun_inheritsTheFollowingStyle() {
        val merged = WordRichText.mergeEmission(stored(run("bold", bold), selection = 0), emission("Xbold", 1))
        assertEquals(listOf(run("Xbold", bold)), runsOf(merged))
    }

    /** Typing at a run boundary takes the preceding style and leaves the other run alone. */
    @Test
    fun typingAtTheBoundaryOfTwoStyles_keepsBothRuns() {
        val merged = WordRichText.mergeEmission(
            stored(run("plain", plain), run("bold", bold), selection = 5),
            emission("plainXbold", 6),
        )
        assertEquals(listOf(run("plainX", plain), run("bold", bold)), runsOf(merged))
    }

    @Test
    fun pastingTextAtABoundary_keepsBothNeighbours() {
        val merged = WordRichText.mergeEmission(
            stored(run("a ", bold), run("z", italic), selection = 2),
            emission("a bcdz", 5),
        )
        assertEquals(listOf(run("a bcd", bold), run("z", italic)), runsOf(merged))
    }

    @Test
    fun deletingInsideAStyledRun_keepsItStyled() {
        val merged = WordRichText.mergeEmission(stored(run("bold", bold), selection = 3), emission("bod", 2))
        assertEquals(listOf(run("bod", bold)), runsOf(merged))
    }

    /** Backspace at a style boundary keeps the style of everything that is left. */
    @Test
    fun deletingAcrossAStyleBoundary_keepsTheSurvivingStyles() {
        val merged = WordRichText.mergeEmission(
            stored(run("bold", bold), run("tail", italic), selection = 4),
            emission("boltail", 3),
        )
        assertEquals(listOf(run("bol", bold), run("tail", italic)), runsOf(merged))
    }

    /** Deleting the tail of one run and the head of the next keeps what is left. */
    @Test
    fun deletingAcrossTwoStyledRuns_keepsTheRemainingStyles() {
        val merged = WordRichText.mergeEmission(
            stored(run("aa", bold), run("bb", italic), selection = 3),
            emission("ab", 1),
        )
        assertEquals(listOf(run("a", bold), run("b", italic)), runsOf(merged))
    }

    /** Typing over a selection replaces the text and takes the style it replaced. */
    @Test
    fun typingOverASelection_keepsTheStyleOfTheReplacedText() {
        val merged = WordRichText.mergeEmission(
            TextFieldValue(WordRichText.toAnnotated(listOf(run("hello", bold))), TextRange(0, 5)),
            TextFieldValue(AnnotatedString("hi"), TextRange(2)),
        )
        assertEquals(listOf(run("hi", bold)), runsOf(merged))
    }

    /** An autocorrect rewrite keeps the corrected word's own formatting. */
    @Test
    fun autocorrect_keepsTheCorrectedWordStyled() {
        val merged = WordRichText.mergeEmission(
            stored(run("The ", plain), run("teh", bold), run(" cat", plain), selection = 7),
            emission("The the cat", 7),
        )
        assertEquals(listOf(run("The ", plain), run("the", bold), run(" cat", plain)), runsOf(merged))
    }

    // ---- IME composition and repeated emissions -----------------------------

    @Test
    fun compositionSteps_areAppliedOnceEachAndKeepTheStyle() {
        var value = stored(run("hi", bold), selection = 2)
        // "h", "he", "he!" as an IME composes at the end, then the commit without a composition.
        value = WordRichText.mergeEmission(value, TextFieldValue(AnnotatedString("hih"), TextRange(3), TextRange(2, 3)))
        assertEquals(2, value.composition?.start)
        assertEquals(3, value.composition?.end)
        value = WordRichText.mergeEmission(value, TextFieldValue(AnnotatedString("hihe"), TextRange(4), TextRange(2, 4)))
        value = WordRichText.mergeEmission(value, TextFieldValue(AnnotatedString("hihe!"), TextRange(5), TextRange(2, 5)))
        value = WordRichText.mergeEmission(value, TextFieldValue(AnnotatedString("hihe!"), TextRange(5)))

        assertEquals("hihe!", value.text)
        assertNull(value.composition)
        assertEquals(listOf(run("hihe!", bold)), runsOf(value))
    }

    /** The same emission twice must not type the character twice. */
    @Test
    fun repeatingAnEmission_doesNotApplyTheEditTwice() {
        val emitted = emission("boldX", 5)
        val once = WordRichText.mergeEmission(stored(run("bold", bold), selection = 4), emitted)
        val twice = WordRichText.mergeEmission(once, emitted)
        assertEquals("boldX", twice.text)
        assertEquals(listOf(run("boldX", bold)), runsOf(twice))
    }

    /** A toolbar formatting change is never overwritten by a later span-less emission. */
    @Test
    fun aLaterEmissionDoesNotUndoAToolbarChange() {
        val formatted = WordRichText.restyleSelection(stored(run("hello", plain), selection = 0)) { it.copy(bold = true) }
        val afterTap = WordRichText.mergeEmission(formatted, emission("hello", 2))
        assertEquals(listOf(run("hello", bold)), runsOf(afterTap))
        assertEquals(bold, WordRichText.styleAt(afterTap))
    }

    // ---- toolbar formatting meets typing ------------------------------------

    /** Bold on a collapsed caret, then typing: the paragraph keeps the formatting. */
    @Test
    fun formattingAppliedByTheToolbarSurvivesTyping() {
        val value = stored(run("hello", plain), selection = 0)
        val formatted = WordRichText.restyleSelection(value) { it.copy(bold = true) }
        val typed = WordRichText.mergeEmission(formatted, emission("helloX", 6))
        assertEquals(listOf(run("helloX", bold)), runsOf(typed))
        assertEquals(bold, WordRichText.styleAt(typed))
    }

    // ---- find & replace -----------------------------------------------------

    @Test
    fun replaceAll_keepsTheFormattingAroundEveryReplacement() {
        val runs = listOf(run("Bold ", bold), run("plain", plain))
        assertEquals(listOf(run("BoLd ", bold), run("pLain", plain)), WordRichText.replaceRuns(runs, "l", "L"))
    }

    @Test
    fun replaceOne_touchesOnlyThatMatch() {
        val runs = listOf(run("x x x", bold))
        assertEquals(listOf(run("x Y x", bold)), WordRichText.replaceRuns(runs, "x", "Y", only = 1))
    }

    @Test
    fun replaceIsCaseInsensitiveAndKeepsAnUntouchedTextAlone() {
        val runs = listOf(run("Hello world", italic))
        assertEquals(listOf(run("hi world", italic)), WordRichText.replaceRuns(runs, "HELLO", "hi"))
        assertEquals(runs, WordRichText.replaceRuns(runs, "absent", "x"))
    }

    @Test
    fun replacingAMatchWithNothing_deletesItAndKeepsTheRest() {
        val runs = listOf(run("keep", bold), run("-drop", italic))
        assertEquals(listOf(run("keep", bold)), WordRichText.replaceRuns(runs, "-drop", ""))
    }

    /** Two matches in differently styled runs each keep their own run's formatting. */
    @Test
    fun replaceAll_keepsEachMatchInItsOwnRun() {
        val runs = listOf(run("ab", plain), run("ba", bold))
        assertEquals(listOf(run("aB", plain), run("Ba", bold)), WordRichText.replaceRuns(runs, "b", "B"))
    }

    // ---- save and reopen ----------------------------------------------------

    /** A paragraph with a bold word and a plain tail, as Word would write it. */
    private fun boldTailDocx(): ByteArray {
        val out = ByteArrayOutputStream()
        XWPFDocument().use { doc ->
            doc.createParagraph().also { p ->
                p.createRun().also { r -> r.setText("Bold"); r.isBold = true }
                p.createRun().setText(" tail")
            }
            doc.write(out)
        }
        return out.toByteArray()
    }

    @Test
    fun anEditThroughTheFieldPipeline_survivesSaveAndReopen() {
        val bytes = boldTailDocx()
        val block = WordDocument.read(bytes).blocks.single() as WordDocument.ParagraphBlock
        val stored = TextFieldValue(WordRichText.toAnnotated(block.runs), TextRange(block.text.length))

        // Type "!" at the end the way the field reports it: new text, no spans.
        val edited = WordRichText.mergeEmission(stored, emission(block.text + "!", block.text.length + 1))
        val saved = WordDocument.applyEditsAndSerialize(bytes, mapOf(0 to runsOf(edited)))

        val reread = WordDocument.read(saved).blocks.single() as WordDocument.ParagraphBlock
        assertEquals("Bold tail!", reread.text)
        assertEquals("Bold", reread.runs.first().text)
        assertTrue("the bold run is still bold", reread.runs.first().style.bold)
        assertFalse("the typed tail is not bold", reread.runs.last().style.bold)
    }
}
