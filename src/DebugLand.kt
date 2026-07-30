import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

fun main() {
    val files = listOf(
        File("D:/projects/land_number_parse/114-122947-KS-ginding.pdf"),
        File("D:/projects/land_number_parse/114-131969-kaohsiung_nobatch.pdf")
    )

    for (file in files) {
        println("=== DEBUG: " + file.name + " ===")
        PDDocument.load(file).use { doc ->
            val text = PDFTextStripper().getText(doc)
            val lines = text.lines()
            var inLand = false
            lines.forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (trimmed.contains("土") && trimmed.contains("坐")) {
                    println("Land start line " + i + ": " + trimmed)
                    inLand = true
                }
                if (trimmed.contains("建號")) {
                    println("Building start line " + i + ": " + trimmed)
                    inLand = false
                }
                if (inLand) {
                    println("LAND REGION [" + i + "]: " + trimmed)
                }
            }
        }
    }
}
