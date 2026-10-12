import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
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
    val buildingType: String = "", // "house", "elevator", "walk-up"
    val buildingTypeRule: String = "", // which rule of determineBuildingType decided buildingType
    val commonArea: Boolean = false // 共同使用部分 / 公設
)

// 定義標別結構：一個標別是投標書上填一張的單位，底下有該標的土地、建物與金額
data class Batch(
    val batchNumber: String?,      // 標別 (例如 "1", "甲", "A")；PDF 未標示標別時為 null (即只有一個標)
    val caseNumber: String,        // 案號 (例如 "115年司執字125891號")
    val owner: String,             // 財產所有人 (同一案號的不同標別可能不同人)
    val deposit: String,           // 保證金 (例如 "12,828,000元")
    val minimalPriceTotal: String, // 拍賣最低價額合計 (例如 "42,760,000元")
    val handover: String,          // 點交情形 (例如 "不點交")
    val usage: String,             // 使用情形
    val remarks: String,           // 備註
    val landResults: List<LandLocation>,
    val buildingResults: List<BuildingInfo>,
    val warnings: List<String>
) {
    /** 本標別所有土地與建物最低拍賣價格的加總 (元)；未填價格的列以 0 計。 */
    val landBuildingPriceSum: Long
        get() = landResults.sumOf { priceToYuan(it.landMinimalPrice) ?: 0L } +
            buildingResults.sumOf { priceToYuan(it.buildingMinimalPrice) ?: 0L }
}

// 定義拍賣公告結構：一份 PDF = 一個案號的拍賣公告，底下有一或多個標別
data class Notice(
    val caseNumber: String,
    val owner: String,             // 各標別財產所有人皆相同時為該人，否則為 ""
    val biddingDateTime: String,
    val openingPlace: String,      // 開標地點
    val batches: List<Batch>,
    val warnings: List<String>     // 整份公告層級的警示 (各標別的警示在 Batch.warnings)
)

