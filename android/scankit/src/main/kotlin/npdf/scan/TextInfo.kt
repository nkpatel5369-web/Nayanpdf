package npdf.scan

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A recognised line of text with its height (for telling headings from body text). */
data class TextLine(val text: String, val height: Float = 0f, val top: Float = 0f)

/** Contact details read from a business card. */
data class Contact(
    val name: String? = null,
    val company: String? = null,
    val title: String? = null,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val website: String? = null,
    val address: String? = null,
) {
    val isEmpty: Boolean get() = name == null && company == null && phones.isEmpty() && emails.isEmpty() && website == null
}

/** Reads contact details and file-name suggestions from recognised text. Heuristic: the user reviews the result. */
object TextInfo {

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val URL = Regex("(?i)\\b((?:https?://)?(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|in|org|net|co|io|biz|info|co\\.in|org\\.in|gov\\.in|edu|ai)(?:/[^\\s]*)?)\\b")
    private val PHONE = Regex("(?<![\\d])(?:\\+?\\d{1,3}[\\s.-]?)?(?:\\(?\\d{2,5}\\)?[\\s.-]?)?\\d{3,5}[\\s.-]?\\d{3,5}(?![\\d])")
    private val PIN = Regex("\\b\\d{6}\\b")
    private val COMPANY_WORDS = Regex("(?i)\\b(pvt|private|ltd|limited|llp|inc|corp|corporation|company|co\\.|& co|associates|consultants|solutions|services|industries|enterprises|traders|technologies|bank|chartered accountants|advocates|group|foundation|trust)\\b")
    private val TITLE_WORDS = Regex("(?i)\\b(director|manager|partner|proprietor|founder|ceo|cfo|coo|cto|president|head|officer|consultant|engineer|executive|analyst|advocate|chartered accountant|ca\\b|cs\\b|cma\\b|associate|lead|specialist|designer|developer|sales|marketing|accountant|auditor|secretary)\\b")
    private val ADDRESS_WORDS = Regex("(?i)\\b(road|rd\\.?|street|st\\.|lane|marg|nagar|sector|floor|building|bldg|tower|plot|near|opp\\.?|society|park|colony|chowk|cross|block|phase|city|district|india|maharashtra|gujarat|delhi|mumbai|ahmedabad|pune|bengaluru|bangalore|chennai|kolkata|hyderabad|surat|vadodara)\\b")

    fun parseCard(lines: List<TextLine>): Contact {
        val clean = lines.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
        val all = clean.joinToString("\n") { it.text }
        val emails = EMAIL.findAll(all).map { it.value }.distinct().toList()
        val website = URL.findAll(all).map { it.value }.firstOrNull { u -> emails.none { e -> e.contains(u, ignoreCase = true) } }
        val phones = clean.flatMap { l ->
            if (EMAIL.containsMatchIn(l.text) && !l.text.contains(Regex("\\d{6,}"))) emptyList()
            else PHONE.findAll(l.text).map { it.value.trim() }.filter { p -> p.count { it.isDigit() } in 8..13 && !PIN.matches(p) }.toList()
        }.distinct()
        val used = HashSet<String>()
        fun isContactLine(t: String) = EMAIL.containsMatchIn(t) || URL.containsMatchIn(t) || t.count { it.isDigit() } >= 6
        val company = clean.firstOrNull { COMPANY_WORDS.containsMatchIn(it.text) && !isContactLine(it.text) }?.text
        company?.let { used.add(it) }
        val title = clean.firstOrNull { it.text !in used && TITLE_WORDS.containsMatchIn(it.text) && !isContactLine(it.text) && it.text.length <= 60 }?.text
        title?.let { used.add(it) }
        // Name: 2 to 4 words, letters only, not company/title/contact; prefer the tallest such line.
        val nameCandidates = clean.filter { l ->
            val t = l.text
            t !in used && !isContactLine(t) && !COMPANY_WORDS.containsMatchIn(t) && !ADDRESS_WORDS.containsMatchIn(t) &&
                t.split(Regex("\\s+")).size in 2..4 && t.all { it.isLetter() || it == ' ' || it == '.' || it == '\'' || it == '-' } &&
                t.split(Regex("\\s+")).all { w -> w.firstOrNull()?.isUpperCase() == true }
        }
        val name = nameCandidates.maxByOrNull { it.height }?.text
        name?.let { used.add(it) }
        val addressLines = clean.filter { l -> l.text !in used && (ADDRESS_WORDS.containsMatchIn(l.text) || PIN.containsMatchIn(l.text)) && !EMAIL.containsMatchIn(l.text) }
            .map { it.text }
        val address = addressLines.joinToString(", ").takeIf { it.isNotBlank() }
        return Contact(name, company, title, phones, emails, website, address)
    }

