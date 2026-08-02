import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

// 定義土地坐落結構
data class LandLocation(
    val id: String,
    val county: String,
    val district: String,
    val section: String,
    val subSection: String,
    val landNumber: String,
    val rightScope: String = "",
    val landMinimalPrice: String = "",
    val buildingNumbers: MutableList<String> = mutableListOf()
)

// 定義建物結構
data class BuildingInfo(
    val id: String,
    val buildingNumber: String,
    val baseLocation: String,
    val rightScope: String = "",
    val buildingMinimalPrice: String = ""
)

fun main(args: Array<String>) {
    // 關閉 PDFBox / FontBox 冗長 Log
    Logger.getLogger("org.apache.pdfbox").level = Level.OFF
    Logger.getLogger("org.apache.fontbox").level = Level.OFF

    println("==================================================")
    println("     法院拍賣公告 - 多檔案批次土地與建號解析器     ")
    println("  Court Auction Announcement - Land & Building Parser  ")
    println("==================================================")

    val pdfFiles = if (args.isNotEmpty()) {
        args.map { File(it) }.filter { it.exists() && it.name.lowercase().endsWith(".pdf") }
    } else {
        val projectDir = File("D:/projects/land_number_parse")
        projectDir.listFiles { _, name -> name.lowercase().endsWith(".pdf") }?.sortedBy { it.name } ?: emptyList()
    }

    if (pdfFiles.isEmpty()) {
        println("⚠️ 提示 (Notice)：未找到任何 PDF 檔案 (No PDF files found)。請將拍賣公告 PDF 檔放入 D:/projects/land_number_parse/ 目錄中。")
        return
    }

    println("共找到 ${pdfFiles.size} 個 PDF 檔案 (Found ${pdfFiles.size} PDF files)，開始進行批次解析...\n")

    for ((fileIndex, pdfFile) in pdfFiles.withIndex()) {
        println("==================================================")
        println("📄 檔案 File [${fileIndex + 1}/${pdfFiles.size}]：${pdfFile.name}")
        println("==================================================")

        try {
            PDDocument.load(pdfFile).use { document ->
                val stripper = PDFTextStripper()
                val fullText = stripper.getText(document)

                // 直接跳到「附表：」之後的 Table 表格內文區塊
                val attachmentText = getAttachmentText(fullText)

                val landResults = parseLandLocations(attachmentText)
                val buildingResults = parseBuildings(attachmentText)

                // 若有拍賣土地標的，將建號關聯至對應土地
                if (landResults.isNotEmpty()) {
                    for (building in buildingResults) {
                        for (land in landResults) {
                            if (!land.buildingNumbers.contains(building.buildingNumber)) {
                                land.buildingNumbers.add(building.buildingNumber)
                            }
                        }
                    }
                }

                // 印出土地標的雙語解析結果
                if (landResults.isEmpty()) {
                    println("ℹ️ 【提示 Notice】本案無拍賣土地標的（僅法拍建物）。")
                    println("   No land target in this auction (Building only). 地號 Land Number: (無 None)")
                } else {
                    println("🎯 「土地標的附表」解析結果 Land Target Table Results (共 ${landResults.size} 筆土地標的)：")
                    println("--------------------------------------------------")
                    for ((index, item) in landResults.withIndex()) {
                        println("【土地標的 Land Target ${index + 1}】")
                        if (item.county.isNotBlank()) println("  📍 縣市 City/County:            " + item.county)
                        if (item.district.isNotBlank()) println("  📍 鄉鎮市區 District:           " + item.district)
                        println("  📍 段別 Section:                " + item.section)
                        println("  🔹 小段 Subsection:             " + (if (item.subSection.isBlank()) "(無小段 / 空白 None/Blank)" else item.subSection))
                        println("  📌 地號 Land Number:            " + item.landNumber + " 地號 (Land No. " + item.landNumber + ")")
                        if (item.rightScope.isNotBlank()) println("  ⚖️ 權利範圍 Ownership Scope:    " + item.rightScope)
                        if (item.landMinimalPrice.isNotBlank()) println("  💰 最低拍賣價格 Land Minimal Price: " + item.landMinimalPrice)
                        
                        val bldgText = if (item.buildingNumbers.isNotEmpty()) {
                            item.buildingNumbers.joinToString(", ") { it + " 建號 (Building No. " + it + ")" }
                        } else {
                            "(無對應建號 No Corresponding Building Number)"
                        }
                        println("  🏢 對應建號 Building Number:   " + bldgText)
                        println("--------------------------------------------------")
                    }
                }

                // 印出建物標的雙語解析結果
                if (buildingResults.isNotEmpty()) {
                    println("\n🏢 「建物標的附表」明細 Building Target Table Details (共 ${buildingResults.size} 筆建物標的)：")
                    println("--------------------------------------------------")
                    for ((index, item) in buildingResults.withIndex()) {
                        println("【建物標的 Building Target ${index + 1}】")
                        println("  🏢 建號 Building Number:       " + item.buildingNumber + " 建號 (Building No. " + item.buildingNumber + ")")
                        if (item.baseLocation.isNotBlank()) println("  📍 基地坐落 Base Location:     " + item.baseLocation)
                        if (item.rightScope.isNotBlank()) println("  ⚖️ 權利範圍 Ownership Scope:    " + item.rightScope)
                        if (item.buildingMinimalPrice.isNotBlank()) println("  💰 最低拍賣價格 Building Minimal Price: " + item.buildingMinimalPrice)
                        println("--------------------------------------------------")
                    }
                }
            }
        } catch (e: Exception) {
            println("解析檔案 File ${pdfFile.name} 時發生錯誤 (Error parsing file)：" + e.message)
            e.printStackTrace()
        }
        println("\n")
    }

    println("==================================================")
    println("✅ 所有 ${pdfFiles.size} 個 PDF 檔案批次解析完畢 (Batch Parsing Completed)！")
    println("==================================================")
}

