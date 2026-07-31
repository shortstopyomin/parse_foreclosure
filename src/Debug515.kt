import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

fun main() {
    val file = File("D:/projects/land_number_parse/114-90563_taoyuan_nobatch.pdf")
    println("=== DEBUG: " + file.name + " ===")
    PDDocument.load(file).use { doc ->
        println("Page count: " + doc.numberOfPages)
        val stripper = PDFTextStripper()
        for (page in 1..doc.numberOfPages) {
            stripper.startPage = page
            stripper.endPage = page
            val pageText = stripper.getText(doc)
            println("--- PAGE " + page + " ---")
            pageText.lines().forEachIndexed { i, line ->
                if (line.contains("515") || line.contains("288") || line.contains("建號") || line.contains("地號") || line.contains("備考") || line.contains("(續上頁)")) {
                    println("Line " + i + ": " + line.trim())
                }
            }
        }
    }
}
