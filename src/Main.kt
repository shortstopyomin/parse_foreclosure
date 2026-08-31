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
    val buildingMinimalPrice: String = "",
    val totalFloors: Int? = null,
    val locatedFloor: String = "",
    val buildingType: String = "" // "house", "elevator", "walk-up"
)

fun main(args: Array<String>) {
    // 徹底關閉 PDFBox / FontBox 冗長 Log 與 Warning
    System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog")
    Logger.getLogger("org.apache.pdfbox").level = Level.OFF
    Logger.getLogger("org.apache.fontbox").level = Level.OFF
    Logger.getLogger("org.apache.fontbox.ttf").level = Level.OFF
    Logger.getLogger("org.apache.fontbox.ttf.CmapSubtable").level = Level.OFF

    println("==================================================")
    println("     法院拍賣公告 - 多檔案批次土地與建號解析器     ")
    println("  Court Auction Announcement - Land & Building Parser  ")
    println("==================================================")

    val pdfFiles = if (args.isNotEmpty()) {
        args.map { File(it) }.filter { it.exists() && it.name.lowercase().endsWith(".pdf") }
    } else {
        val projectDir = File(".")
        projectDir.listFiles { _, name -> name.lowercase().endsWith(".pdf") }?.sortedBy { it.name } ?: emptyList()
    }

    if (pdfFiles.isEmpty()) {
        println("⚠️ 提示 (Notice)：未找到任何 PDF 檔案 (No PDF files found)。請將拍賣公告 PDF 檔放入專案目錄中。")
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

                // 解析投標日與時間
                val biddingDateTime = extractBiddingDateTime(fullText)
                if (biddingDateTime.isNotBlank()) {
                    println("⏰ 投標日時間 Bidding Date & Time: " + biddingDateTime)
                    println("--------------------------------------------------")
                }

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
                    println("   No land target in this auction (Building only). 地號 Land Number: None")
                } else {
                    println("🎯 「土地標的附表」解析結果 Land Target Table Results (共 ${landResults.size} 筆土地標的)：")
                    println("--------------------------------------------------")
                    for ((index, item) in landResults.withIndex()) {
                        println("【土地標的 Land Target ${index + 1}】")
                        if (item.county.isNotBlank()) println("  📍 縣市 City/County:            " + item.county)
                        if (item.district.isNotBlank()) println("  📍 鄉鎮市區 District:           " + item.district)
                        println("  📍 段別 Section:                " + item.section)
                        println("  🔹 小段 Subsection:             " + (if (item.subSection.isBlank()) "(無小段 / 空白 None/Blank)" else item.subSection))
                        println("  📌 地號 Land Number:            " + (if (item.landNumber.isBlank()) "None" else item.landNumber + " 地號 (Land No. " + item.landNumber + ")"))
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
                        if (item.buildingType.isNotBlank()) println("  🏠 建物型態 Building Type:     " + formatBuildingType(item.buildingType))
                        if (item.baseLocation.isNotBlank()) println("  📍 基地坐落 Base Location:     " + item.baseLocation)
                        if (item.totalFloors != null) println("  🏢 房屋層數 Total Floors:      " + item.totalFloors + " 層 (" + item.totalFloors + " Stories)")
                        if (item.locatedFloor.isNotBlank()) println("  🚪 所在樓層 Located Floor:     " + formatLocatedFloor(item.locatedFloor))
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

// 格式化投標日時間為標準格式 (例如: 115/09/10 14:30)
fun formatBiddingDateTime(raw: String): String {
    if (raw.isBlank()) return ""

    val regex = Regex("""(?<year>\d{2,3})\s*年\s*(?<month>\d{1,2})\s*月\s*(?<day>\d{1,2})\s*日\s*(?:(?<period>上午|早上|下午|中午|晚上))?\s*(?<hour>\d{1,2})\s*(?:時|點)\s*(?:(?<minute>\d{1,2})\s*分|整)?""")
    val match = regex.find(raw) ?: return raw

    val year = match.groups["year"]?.value?.toIntOrNull() ?: return raw
    val month = match.groups["month"]?.value?.toIntOrNull() ?: return raw
    val day = match.groups["day"]?.value?.toIntOrNull() ?: return raw
    val period = match.groups["period"]?.value ?: ""
    var hour = match.groups["hour"]?.value?.toIntOrNull() ?: return raw
    val minute = match.groups["minute"]?.value?.toIntOrNull() ?: 0

    if (period in listOf("下午", "晚上")) {
        if (hour < 12) {
            hour += 12
        }
    } else if (period in listOf("上午", "早上")) {
        if (hour == 12) {
            hour = 0
        }
    }

    return String.format("%d/%02d/%02d %02d:%02d", year, month, day, hour, minute)
}

// 提取投標日與時間並格式化為標準格式 (如：115/09/10 14:30)
fun extractBiddingDateTime(fullText: String): String {
    val normalized = fullText.lines().map { it.trim() }.joinToString(" ")

    // 優先策略 1: 公告主文「投標日、時及場所：115年9月15日上午9時30分起」或「115年9月17日下午2時30分起」
    val noticeRegex = Regex("""投標日[、，\s]*時(?:及場所)?[:：]\s*(?:中華民國)?\s*(?<datetime>\d{2,3}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日\s*(?:上午|早上|下午|中午|晚上)?\s*\d{1,2}\s*(?:時|點)\s*(?:\d{1,2}\s*分)?(?:\s*起)?)""")
    val match1 = noticeRegex.find(normalized)
    if (match1 != null) {
        val raw = match1.groups["datetime"]?.value?.replace(" ", "") ?: ""
        return formatBiddingDateTime(raw)
    }

    // 策略 2: 備註欄「投標日期：中華民國115年9月16日下午2點30分到3點30分」
    val remarksRegex = Regex("""投標日(?:期)?[:：]\s*(?:中華民國)?\s*(?<datetime>\d{2,3}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日\s*(?:上午|早上|下午|中午|晚上)?\s*\d{1,2}\s*(?:時|點)\s*(?:\d{1,2}\s*分)?(?:\s*(?:到|至|起)\s*(?:上午|早上|下午|中午|晚上)?\s*\d{1,2}\s*(?:時|點)\s*(?:\d{1,2}\s*分)?)?)""")
    val match2 = remarksRegex.find(normalized)
    if (match2 != null) {
        val raw = match2.groups["datetime"]?.value?.replace(" ", "") ?: ""
        return formatBiddingDateTime(raw)
    }

    return ""
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

// 中文數字轉換為整數 (例如 "五" -> 5, "十二" -> 12, "14" -> 14)
fun chineseNumberToInt(cn: String): Int? {
    val trimmed = cn.trim()
    trimmed.toIntOrNull()?.let { return it }
    
    val map = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    if (trimmed == "十") return 10
    if (trimmed.startsWith("十")) {
        val digit = map[trimmed.getOrNull(1)] ?: 0
        return 10 + digit
    }
    if (trimmed.endsWith("十")) {
        val digit = map[trimmed[0]] ?: 1
        return digit * 10
    }
    if (trimmed.length == 3 && trimmed[1] == '十') {
        val tens = map[trimmed[0]] ?: 1
        val ones = map[trimmed[2]] ?: 0
        return tens * 10 + ones
    }
    if (trimmed.length == 1 && map.containsKey(trimmed[0])) {
        return map[trimmed[0]]
    }
    return null
}

// 格式化所在樓層為雙語字串
fun formatLocatedFloor(floor: String): String {
    val num = floor.toIntOrNull()
    if (num != null) {
        val ordinal = when {
            num % 100 in 11..13 -> "${num}th"
            num % 10 == 1 -> "${num}st"
            num % 10 == 2 -> "${num}nd"
            num % 10 == 3 -> "${num}rd"
            else -> "${num}th"
        }
        return "$num 樓 ($ordinal Floor)"
    }
    if (floor.contains("~")) {
        return "$floor 樓 (Floors $floor)"
    }
    if (floor.contains("地下")) {
        return "$floor (Basement)"
    }
    if (floor.contains("頂")) {
        return "$floor (Rooftop)"
    }
    return floor
}

// 格式化建物型態為雙語字串
fun formatBuildingType(type: String): String {
    return when (type.lowercase()) {
        "house" -> "透天 (House / house)"
        "elevator" -> "大樓 (Elevator Building / elevator)"
        "walk-up" -> "公寓 (Walk-up Apartment / walk-up)"
        else -> type
    }
}

// 提取權利範圍 (如：全部, 20000分之166, 100000分之264, 1/2)
fun extractRightScope(text: String): String {
    // 移除備考之後的文字，避免誤取備考欄位中的持分註記（例如：備考 各1/2、備考 各83/20000）
    val textBeforeRemarks = if (text.contains("備考")) {
        text.substringBefore("備考")
    } else {
        text
    }

    // 優先策略 1: 緊鄰最低拍賣價格前方的權利範圍 (例如 "全部 3,072,000元", "1分之1 2,816,000元", "10000分之61 948,000元")
    val priceAdjacentRegex = Regex("""(?<scope>全部|\d+\s*(?:萬)?\s*分\s*之\s*\d+|\d+/\d+)\s+(?=\d{1,3}(?:,\d{3})+\s*元|\d+\s*元)""")
    val adjacentMatch = priceAdjacentRegex.find(textBeforeRemarks) ?: priceAdjacentRegex.find(text)
    if (adjacentMatch != null) {
        return adjacentMatch.groups["scope"]!!.value.replace(Regex("""\s+"""), "")
    }

    // 策略 2: 中文分數格式 (例如 20000分之166, 100000分之264, 5分之1)
    val fractionMatch = Regex("""\d+\s*(?:萬)?\s*分\s*之\s*\d+""").find(textBeforeRemarks) 
        ?: Regex("""\d+\s*(?:萬)?\s*分\s*之\s*\d+""").find(text)
    if (fractionMatch != null) {
        return fractionMatch.value.replace(Regex("""\s+"""), "")
    }

    // 策略 3: 「全部」
    if (textBeforeRemarks.contains("全部") || text.contains("全部")) {
        return "全部"
    }

    // 策略 4: 斜線分數 (例如 1/2)，需確保非日期格式
    val slashMatch = Regex("""(?<!\d/)\b\d+/\d+\b(?!/\d)""").find(textBeforeRemarks)
    if (slashMatch != null) {
        return slashMatch.value
    }

    return ""
}

// 提取最低拍賣價格 (如：717,000元, 3,072,000元, 8,300,000元)
fun extractMinimalPrice(text: String): String {
    val priceMatch = Regex("""(?<!合計|保證金)(?<price>\d{1,3}(?:,\d{3})+\s*元|\d+\s*元)""").find(text)
    return priceMatch?.value?.replace(" ", "") ?: ""
}

// 提取總樓層與所在樓層資訊
fun extractFloorInfo(blockText: String): Pair<Int?, String> {
    if (blockText.contains("共同使用部分") || blockText.contains("公設")) {
        return Pair(null, "")
    }

    val compact = blockText.replace(Regex("""\s+"""), "")

    // 1. 總樓層 (Total Floors): 例如 "14層樓", "15層樓", "4層樓", "5層樓"
    var totalFloors: Int? = null
    val totalFloorMatch = Regex("""(?<total>\d+|[一二三四五六七八九十]+)\s*層\s*樓""").find(blockText)
    if (totalFloorMatch != null) {
        val rawTotal = totalFloorMatch.groups["total"]?.value ?: ""
        totalFloors = chineseNumberToInt(rawTotal)
    }

    // 2. 所在樓層 (Located Floor)
    var locatedFloor: String = ""

    // 策略 A: 「之第X層」或「之第X樓」 (例如 "之第12層", "之第10層")
    val zhiMatch = Regex("""之第(?<loc>\d+|[一二三四五六七八九十]+)[層樓]""").find(compact)
    if (zhiMatch != null) {
        val rawLoc = zhiMatch.groups["loc"]?.value ?: ""
        val num = chineseNumberToInt(rawLoc)
        locatedFloor = num?.toString() ?: rawLoc
    }

    // 策略 B: 建物面積表中的「X層:」或「X樓層:」
    if (locatedFloor.isBlank()) {
        val basementMatch = Regex("""(?<bLevel>地下(?:\d+|[一二三四五六七八九十]+))\s*[層樓](?:\s*頂)?\s*:""").find(blockText)
        if (basementMatch != null) {
            val raw = basementMatch.groups["bLevel"]?.value ?: ""
            val digitPart = raw.removePrefix("地下")
            val num = chineseNumberToInt(digitPart)
            locatedFloor = "地下" + (num ?: digitPart) + "樓"
        }
        
        if (locatedFloor.isBlank()) {
            val topMatch = Regex("""(?<top>(?:\d+|[一二三四五六七八九十]+))\s*層\s*頂(?:\s*未登記)?\s*:""").find(blockText)
            if (topMatch != null) {
                val raw = topMatch.groups["top"]?.value ?: ""
                val num = chineseNumberToInt(raw)
                locatedFloor = (num ?: raw).toString() + "樓頂未登記"
            }
        }

        if (locatedFloor.isBlank()) {
            val floorAreaMatches = Regex("""(?<fNum>(?:\d+|[一二三四五六七八九十]+))\s*(?:樓\s*層|層)\s*:""").findAll(blockText).toList()
            if (floorAreaMatches.size > 1) {
                val nums = floorAreaMatches.mapNotNull { chineseNumberToInt(it.groups["fNum"]?.value ?: "") }.sorted()
                if (nums.isNotEmpty()) {
                    locatedFloor = "${nums.first()}~${nums.last()}"
                    if (totalFloors == null) {
                        totalFloors = nums.maxOrNull()
                    }
                }
            } else if (floorAreaMatches.size == 1) {
                val raw = floorAreaMatches[0].groups["fNum"]?.value ?: ""
                val num = chineseNumberToInt(raw)
                locatedFloor = num?.toString() ?: raw
            }
        }
    }

    // 策略 C: 建物門牌中的「X號X樓」
    if (locatedFloor.isBlank()) {
        val doorBasementMatch = Regex("""號\s*(?<bLevel>地下(?:\d+|[一二三四五六七八九十]+))\s*[層樓]""").find(blockText)
        if (doorBasementMatch != null) {
            val raw = doorBasementMatch.groups["bLevel"]?.value?.replace(Regex("""\s+"""), "") ?: ""
            val digitPart = raw.removePrefix("地下")
            val num = chineseNumberToInt(digitPart)
            locatedFloor = "地下" + (num ?: digitPart) + "樓"
        }
        if (locatedFloor.isBlank()) {
            val doorTopMatch = Regex("""號\s*(?<top>(?:\d+|[一二三四五六七八九十]+)\s*[層樓]\s*頂(?:\s*未登記)?)""").find(blockText)
            if (doorTopMatch != null) {
                val raw = doorTopMatch.groups["top"]?.value?.replace(Regex("""\s+"""), "") ?: ""
                val num = chineseNumberToInt(raw)
                locatedFloor = (num ?: raw).toString() + "樓頂未登記"
            }
        }
        if (locatedFloor.isBlank()) {
            val doorMatch = Regex("""號\s*(?<loc>\d+|[一二三四五六七八九十]+)\s*[層樓]""").find(blockText)
            if (doorMatch != null) {
                val raw = doorMatch.groups["loc"]?.value ?: ""
                val num = chineseNumberToInt(raw)
                locatedFloor = num?.toString() ?: raw
            }
        }
    }

    return Pair(totalFloors, locatedFloor)
}

/**
 * 判斷建物型態:
 * 1. house (透天): 總樓層 <= 5 且 樓層面積包含全棟/多樓層 (例如 1~4 樓)，或標示透天/別墅/整棟，或為透天厝之增建
 * 2. elevator (大樓 / 電梯大樓): 總樓層 >= 6 (依法規設有電梯)，或明確標示大樓/華廈/電梯，或屬大樓之共同使用部分
 * 3. walk-up (公寓): 總樓層 <= 5 且 僅持有單一特定樓層 (如 2樓、5樓、5樓頂未登記、之第X層)
 */
fun determineBuildingType(totalFloors: Int?, locatedFloor: String, blockText: String, primaryType: String? = null): String {
    val isCommonArea = blockText.contains("共同使用部分") || blockText.contains("公設")
    if (isCommonArea) {
        return primaryType ?: "elevator"
    }

    // 計算面積欄位中出現的樓層數 (例如 一層: ..., 二層: ..., 三層: ...)
    val areaFloorMatches = Regex("""(?:\d+|[一二三四五六七八九十]+)\s*(?:樓\s*層|層)\s*:""").findAll(blockText).toList()
    val distinctAreaFloors = areaFloorMatches.size

    // 規則 1: 關鍵字明確標註
    if (blockText.contains("透天") || blockText.contains("別墅")) {
        return "house"
    }
    if (blockText.contains("電梯") || blockText.contains("華廈") || blockText.contains("大廈")) {
        return "elevator"
    }
    if (blockText.contains("公寓")) {
        return "walk-up"
    }

    // 規則 2: 總樓層 >= 6 -> 大樓 (elevator)
    if (totalFloors != null && totalFloors >= 6) {
        return "elevator"
    }

    // 規則 3: 總樓層 <= 5
    if (totalFloors != null && totalFloors <= 5) {
        // 若樓層面積列出多個樓層 (例如 1~4層)，或所在樓層為範圍 (1~4 樓) -> 透天 (house)
        if (distinctAreaFloors > 1 || locatedFloor.contains("~")) {
            return "house"
        }
        // 若僅為單一樓層 (例如 5樓, 2樓, 地下1樓, 5樓頂未登記) -> 公寓 (walk-up)
        return "walk-up"
    }

    // 規則 4: 若無總樓層資訊時（如增建物未登記）
    if (distinctAreaFloors > 1 || locatedFloor.contains("~")) {
        return "house"
    }
    if (primaryType != null) {
        return primaryType
    }
    if (locatedFloor.isNotBlank()) {
        return "walk-up"
    }

    return "elevator"
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

        if (trimmed.contains("建號                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                ") || trimmed.contains("建 號") || trimmed.contains("建物面積")) {
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

    var inBldg = false
    val bldgIndices = mutableListOf<Pair<Int, String>>()
    var endIdx = lines.size
    val bldgRowRegex = Regex("""^(?:(?<itemNo>\d+)\s+)?(?<bldgNo>\d{2,5})\s+(?<rest>.*)$""")

    for ((i, line) in lines.withIndex()) {
        val trimmed = line.trim()
        if (trimmed.contains("建號") || trimmed.contains("建 號")) inBldg = true
        if (inBldg && (trimmed.contains("使用情形") || trimmed.contains("點交情形") || trimmed.contains("點交否"))) {
            inBldg = false
            endIdx = i
            break
        }
        if (inBldg) {
            if (trimmed.contains("編號") || trimmed.contains("門牌") || trimmed.contains("備考") || 
                trimmed.contains("公尺") || trimmed.contains("權利範圍") || (trimmed.contains("第") && trimmed.contains("頁"))) {
                continue
            }
            val m = bldgRowRegex.find(trimmed)
            if (m != null) {
                val bldgNo = m.groups["bldgNo"]?.value ?: ""
                if (bldgNo.length in 2..5 && !bldgNo.startsWith("114") && !bldgNo.startsWith("115") && 
                    bldgNo !in listOf("122947", "131969", "133508", "90563")) {
                    bldgIndices.add(Pair(i, bldgNo))
                }
            }
        }
    }

    var firstMainType: String? = null
    for (k in bldgIndices.indices) {
        val start = bldgIndices[k].first
        val nextStart = if (k + 1 < bldgIndices.size) bldgIndices[k + 1].first else endIdx
        val bldgNo = bldgIndices[k].second
        val blockLines = (start until nextStart).map { lines[it] }
        val blockText = blockLines.joinToString("\n")
        
        val firstLine = lines[start].trim()
        val firstMatch = bldgRowRegex.find(firstLine)
        val rest = firstMatch?.groups["rest"]?.value ?: ""

        val scope = extractRightScope(blockText)
        val price = extractMinimalPrice(blockText)
        val (floors, located) = extractFloorInfo(blockText)
        val bType = determineBuildingType(floors, located, blockText, firstMainType)
        if (firstMainType == null && !blockText.contains("共同使用部分") && !blockText.contains("公設")) {
            firstMainType = bType
        }

        val item = BuildingInfo(
            id = (results.size + 1).toString(),
            buildingNumber = bldgNo,
            baseLocation = rest,
            rightScope = scope,
            buildingMinimalPrice = price,
            totalFloors = floors,
            locatedFloor = located,
            buildingType = bType
        )
        if (results.none { it.buildingNumber == item.buildingNumber }) {
            results.add(item)
        }
    }

    return results
}