// 截取「附表：」開始的表格區塊，完全忽略前方公告條文
fun getAttachmentText(fullText: String): String {
    val attachmentIndex = fullText.indexOf("附表")
    return if (attachmentIndex != -1) {
        fullText.substring(attachmentIndex)
    } else {
        fullText
    }
}

// 安全取得 MatchResult 命名群組值
fun MatchResult.safeGroupValue(groupName: String): String {
    return try {
        this.groups[groupName]?.value ?: ""
    } catch (e: Exception) {
        ""
    }
}

// 提取權利範圍 (如：全部, 20000分之166, 100000分之264, 1/2)
fun extractRightScope(text: String): String {
    val fractionMatch = Regex("""\d+\s*(?:萬)?\s*分之\s*\d+""").find(text)
    if (fractionMatch != null) {
        return fractionMatch.value.replace(" ", "")
    }
    val slashMatch = Regex("""\d+/\d+""").find(text)
    if (slashMatch != null) {
        return slashMatch.value
    }
    if (text.contains("全部")) {
        return "全部"
    }
    return ""
}

// 提取最低拍賣價格 (如：717,000元, 3,072,000元, 8,300,000元)
fun extractMinimalPrice(text: String): String {
    val priceMatch = Regex("""(?<!合計|保證金)(?<price>\d{1,3}(?:,\d{3})+\s*元|\d+\s*元)""").find(text)
    return priceMatch?.value?.replace(" ", "") ?: ""
}