    private val KEYWORDS = listOf(
        "Tax Invoice", "Invoice", "Receipt", "Bank Statement", "Statement of Account", "Statement",
        "Balance Sheet", "Profit and Loss", "Form 16", "Form 26AS", "Purchase Order", "Quotation", "Delivery Challan",
        "Credit Note", "Debit Note", "Agreement", "Contract", "Certificate", "Notice", "Letter", "Resume", "Prescription",
        "Report", "Minutes", "Application", "Affidavit", "Power of Attorney", "Lease", "Bill",
    )
    private val DATE_PATTERNS = listOf(
        Regex("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})\\b") to "dmy",
        Regex("\\b(\\d{4})[/.-](\\d{1,2})[/.-](\\d{1,2})\\b") to "ymd",
        Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*,?\\s+(\\d{4})\\b") to "dMy",
        Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+(\\d{1,2}),?\\s+(\\d{4})\\b") to "Mdy",
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** The first valid date in [text], or null. Day-first for numeric dates (Indian convention). */
    fun firstDate(text: String): LocalDate? {
        val found = ArrayList<Pair<Int, LocalDate>>()
        for ((re, kind) in DATE_PATTERNS) for (m in re.findAll(text)) {
            val g = m.groupValues
            val d = runCatching {
                when (kind) {
                    "dmy" -> LocalDate.of(g[3].toInt(), g[2].toInt(), g[1].toInt())
                    "ymd" -> LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
                    "dMy" -> LocalDate.of(g[3].toInt(), MONTHS.indexOf(g[2].lowercase().take(3)) + 1, g[1].toInt())
                    else -> LocalDate.of(g[3].toInt(), MONTHS.indexOf(g[1].lowercase().take(3)) + 1, g[2].toInt())
                }
            }.getOrNull()
            if (d != null && d.year in 1950..2100) found.add(m.range.first to d)
        }
        return found.minByOrNull { it.first }?.second
    }

    /**
     * File name suggestions from recognised text: a document type with its date,
     * the heading, and the plain default. Names contain no characters that file
     * systems reject.
     */
    fun nameSuggestions(lines: List<TextLine>, fallback: String): List<String> {
        val text = lines.joinToString("\n") { it.text }
        val out = LinkedHashSet<String>()
        val date = firstDate(text)?.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val kw = KEYWORDS.firstOrNull { Regex("(?i)\\b" + Regex.escape(it) + "\\b").containsMatchIn(text) }
        if (kw != null) out.add(listOfNotNull(kw, date).joinToString(" "))
        val heading = lines.filter { l -> l.text.trim().length in 4..48 && l.text.count { it.isLetter() } >= 4 }
            .let { ls -> val top = ls.take(8); top.maxByOrNull { it.height } }?.text?.trim()
        // The page's heading, unless a suggestion already starts with it ("TAX INVOICE" next to "Tax Invoice 2026-03-31").
        if (heading != null && out.none { it.lowercase().startsWith(safeName(heading).lowercase()) }) out.add(safeName(heading))
        out.add(fallback)
        return out.map(::safeName).filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(4)
    }

    /** Removes characters not allowed in file names and trims the length. */
    fun safeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80)

    /** Default name such as "Scan 2026-11-15 10.32". */
    fun defaultName(kind: String, now: java.time.LocalDateTime): String =
        "$kind ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm", Locale.ROOT))}"
}