/** Minimal dependency-free JSON string escaper for the fixed `--json` output schema below. */
fun jsonEscape(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

fun jsonString(s: String?): String = if (s == null) "null" else "\"${jsonEscape(s)}\""

/**
 * Emit the stable machine-readable contract for one PDF, layered like the notice itself:
 * the PDF-level fields (`caseNumber`, `owner`, `biddingDateTime`, `openingPlace`, `warnings`)
 * and `batches`, one per 標別 — the unit a bid form is filled in for. `batchNumber` is null
 * when the PDF has no 標別 label (a single-batch notice).
 *
 * Each batch carries its `deposit` (保證金) and `minimalPriceTotal` (拍賣最低價額合計) in whole
 * 元 (or null), `landBuildingPriceSum` (the sum of its rows' minimal prices, which should equal
 * `minimalPriceTotal`), `owner`, `handover` (點交情形), `usage` (使用情形), `remarks` (備註), its own `warnings`, and
 * two independent arrays (land parcels, building units) with no field linking one to the other —
 * the in-memory building-to-land cross-join (used only for the human-readable display) is
 * intentionally NOT reflected here. See openspec/changes/replace-land-building-parser-with-kotlin/design.md D9.
 * Each row carries its `minimalPrice` (最低拍賣價格) in whole 元, or null; each building its
 * floors and type (with the rule that decided it), and `property` summarises the batch's main building.
 *
 * `report` is the same human-readable text console mode prints for this file.
 */
fun printJson(notice: Notice, report: String) {
    val sb = StringBuilder()
    sb.append("{")
    sb.append("\"caseNumber\":${jsonString(notice.caseNumber.ifBlank { null })},")
    sb.append("\"owner\":${jsonString(notice.owner.ifBlank { null })},")
    sb.append("\"biddingDateTime\":${jsonString(notice.biddingDateTime.ifBlank { null })},")
    sb.append("\"openingPlace\":${jsonString(notice.openingPlace.ifBlank { null })},")
    sb.append("\"batches\":[")
    sb.append(notice.batches.joinToString(",") { batchJson(it) })
    sb.append("],")
    sb.append("\"warnings\":[${notice.warnings.joinToString(",") { jsonString(it) }}],")
    sb.append("\"report\":${jsonString(report)}")
    sb.append("}")
    println(sb.toString())
}

/** One 標別 as a JSON object (see [printJson]). */
fun batchJson(batch: Batch): String {
    val sb = StringBuilder()
    sb.append("{")
    sb.append("\"batchNumber\":${jsonString(batch.batchNumber)},")
    sb.append("\"owner\":${jsonString(batch.owner.ifBlank { null })},")
    sb.append("\"deposit\":${priceToYuan(batch.deposit) ?: "null"},")
    sb.append("\"minimalPriceTotal\":${priceToYuan(batch.minimalPriceTotal) ?: "null"},")
    sb.append("\"landBuildingPriceSum\":${batch.landBuildingPriceSum},")
    sb.append("\"handover\":${jsonString(batch.handover.ifBlank { null })},")
    sb.append("\"usage\":${jsonString(batch.usage.ifBlank { null })},")
    sb.append("\"remarks\":${jsonString(batch.remarks.ifBlank { null })},")
    sb.append("\"landParcels\":[")
    sb.append(batch.landResults.mapIndexed { index, item ->
        "{\"diNumber\":${jsonString(item.landNumber)},\"scope\":${jsonString(item.rightScope.ifBlank { null })}," +
            "\"minimalPrice\":${priceToYuan(item.landMinimalPrice) ?: "null"},\"sortOrder\":$index}"
    }.joinToString(","))
    sb.append("],")
    sb.append("\"buildingUnits\":[")
    sb.append(batch.buildingResults.mapIndexed { index, item ->
        "{\"buNumber\":${jsonString(item.buildingNumber)},\"scope\":${jsonString(item.rightScope.ifBlank { null })}," +
            "\"minimalPrice\":${priceToYuan(item.buildingMinimalPrice) ?: "null"},\"sortOrder\":$index," +
            "${floorAndTypeJson(item)},\"commonArea\":${item.commonArea}}"
    }.joinToString(","))
    sb.append("],")
    val main = mainBuilding(batch.buildingResults)
    sb.append("\"property\":${if (main == null) "null" else "{${floorAndTypeJson(main)}}"},")
    sb.append("\"warnings\":[${batch.warnings.joinToString(",") { jsonString(it) }}]")
    sb.append("}")
    return sb.toString()
}

/** A 最低拍賣價格 like "11,500,000元" as whole 元 (11500000), or null when there is none. */
fun priceToYuan(price: String): Long? = price.replace(Regex("""[,\s元]"""), "").toLongOrNull()

/** Whole 元 formatted like the PDF (42760000 -> "42,760,000元"). */
fun formatYuan(amount: Long): String = "%,d元".format(amount)

/** `totalFloors`, `locatedFloor`, `buildingType`, `buildingTypeRule` of one building as JSON members. */
fun floorAndTypeJson(item: BuildingInfo): String =
    "\"totalFloors\":${item.totalFloors ?: "null"}," +
        "\"locatedFloor\":${jsonString(item.locatedFloor.ifBlank { null })}," +
        "\"buildingType\":${jsonString(item.buildingType.ifBlank { null })}," +
        "\"buildingTypeRule\":${jsonString(item.buildingTypeRule.ifBlank { null })}"

/** The property's main building: the first one that isn't a common area (共同使用部分/公設). */
fun mainBuilding(buildingResults: List<BuildingInfo>): BuildingInfo? =
    buildingResults.firstOrNull { !it.commonArea }

/**
 * Things a reviewer should check for one 標別: missing values, a 拍賣最低價額合計 that doesn't
 * match the rows' prices (rows missing, or rows from another 標別), and building types that
 * were guessed rather than read.
 */
fun computeBatchWarnings(
    landResults: List<LandLocation>,
    buildingResults: List<BuildingInfo>,
    deposit: String,
    minimalPriceTotal: String,
    owner: String
): List<String> {
    val warnings = mutableListOf<String>()
    if (landResults.isEmpty() && buildingResults.isEmpty()) {
        warnings.add("未解析到任何土地或建物標的 (No land or building rows found)")
    }
    if (owner.isBlank()) {
        warnings.add("未解析到財產所有人 (No property owner found)")
    }
    if (deposit.isBlank()) {
        warnings.add("未解析到保證金 (No deposit amount found)")
    }
    val total = priceToYuan(minimalPriceTotal)
    if (total == null) {
        warnings.add("未解析到拍賣最低價額合計 (No minimal price total found)")
    } else {
        val sum = landResults.sumOf { priceToYuan(it.landMinimalPrice) ?: 0L } +
            buildingResults.sumOf { priceToYuan(it.buildingMinimalPrice) ?: 0L }
        if (sum != total) {
            warnings.add(
                "土地+建物最低拍賣價格加總 = ${formatYuan(sum)}，但拍賣最低價額合計 = ${formatYuan(total)} " +
                    "(Land + building prices sum to ${formatYuan(sum)}, but the minimal price total is ${formatYuan(total)})"
            )
        }
    }
    val main = mainBuilding(buildingResults)
    if (buildingResults.isNotEmpty() && main == null) {
        warnings.add("所有建物皆為共同使用部分，無主建物 (Every building row is a common area; no main building)")
    }
    if (main != null) {
        when (main.buildingTypeRule) {
            "default" -> warnings.add("主建物型態無任何依據，預設為大樓 (Main building type had no evidence; defaulted to elevator)")
            "inherited" -> warnings.add("主建物型態沿用前一建物 (Main building type was inherited from an earlier building)")
        }
        if (main.totalFloors == null && main.locatedFloor.isBlank()) {
            warnings.add("主建物未解析到樓層資訊 (No floor information found for the main building)")
        } else if (main.totalFloors == null) {
            warnings.add("主建物未解析到總樓層 (No total floor count found for the main building)")
        }
    }
    return warnings
}

/** Things a reviewer should check for the PDF as a whole (each 標別 has its own, see [computeBatchWarnings]). */
fun computeNoticeWarnings(
    sortedText: String,
    biddingDateTime: String,
    openingPlace: String,
    batches: List<Batch>
): List<String> {
    val warnings = mutableListOf<String>()
    if (!sortedText.contains("附表")) {
        warnings.add("找不到「附表」區塊，已解析全文 (No 附表 section found; parsed the whole document)")
    }
    if (biddingDateTime.isBlank()) {
        warnings.add("未解析到投標日時間 (No bidding date/time found)")
    }
    if (openingPlace.isBlank()) {
        warnings.add("未解析到開標地點 (No bid opening place found)")
    }
    val caseNumbers = batches.map { it.caseNumber }.filter { it.isNotBlank() }.distinct()
    if (caseNumbers.isEmpty()) {
        warnings.add("未解析到案號 (No case number found)")
    } else if (caseNumbers.size > 1) {
        warnings.add("各標別的案號不一致: ${caseNumbers.joinToString(", ")} (Batches have different case numbers)")
    }
    val dupBatches = batches.mapNotNull { it.batchNumber }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (dupBatches.isNotEmpty()) {
        warnings.add("同一標別出現多次: ${dupBatches.joinToString(", ")} (The same batch number appears more than once)")
    }
    return warnings
}

/** Link every building to every land row of the same 標別 for the console display (see printJson for why JSON omits it). */
fun linkBuildingsToLand(landResults: List<LandLocation>, buildingResults: List<BuildingInfo>) {
    if (landResults.isNotEmpty()) {
        for (building in buildingResults) {
            for (land in landResults) {
                if (!land.buildingNumbers.contains(building.buildingNumber)) {
                    land.buildingNumbers.add(building.buildingNumber)
                }
            }
        }
    }
}

/**
 * 解析一份拍賣公告 PDF：先依版面位置取文字，截取附表後依「標別」切段，
 * 每一段分別解析土地、建物、保證金、合計、點交情形與備註。
 */
fun parseNotice(document: PDDocument): Notice {
    val fullText = PDFTextStripper().getText(document)
    val sortedText = extractPositionSortedText(document)
    val biddingDateTime = extractBiddingDateTime(fullText)
    val openingPlace = extractOpeningPlace(sortedText)

    val batches = splitBatches(getAttachmentText(sortedText)).map { parseBatch(it) }
    val caseNumber = batches.map { it.caseNumber }.firstOrNull { it.isNotBlank() } ?: ""
    val owners = batches.map { it.owner }.filter { it.isNotBlank() }.distinct()
    return Notice(
        caseNumber = caseNumber,
        owner = owners.singleOrNull() ?: "",
        biddingDateTime = biddingDateTime,
        openingPlace = openingPlace,
        batches = batches,
        warnings = computeNoticeWarnings(sortedText, biddingDateTime, openingPlace, batches)
    )
}

/** 解析一個標別段落；土地與建物只在同一標別內對應。 */
fun parseBatch(section: BatchSection): Batch {
    val landResults = parseLandLocations(section.text)
    val buildingResults = parseBuildings(section.text)
    linkBuildingsToLand(landResults, buildingResults)
    val (caseNumber, owner) = extractCaseNumberAndOwner(section.text)
    val deposit = extractDeposit(section.text)
    val minimalPriceTotal = extractMinimalPriceTotal(section.text)
    return Batch(
        batchNumber = section.batchNumber,
        caseNumber = caseNumber,
        owner = owner,
        deposit = deposit,
        minimalPriceTotal = minimalPriceTotal,
        handover = extractHandover(section.text),
        usage = extractUsage(section.text),
        remarks = extractRemarks(section.text),
        landResults = landResults,
        buildingResults = buildingResults,
        warnings = computeBatchWarnings(landResults, buildingResults, deposit, minimalPriceTotal, owner)
    )
}

fun main(args: Array<String>) {
    // 徹底關閉 PDFBox / FontBox 冗長 Log 與 Warning
    System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog")
    Logger.getLogger("org.apache.pdfbox").level = Level.OFF
    Logger.getLogger("org.apache.fontbox").level = Level.OFF
    Logger.getLogger("org.apache.fontbox.ttf").level = Level.OFF
    Logger.getLogger("org.apache.fontbox.ttf.CmapSubtable").level = Level.OFF

    val jsonMode = args.contains("--json")
    val fileArgs = args.filter { it != "--json" }

    if (jsonMode) {
        // Single-PDF, machine-readable mode: exactly one JSON document to stdout, no console
        // banner/decoration. A missing/unreadable file or parse error is a non-zero exit so
        // the caller (api-core's subprocess client) treats it as a hard failure.
        val pdfPath = fileArgs.firstOrNull()
        if (pdfPath == null) {
            System.err.println("Error: --json requires a PDF file path argument")
            kotlin.system.exitProcess(1)
        }
        val pdfFile = File(pdfPath)
        if (!pdfFile.exists()) {
            System.err.println("Error: file not found: $pdfPath")
            kotlin.system.exitProcess(1)
        }
        try {
            PDDocument.load(pdfFile).use { document ->
                val notice = parseNotice(document)
                printJson(notice, renderReport(notice))
            }
        } catch (e: Exception) {
            System.err.println("Error parsing file $pdfPath: ${e.message}")
            kotlin.system.exitProcess(1)
        }
        return
    }

    println("==================================================")
    println("     法院拍賣公告 - 多檔案批次土地與建號解析器     ")
    println("  Court Auction Announcement - Land & Building Parser  ")
    println("==================================================")

    val pdfFiles = if (fileArgs.isNotEmpty()) {
        fileArgs.map { File(it) }.filter { it.exists() && it.name.lowercase().endsWith(".pdf") }
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

        try {
            PDDocument.load(pdfFile).use { document ->
                print(renderReport(parseNotice(document)))
            }
        } catch (e: Exception) {
            println("==================================================")
            println("解析檔案 File ${pdfFile.name} 時發生錯誤 (Error parsing file)：" + e.message)
            e.printStackTrace()
        }
        println("\n")
    }

    println("==================================================")
    println("✅ 所有 ${pdfFiles.size} 個 PDF 檔案批次解析完畢 (Batch Parsing Completed)！")
    println("==================================================")
}

/**
 * The human-readable bilingual result for one PDF, in two levels: the PDF-level fields
 * (case number, bidding date/time, opening place), then one block per 標別 with its
 * deposit, minimal price total, land and building rows, usage, remarks and warnings. Console mode
 * prints it right after the file name line; `--json` returns it as `report`.
 */
fun renderReport(notice: Notice): String {
    val out = StringBuilder()
    fun line(s: String) {
        out.append(s).append('\n')
    }
    val separator = "=================================================="

    // PDF 層 (一位債務人的一個拍賣案)
    if (notice.caseNumber.isNotBlank()) line("   📁 案號 Case Number: " + notice.caseNumber)
    if (notice.owner.isNotBlank()) line("   👤 財產所有人 Owner: " + notice.owner)
    if (notice.biddingDateTime.isNotBlank()) line("   ⏰ 投標日時間 Bidding Date & Time: " + notice.biddingDateTime)
    if (notice.openingPlace.isNotBlank()) line("   📍 開標地點 Opening Place: " + notice.openingPlace)
    line("   📦 共 ${notice.batches.size} 個標別 (${notice.batches.size} batch${if (notice.batches.size == 1) "" else "es"})")
    line(separator)

    // 標別層 (投標書一張填一個標別)
    val showBatchOwner = notice.owner.isBlank()
    for (batch in notice.batches) {
        val title = "標別 Batch Number: ${batch.batchNumber ?: "(未標示)"}"
        line("  ┌─ $title " + "─".repeat(maxOf(4, 44 - title.length)))
        fun row(s: String = "") {
            line(if (s.isEmpty()) "  │" else "  │ $s")
        }
        if (showBatchOwner && batch.owner.isNotBlank()) row("👤 財產所有人 Owner: " + batch.owner)
        row("💵 保證金 Deposit: " + batch.deposit.ifBlank { "(未解析到 Not found)" })
        val total = priceToYuan(batch.minimalPriceTotal)
        val sum = batch.landBuildingPriceSum
        val check = when {
            total == null -> ""
            total == sum -> " ✅ (土地+建物 Land+Building = ${formatYuan(sum)})"
            else -> " ❌ (土地+建物 Land+Building = ${formatYuan(sum)})"
        }
        row("💰 拍賣最低價額合計 Minimal Price Total: " + batch.minimalPriceTotal.ifBlank { "(未解析到 Not found)" } + check)
        if (batch.handover.isNotBlank()) row("📋 點交情形 Handover: " + batch.handover)

        // 該標別的土地
        row()
        if (batch.landResults.isEmpty()) {
            row("ℹ️ 本標別無拍賣土地標的（僅法拍建物）。No land target in this batch (Building only).")
        } else {
            row("🎯 土地 Land (共 ${batch.landResults.size} 筆土地標的)：")
            for ((index, item) in batch.landResults.withIndex()) {
                row("【土地標的 Land Target ${index + 1}】")
                if (item.county.isNotBlank()) row("  📍 縣市 City/County:            " + item.county)
                if (item.district.isNotBlank()) row("  📍 鄉鎮市區 District:           " + item.district)
                row("  📍 段別 Section:                " + item.section)
                row("  🔹 小段 Subsection:             " + (if (item.subSection.isBlank()) "(無小段 / 空白 None/Blank)" else item.subSection))
                row("  📌 地號 Land Number:            " + (if (item.landNumber.isBlank()) "None" else item.landNumber + " 地號 (Land No. " + item.landNumber + ")"))
                if (item.rightScope.isNotBlank()) row("  ⚖️ 權利範圍 Ownership Scope:    " + item.rightScope)
                if (item.landMinimalPrice.isNotBlank()) row("  💰 最低拍賣價格 Land Minimal Price: " + item.landMinimalPrice)
                val bldgText = if (item.buildingNumbers.isNotEmpty()) {
                    item.buildingNumbers.joinToString(", ") { it + " 建號 (Building No. " + it + ")" }
                } else {
                    "(無對應建號 No Corresponding Building Number)"
                }
                row("  🏢 對應建號 Building Number:   " + bldgText)
            }
        }

        // 該標別的建物
        if (batch.buildingResults.isNotEmpty()) {
            row()
            row("🏢 建物 Building (共 ${batch.buildingResults.size} 筆建物標的)：")
            for ((index, item) in batch.buildingResults.withIndex()) {
                row("【建物標的 Building Target ${index + 1}】")
                row("  🏢 建號 Building Number:       " + item.buildingNumber + " 建號 (Building No. " + item.buildingNumber + ")")
                if (item.buildingType.isNotBlank()) row("  🏠 建物型態 Building Type:     " + formatBuildingType(item.buildingType))
                if (item.baseLocation.isNotBlank()) row("  📍 基地坐落 Base Location:     " + item.baseLocation)
                if (item.totalFloors != null) row("  🏢 房屋層數 Total Floors:      " + item.totalFloors + " 層 (" + item.totalFloors + " Stories)")
                if (item.locatedFloor.isNotBlank()) row("  🚪 所在樓層 Located Floor:     " + formatLocatedFloor(item.locatedFloor))
                if (item.rightScope.isNotBlank()) row("  ⚖️ 權利範圍 Ownership Scope:    " + item.rightScope)
                if (item.buildingMinimalPrice.isNotBlank()) row("  💰 最低拍賣價格 Building Minimal Price: " + item.buildingMinimalPrice)
            }
        }

        if (batch.usage.isNotBlank()) {
            row()
            row("🏠 使用情形 Usage:")
            for (u in batch.usage.lines()) row("  " + u)
        }
        if (batch.remarks.isNotBlank()) {
            row()
            row("📝 備註 Remarks:")
            for (r in batch.remarks.lines()) row("  " + r)
        }
        if (batch.warnings.isNotEmpty()) {
            row()
            row("⚠️ 警示 Warnings:")
            for (w in batch.warnings) row("  - " + w)
        }
        line("  └" + "─".repeat(48))
    }

    if (notice.warnings.isNotEmpty()) {
        line("⚠️ 整份公告警示 Notice Warnings:")
        for (w in notice.warnings) line("  - " + w)
    }
    return out.toString()
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

/**
 * 依版面位置排序後，權利範圍欄常被最低拍賣價格欄截斷：分母留在該列價格之前
 * (「10000分之 5,950,000元」「100000 分 1,415,000元」)，分子落在下一行的行尾
 * (「166」「之249」「… 合計: 616.38 467」)。從該列價格前的片段接起後續各行的行尾片段。
 * 該列價格前已是完整的權利範圍 (「全部」「1分之1」) 時直接採用；拼不出「X分之Y」則回傳 ""。
 */
fun extractSplitRightScope(lines: List<String>, rowIndex: Int): String {
    val price = """(?:\d{1,3}(?:,\d{3})+|\d+)\s*元"""
    val row = lines[rowIndex].trim()
    val complete = Regex("""(?<scope>全部|\d+\s*分\s*之\s*\d+|\d+/\d+)\s+$price""").find(row)
    if (complete != null) return complete.groups["scope"]!!.value.replace(Regex("""\s+"""), "")
    val partial = Regex("""(?<![\d.,])(?<frag>\d+\s*分\s*之?|\d+)\s+$price""").find(row) ?: return ""
    val scope = StringBuilder(partial.groups["frag"]!!.value.replace(Regex("""\s+"""), ""))
    for (i in rowIndex + 1 until minOf(rowIndex + 4, lines.size)) {
        val lastToken = lines[i].trim().split(Regex("""\s+""")).last()
        if (!lastToken.matches(Regex("""[\d分之]+"""))) break
        scope.append(lastToken)
    }
    return scope.toString().takeIf { it.matches(Regex("""\d+分之\d+""")) } ?: ""
}

// 提取最低拍賣價格 (如：717,000元, 3,072,000元, 8,300,000元)
fun extractMinimalPrice(text: String): String {
    val priceMatch = Regex("""(?<!合計|保證金)(?<price>\d{1,3}(?:,\d{3})+\s*元|\d+\s*元)""").find(text)
    return priceMatch?.value?.replace(" ", "") ?: ""
}

/**
 * 依版面位置 (由上而下、由左而右) 排序後的全文，並清掉不屬於內文的雜訊：
 * 左邊界的行號 (01、02…，皆位於頁面最左側)、頁碼 (「第三頁」) 與「(續上頁)」。
 * 多標別公告用預設順序抽字時，「標別: X」標題會與內容錯置 (例如 標別4、標別5 連在一起)；
 * 依位置排序後每個標題才會緊接著該標的內容，才能依標別切段 (見 [splitBatches])。
 */
fun extractPositionSortedText(document: PDDocument): String {
    val stripper = object : PDFTextStripper() {
        override fun writeString(text: String, textPositions: List<TextPosition>) {
            val isMarginLineNumber = text.trim().matches(Regex("""\d{1,2}""")) &&
                textPositions.all { it.xDirAdj < MARGIN_LINE_NUMBER_MAX_X }
            if (!isMarginLineNumber) super.writeString(text, textPositions)
        }
    }
    stripper.sortByPosition = true
    return stripper.getText(document).lines()
        .map { it.trim() }
        .filterNot { it.matches(Regex("""第[一二三四五六七八九十\d]{1,4}頁|[(（]續上頁[)）]""")) }
        .joinToString("\n")
}

/** 左邊界行號的 x 座標上限 (行號約在 x=41，內文自 x≈67 起)。 */
const val MARGIN_LINE_NUMBER_MAX_X = 60f

/** 全形英數轉半形並轉大寫 (例如 "１２" -> "12", "ａ" -> "A")。 */
fun normalizeBatchNumber(raw: String): String =
    raw.map { c -> if (c in '０'..'９' || c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ') (c.code - 0xFEE0).toChar() else c }
        .joinToString("").uppercase()

/** 「標別: X」標題。X 可為數字 (1, 2, 10)、天干 (甲乙丙丁…)、或英文字母 (A, B, C…)。 */
val BATCH_HEADER_REGEX = Regex(
    """標[ \t]*別[ \t]*[:：][ \t]*(?<batch>[0-9０-９]+|[甲乙丙丁戊己庚辛壬癸]|[A-Za-zＡ-Ｚａ-ｚ])(?![0-9０-９A-Za-zＡ-Ｚａ-ｚ])"""
)

/**
 * 附表區塊：自「附表：」(附表表格的標題) 起；找不到時退回第一個「附表」，再找不到則為全文。
 * 公告正文的「均詳如附表。」不帶冒號，不會被當成附表起點。
 */
fun getAttachmentText(fullText: String): String {
    val start = Regex("""附\s*表\s*[:：]""").find(fullText)?.range?.first
        ?: fullText.indexOf("附表").takeIf { it != -1 }
        ?: 0
    return fullText.substring(start)
}

/** 附表中的一個標別段落：標別 (未標示為 null) 與該標別的附表文字。 */
data class BatchSection(val batchNumber: String?, val text: String)

/**
 * 依「標別: X」把附表切成一段一段，每段為一個可投標的標別。
 * 沒有任何標別標題時 (只有一個標)，整個附表為一段，batchNumber 為 null。
 * 需傳入 [extractPositionSortedText] 的附表，標題才會在正確位置。
 */
fun splitBatches(attachmentText: String): List<BatchSection> {
    val headers = BATCH_HEADER_REGEX.findAll(attachmentText).toList()
    if (headers.isEmpty()) return listOf(BatchSection(null, attachmentText))
    return headers.mapIndexed { i, m ->
        val end = if (i + 1 < headers.size) headers[i + 1].range.first else attachmentText.length
        BatchSection(normalizeBatchNumber(m.groups["batch"]!!.value), attachmentText.substring(m.range.last + 1, end))
    }
}

/** 保證金金額 (如：「三、保證金新台幣：154,000元。」→ "154,000元")；找不到為 ""。 */
fun extractDeposit(text: String): String =
    Regex("""保\s*證\s*金\s*(?:金\s*額)?\s*[:：]?\s*(?:新\s*[台臺]\s*幣)?\s*[:：]?\s*(?<amount>\d{1,3}(?:,\d{3})+|\d+)\s*元""")
        .find(text)?.let { it.groups["amount"]!!.value + "元" } ?: ""

/** 拍賣最低價額合計 (如：「二、拍賣最低價額合計新台幣：42,760,000元」→ "42,760,000元")；找不到為 ""。 */
fun extractMinimalPriceTotal(text: String): String =
    Regex("""最\s*低\s*(?:拍\s*賣\s*)?價\s*額?\s*合\s*計\s*(?:新\s*[台臺]\s*幣)?\s*[:：]?\s*(?<amount>\d{1,3}(?:,\d{3})+|\d+)\s*元""")
        .find(text)?.let { it.groups["amount"]!!.value + "元" } ?: ""

/**
 * 附表表頭的案號與財產所有人 (如：「115年司執字125891號 財產所有人：蕭宇筌」)。
 * 所有人過長時會折到下一行 (例如「…高小恬、高」「鈺貴、高玉玲」)，接到表格標題 (「編」) 之前為止。
 */
fun extractCaseNumberAndOwner(text: String): Pair<String, String> {
    val lines = text.lines()
    val headerRegex = Regex("""(?<case>\d{2,3}\s*年\s*度?\s*\S{0,8}?字\s*第?\s*\d+\s*號)\s*(?:財\s*產\s*所\s*有\s*人|債\s*務\s*人)\s*[:：]\s*(?<owner>.*)$""")
    for ((i, line) in lines.withIndex()) {
        val m = headerRegex.find(line) ?: continue
        val owner = StringBuilder(m.groups["owner"]!!.value.trim())
        var j = i + 1
        while (j < lines.size && owner.isNotEmpty() && isOwnerContinuation(lines[j])) {
            owner.append(lines[j].trim())
            j++
        }
        return Pair(m.groups["case"]!!.value.replace(Regex("""\s+"""), ""), owner.toString())
    }
    return Pair("", "")
}

/** 財產所有人折行的延續：只有人名與頓號，且不是表格標題 (「編 土地坐落…」「編號 建號…」)。 */
fun isOwnerContinuation(line: String): Boolean {
    val t = line.trim()
    return t.isNotEmpty() && !t.startsWith("編") && t.matches(Regex("""[一-龥A-Za-z、，()（）]+"""))
}

/** 點交情形 (如：「點交情形 點交否: 不點交」→ "不點交")；找不到為 ""。 */
fun extractHandover(text: String): String =
    Regex("""點\s*交\s*否\s*[:：]?\s*(?<value>[^\n]+)""").find(text)?.groups?.get("value")?.value?.trim() ?: ""

/** 公告結尾的承辦資訊 (函稿、股別、書記官…)，備註與使用情形都在此之前結束。 */
val NOTICE_FOOTER = Regex("""^(?:函\s*稿|股\s*別|擬\s*判|民\s*事\s*執\s*行\s*處|司\s*法\s*事\s*務\s*官|書\s*記\s*官|法\s*官)""")

/**
 * 使用情形欄 (如：「使用情形 一、2309建號建物係停車位…」起，到「備註」或公告結尾的承辦資訊之前)；找不到為 ""。
 * 每一行保留原本的折行。
 */
fun extractUsage(text: String): String {
    val lines = text.lines()
    val start = lines.indexOfFirst { it.trim().matches(Regex("""使\s*用\s*情\s*形.*""")) }
    if (start == -1) return ""
    val end = Regex("""^備\s*註""")
    return lines.drop(start)
        .map { it.trim() }
        .takeWhile { !end.containsMatchIn(it) && !NOTICE_FOOTER.containsMatchIn(it) }
        .mapIndexed { i, l -> if (i == 0) l.replace(Regex("""^使\s*用\s*情\s*形\s*"""), "") else l }
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

/**
 * 備註欄 (如：「備註 一、上開不動產3宗合併拍賣…」起，到公告結尾的承辦資訊之前)；找不到為 ""。
 * 每一行保留原本的折行。
 */
fun extractRemarks(text: String): String {
    val lines = text.lines()
    val start = lines.indexOfFirst { it.trim().matches(Regex("""備\s*註.*""")) }
    if (start == -1) return ""
    return lines.drop(start)
        .map { it.trim() }
        .takeWhile { !NOTICE_FOOTER.containsMatchIn(it) }
        .mapIndexed { i, l -> if (i == 0) l.replace(Regex("""^備\s*註\s*"""), "") else l }
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

/**
 * 公告標題的法院名稱 (如：「臺灣新北地方法院公告(第二次拍賣)（稿）」→ "臺灣新北地方法院")；找不到為 ""。
 */
fun extractCourtName(text: String): String {
    val compact = text.replace(Regex("""\s+"""), "")
    val court = Regex("""(?:臺灣|台灣|福建)[^，。、：:「」（）()]{1,4}?地方法院""")
    return (Regex(court.pattern + "(?=公告)").find(compact) ?: court.find(compact))?.value ?: ""
}

/**
 * 開標地點 (如：「開標日、時及場所：115年9月22日上午10時整在本院民事執行處投標室當眾開標」
 * → "臺灣新北地方法院民事執行處投標室"，「本院」換成公告標題的法院名稱)。
 * 公告寫「前開處所」或「地址同投標場所」時，補上投標場所的地點或地址。找不到為 ""。
 */
fun extractOpeningPlace(text: String): String {
    val place = extractRawOpeningPlace(text)
    val court = extractCourtName(text)
    return if (court.isBlank()) place else place.replace("本院", court)
}

fun extractRawOpeningPlace(text: String): String {
    val compact = text.replace(Regex("""\s+"""), "")
    val opening = Regex("""開標日[、，]?時及場所[:：].*?在(?<place>.+?)當眾開標""").find(compact)
        ?.groups?.get("place")?.value ?: return ""
    val biddingPlace = Regex("""投標日[、，]?時及場所[:：].*?投入(?<place>[^。]+)""").find(compact)
        ?.groups?.get("place")?.value?.replace(Regex("""(?:標匭|標櫃)?內$"""), "") ?: ""
    if (opening.contains("前開處所") && biddingPlace.isNotBlank()) return biddingPlace
    val address = Regex("""[（(]地址[:：][^）)]+[）)]""").find(biddingPlace)?.value
    if (address != null) return opening.replace(Regex("""[（(]地址同投標場所[）)]"""), address)
    return opening
}

// 提取總樓層與所在樓層資訊
fun extractFloorInfo(blockText: String): Pair<Int?, String> {
    if (blockText.contains("共同使用部分") || blockText.contains("公設")) {
        return Pair(null, "")
    }

    val compact = blockText.replace(Regex("""\s+"""), "")

    // 1. 總樓層 (Total Floors): 例如 "14層樓", "15層樓", "4層樓", "5層樓", "22層"
    //    「樓」為可選；排除「第X層」「地下X層」「之X層」、樓層面積表「X層:」及「X層頂」
    var totalFloors: Int? = null
    val totalFloorMatch = Regex(
        """(?<![第下之\d一二三四五六七八九十])(?<total>\d+|[一二三四五六七八九十]+)\s*層(?:\s*樓)?(?!\s*[:：頂])"""
    ).find(blockText)
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
fun determineBuildingType(totalFloors: Int?, locatedFloor: String, blockText: String, primaryType: String? = null): String =
    determineBuildingTypeWithRule(totalFloors, locatedFloor, blockText, primaryType).first

/** Same decision as [determineBuildingType], plus the name of the rule that made it. */
fun determineBuildingTypeWithRule(totalFloors: Int?, locatedFloor: String, blockText: String, primaryType: String? = null): Pair<String, String> {
    val isCommonArea = blockText.contains("共同使用部分") || blockText.contains("公設")
    if (isCommonArea) {
        return Pair(primaryType ?: "elevator", if (primaryType != null) "common-area:inherited" else "common-area:default")
    }

    // 計算面積欄位中出現的樓層數 (例如 一層: ..., 二層: ..., 三層: ...)
    val areaFloorMatches = Regex("""(?:\d+|[一二三四五六七八九十]+)\s*(?:樓\s*層|層)\s*:""").findAll(blockText).toList()
    val distinctAreaFloors = areaFloorMatches.size

    // 規則 1: 關鍵字明確標註
    listOf("透天", "別墅").firstOrNull { blockText.contains(it) }?.let { return Pair("house", "keyword:$it") }
    listOf("電梯", "華廈", "大廈").firstOrNull { blockText.contains(it) }?.let { return Pair("elevator", "keyword:$it") }
    if (blockText.contains("公寓")) {
        return Pair("walk-up", "keyword:公寓")
    }

    // 規則 2: 總樓層 >= 6 -> 大樓 (elevator)
    if (totalFloors != null && totalFloors >= 6) {
        return Pair("elevator", "floors>=6")
    }

    // 規則 3: 總樓層 <= 5
    if (totalFloors != null && totalFloors <= 5) {
        // 若樓層面積列出多個樓層 (例如 1~4層)，或所在樓層為範圍 (1~4 樓) -> 透天 (house)
        if (distinctAreaFloors > 1 || locatedFloor.contains("~")) {
            return Pair("house", "floors<=5:multi-floor")
        }
        // 若僅為單一樓層 (例如 5樓, 2樓, 地下1樓, 5樓頂未登記) -> 公寓 (walk-up)
        return Pair("walk-up", "floors<=5:single-floor")
    }

    // 規則 4: 若無總樓層資訊時（如增建物未登記）
    if (distinctAreaFloors > 1 || locatedFloor.contains("~")) {
        return Pair("house", "no-floors:multi-floor")
    }
    if (primaryType != null) {
        return Pair(primaryType, "inherited")
    }
    if (locatedFloor.isNotBlank()) {
        return Pair("walk-up", "no-floors:located-floor")
    }

    return Pair("elevator", "default")
}

fun parseLandLocations(attachmentText: String): List<LandLocation> {
    val results = mutableListOf<LandLocation>()
    val lines = attachmentText.lines()

    var isInsideLandTable = false

    val regexes = listOf(
        Regex("""(?:\d+\s+)?(?:(?<county>[\u4e00-\u9fa5]{2,3}[縣市]))?\s*(?:(?<district>[\u4e00-\u9fa5]{2,4}[區市鎮鄉]))?\s*(?<section>[\u4e00-\u9fa5]{2,8})(?:段)?\s+(?:(?<subSection>[\u4e00-\u9fa50-9]+小段)?\s+)?(?<landNo>\d+(?:-\d+)?)\s+"""),
        Regex("""(?:(?<county>[\u4e00-\u9fa5]{2,3}[縣市]))?\s*(?:(?<district>[\u4e00-\u9fa5]{2,4}[區市鎮鄉]))?\s*(?<section>[\u4e00-\u9fa5]{2,8})(?:段)?\s*(?:(?<subSection>[\u4e00-\u9fa50-9]+小段))?\s*(?<landNo>\d+(?:-\d+)?)\s*(?:地號)""")
    )
    // 附表土地表格的一列：編號 縣市 鄉鎮市區 段 小段 地號 面積 …，段與小段常不帶「段」「小段」字樣
    // (例如 "1 臺北市 中山區 正義 四 290 1073.3 1000000分 3,520,000元")，故優先以整列欄位比對。
    val tableRowRegex = Regex(
        """^(?:\d+\s+)?(?<county>[\u4e00-\u9fa5]{2}[縣市])\s+(?<district>[\u4e00-\u9fa5]{1,3}[區市鎮鄉])\s+(?<section>[\u4e00-\u9fa5]{2,8})\s+(?:(?<subSection>[\u4e00-\u9fa50-9]{1,6})\s+)?(?<landNo>\d+(?:-\d+)?)\s+\d+(?:\.\d+)?(?:\s|$)"""
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

        // 點交情形 / 使用情形 / 備註 已不屬於土地表格，其中的「○○地號」是說明文字，不是拍賣標的
        if (trimmed.contains("點交情形") || trimmed.contains("點交否") || trimmed.contains("使用情形") ||
            trimmed.matches(Regex("""備\s*註.*"""))) {
            isInsideLandTable = false
        }

        // 備考欄 (例如「備考 重測前:詔安段1小段17-6地號」) 是該列的附註，不是另一筆土地
        if (isInsideLandTable && !trimmed.startsWith("備考")) {
            val tableRow = tableRowRegex.find(trimmed)
            val matchGroups = if (tableRow != null) listOf(sequenceOf(tableRow)) else regexes.map { it.findAll(trimmed) }
            for (matches in matchGroups) {
                for (match in matches) {
                    val county = match.safeGroupValue("county")
                    val district = match.safeGroupValue("district")
                    var section = match.safeGroupValue("section").removeSuffix("段").trim()
                    val subSection = match.safeGroupValue("subSection").let {
                        if (it.isBlank() || it.endsWith("小段")) it else it + "小段"
                    }
                    val landNo = match.safeGroupValue("landNo")

                    if (landNo.isNotBlank() && section.length >= 2 && 
                        !section.contains("縣") && !section.contains("市") && 
                        !section.contains("區") && !section.contains("附表") && 
                        !section.contains("標的") && !section.contains("編號") &&
                        !section.contains("備考") && !section.contains("坐落")) {
                        
                        val fullSection = section + "段"

                        val windowText = (index..minOf(index + 15, lines.size - 1)).joinToString(" ") { lines[it] }
                        val scope = extractSplitRightScope(lines, index).ifBlank { extractRightScope(windowText) }
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

/**
 * 基地坐落欄：依版面位置排序後，該欄在建物列的第一個字串，並折行到後續各行的第一個字串，
 * 直到「--------------」分隔線 (例如「高雄市三民區金」+「獅段1地號」→「高雄市三民區金獅段1地號」)。
 */
fun extractBaseLocation(rowRest: String, followingLines: List<String>): String {
    val first = rowRest.trim().split(Regex("""\s+""")).first()
    val divider = followingLines.indexOfFirst { it.trim().startsWith("---") }
    if (divider == -1 || divider > 4) return first
    // 續行開頭若是純數字，是旁邊欄位的字 (例如「9 子翠段第三崁小」的 9)，不屬於基地坐落
    return first + followingLines.take(divider).joinToString("") { line ->
        line.trim().split(Regex("""\s+""")).firstOrNull { !it.matches(Regex("""\d+""")) } ?: ""
    }
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
                if (bldgNo.length in 2..5) {
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
        val rest = extractBaseLocation(firstMatch?.groups["rest"]?.value ?: "", blockLines.drop(1))

        val scope = extractSplitRightScope(lines, start).ifBlank { extractRightScope(blockText) }
        val price = extractMinimalPrice(blockText)
        val (floors, located) = extractFloorInfo(blockText)
        val (bType, bTypeRule) = determineBuildingTypeWithRule(floors, located, blockText, firstMainType)
        val isCommonArea = blockText.contains("共同使用部分") || blockText.contains("公設")
        if (firstMainType == null && !isCommonArea) {
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
            buildingType = bType,
            buildingTypeRule = bTypeRule,
            commonArea = isCommonArea
        )
        if (results.none { it.buildingNumber == item.buildingNumber }) {
            results.add(item)
        }
    }

    return results
}


