package ai.edlcommander.companion

import ai.edlcommander.companion.help.ManualBlock
import ai.edlcommander.companion.help.ManualParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualParserTest {
    @Test
    fun parsesBlocksAndStripsInlineMarkup() {
        val md = """
            <p><img src="x.svg"></p>

            # Title

            Some **bold** and `code` with a [link](a.md).
            - one
              continued
            1. two

            ```sh
            cargo test
            ```

            | A | B |
            |---|---|
            | x | y |
        """.trimIndent()
        val blocks = ManualParser.parse(md)
        assertEquals(
            listOf(
                ManualBlock.Heading(1, "Title"),
                ManualBlock.Paragraph("Some bold and code with a link."),
                ManualBlock.Bullet("one continued", 0),
                ManualBlock.Bullet("two", 0),
                ManualBlock.Code("cargo test"),
                ManualBlock.Code("A  |  B\nx  |  y"),
            ),
            blocks,
        )
    }

    @Test
    fun bundledAssetMatchesDocsManual() {
        val asset = File("src/main/assets/MANUAL.md")
        val docs = File("../../docs/MANUAL.md")
        assertTrue("asset missing", asset.isFile)
        assertTrue("docs/MANUAL.md missing", docs.isFile)
        assertEquals("Copy docs/MANUAL.md to android/app/src/main/assets/", docs.readText(), asset.readText())
        val headings = ManualParser.parse(asset.readText()).filterIsInstance<ManualBlock.Heading>().map { it.text }
        assertTrue(headings.any { it.startsWith("7. Privacy") })
    }
}