fun parseLandLocations(attachmentText: String): List<LandLocation> {
    val results = mutableListOf<LandLocation>()
    val lines = attachmentText.lines()

    var isInsideLandTable = false

    val regexes = listOf(
        Regex("""(?:\d+\s+)?(?:(?<county>[\u4e00-\u9fa5]{2,3}[縣市]))?\s*(?:(?<district>[\u4e00-\u9fa5]{2,4}[區市鎮鄉]))?\s*(?<section>[\u4e00-\u9fa5]{2,8})(?:段)?\s+(?:(?<subSection>[\u4e00-\u9fa50-9]+小段)?\s+)?(?<landNo>\d+(?:-\d+)?)\s+"""),
        Regex("""(?:(?<county>[\u4e00-\u9fa5]{2,3}[縣市]))?\s*(?:(?<district>[\u4e00-\u9fa5]{2,4}[區市鎮鄉]))?\s*(?<section>[\u4e00-\u9fa5]{2,8})(?:段)?\s*(?:(?<subSection>[\u4e00-\u9fa50-9]+小段))?\s*(?<landNo>\d+(?:-\d+)?)\s*(?:地號)""")
    )

    for ((index, line) in lines.withIndex()) {
        val trimmed = line.trim()

        if (trimmed.contains("續上頁") || trimmed.contains("續頁")) {
            continue
        }

        if (trimmed.contains("土地坐落") || (trimmed.contains("土") && trimmed.contains("地") && trimmed.contains("坐"))) {
            isInsideLandTable = true
        }

        if (trimmed.contains("建號") || trimmed.contains("建 號") || trimmed.contains("建物面積")) {
            isInsideLandTable = false
        }

        if (isInsideLandTable) {
            for (regex in regexes) {
                val matches = regex.findAll(trimmed)
                for (match in matches) {
                    val county = match.safeGroupValue("county")
                    val district = match.safeGroupValue("district")
                    var section = match.safeGroupValue("section").removeSuffix("段").trim()
                    val subSection = match.safeGroupValue("subSection")
                    val landNo = match.safeGroupValue("landNo")

                    if (landNo.isNotBlank() && section.length >= 2 && 
                        !section.contains("縣") && !section.contains("市") && 
                        !section.contains("區") && !section.contains("附表") && 
                        !section.contains("標的") && !section.contains("編號") &&
                        !section.contains("備考") && !section.contains("坐落")) {
                        
                        val fullSection = section + "段"

                        // 多檢視後續 15 行提取權利範圍與最低拍賣價格
                        val windowText = (index..minOf(index + 15, lines.size - 1)).joinToString(" ") { lines[it] }
                        val scope = extractRightScope(windowText)
                        val price = extractMinimalPrice(windowText)

                        val item = LandLocation(
                            id = (results.size + 1).toString(),
                            county = county,
                            district = district,
                            section = fullSection,
                            subSection = subSection,
                            landNumber = landNo,
                            rightScope = scope,
                            landMinimalPrice = price
                        )

                        if (results.none { it.section == item.section && it.landNumber == item.landNumber }) {
                            results.add(item)
                        }
                    }
                }
            }
        }
    }

    return results
}

fun parseBuildings(attachmentText: String): List<BuildingInfo> {
    val results = mutableListOf<BuildingInfo>()
    val lines = attachmentText.lines()

    var isInsideBuildingTable = false
    val bldgRowRegex = Regex("""^(?:(?<itemNo>\d+)\s+)?(?<bldgNo>\d{2,5})\s+(?<rest>.*)$""")

    for ((index, line) in lines.withIndex()) {
        val trimmed = line.trim()

        if (trimmed.contains("續上頁") || trimmed.contains("續頁")) {
            continue
        }

        if (trimmed.contains("建號") || trimmed.contains("建 號") || trimmed.contains("建物面積") || trimmed.contains("建物門牌")) {
            isInsideBuildingTable = true
        }

        if (isInsideBuildingTable && (trimmed.contains("使用情形") || trimmed.contains("點交情形") || trimmed.contains("點交否"))) {
            isInsideBuildingTable = false
        }

        if (isInsideBuildingTable) {
            if (trimmed.contains("編號") || trimmed.contains("門牌") || trimmed.contains("備考") || 
                trimmed.contains("公尺") || trimmed.contains("權利範圍") || trimmed.contains("第") && trimmed.contains("頁")) {
                continue
            }

            val match = bldgRowRegex.find(trimmed)
            if (match != null) {
                val bldgNo = match.safeGroupValue("bldgNo")
                val rest = match.safeGroupValue("rest")

                if (bldgNo.isNotBlank() && bldgNo.length in 2..5 && 
                    !bldgNo.startsWith("114") && !bldgNo.startsWith("115") && 
                    bldgNo != "122947" && bldgNo != "131969" && bldgNo != "133508" && bldgNo != "90563") {
                    
                    // 多檢視後續 15 行提取建物權利範圍與最低拍賣價格
                    val windowText = (index..minOf(index + 15, lines.size - 1)).joinToString(" ") { lines[it] }
                    val scope = extractRightScope(windowText)
                    val price = extractMinimalPrice(windowText)

                    val item = BuildingInfo(
                        id = (results.size + 1).toString(),
                        buildingNumber = bldgNo,
                        baseLocation = rest,
                        rightScope = scope,
                        buildingMinimalPrice = price
                    )
                    if (results.none { it.buildingNumber == item.buildingNumber }) {
                        results.add(item)
                    }
                }
            }
        }
    }

    return results
}
