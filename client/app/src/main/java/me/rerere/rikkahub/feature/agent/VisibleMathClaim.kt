package me.rerere.rikkahub.feature.agent

import me.rerere.rikkahub.feature.math.MultiExpression

/** Affirmative visible mathematics. A substring is not a conclusion. */
internal object VisibleMathClaim {
    fun clauses(reply: String): List<String> =
        compact(reply).split(Regex("[。；;！？!?]")).map { it.trim() }.filter { it.isNotEmpty() }

    fun negated(clause: String): Boolean {
        val text = clause.replace(" ", "")
        return listOf("不是", "并非", "不能", "不成立", "错误示例", "错误例子", "反例", "错的").any { it in text }
    }

    fun compact(reply: String): String {
        val stripped = reply.replace(" ", "").replace("\n", "").replace("\r", "")
            .replace("$", "").replace("\\left", "").replace("\\right", "")
            .replace("\\boxed{", "").replace("\\qquad", "")
            .replace("\\dfrac", "\\frac").replace("a_{n}", "a_n")
            .replace("²", "^2").replace("³", "^3")
        return expandFractions(expandSuperscripts(stripped))
    }

    fun algebra(raw: String): String {
        var current = raw.trim().replace("{", "").replace("}", "")
        repeat(8) {
            val next = current.replace(Regex("""(\d)([A-Za-z(])"""), "$1*$2")
                .replace(Regex("""([A-Za-z)])([A-Za-z(])"""), "$1*$2")
            if (next == current) return@repeat
            current = next
        }
        return current
    }

    fun same(left: String, right: String, variables: List<String>): Boolean {
        val a = algebra(left)
        val b = algebra(right)
        if (a == b) return true
        return runCatching {
            MultiExpression(a, variables).parse().add(MultiExpression(b, variables).parse(), subtract = true).zero
        }.getOrDefault(false)
    }

    fun containsExpression(clause: String, expression: String): Boolean {
        val text = compact(clause).replace("*", "")
        val needle = algebra(expression).replace("*", "")
        return needle.isNotEmpty() && needle in text
    }

    fun affirmativeFormulas(reply: String): List<String> {
        val parts = clauses(reply)
        if (parts.any(::retractsConclusion)) return emptyList()
        val found = ArrayList<String>()
        for (clause in parts) {
            if (negated(clause) || problemRestatement(clause)) continue
            val text = clause.replace("a_{n}", "a_n")
            var index = 0
            while (true) {
                val at = text.indexOf("a_n=", index)
                if (at < 0) break
                if (!pointBound(text, at)) {
                    val raw = text.substring(at + 4).takeWhile { it.isDigit() || it == 'n' || it in "+-*/^()." }
                    val normalized = algebra(raw)
                    if (normalized.isNotEmpty() && normalized.all { it.isLetterOrDigit() || it in "+-*/^()." }) found += normalized
                }
                index = at + 4
            }
        }
        return found
    }

    fun problemRestatement(clause: String): Boolean {
        val text = compact(clause)
        return listOf("题目要求", "题干", "复述").any { it in text }
    }

    fun retractsConclusion(clause: String): Boolean {
        val text = compact(clause)
        return listOf("不是我的结论", "尚未完成", "没有证明", "只是题干", "不要写成", "并非结论").any { it in text }
    }

    fun domainAllows(reply: String, start: String): Boolean {
        val text = compact(reply)
        if (listOf("一切整数", "所有整数", "全体整数").any { it in text && "非负" !in text }) return false
        for (clause in clauses(reply)) {
            val local = compact(clause)
            if ("n<$start" !in local) continue
            if (listOf("不属于", "不在", "除外", "之外").none { it in local }) return false
        }
        return true
    }

    private fun pointBound(clause: String, at: Int): Boolean {
        val prefix = clause.substring(0, at)
        return Regex("""当n=\d+""").containsMatchIn(prefix) || Regex("""n=\d+时""").containsMatchIn(prefix.takeLast(16))
    }

    private fun expandSuperscripts(source: String): String {
        val text = StringBuilder(source)
        var index = 0
        while (index < text.length - 1) {
            if (text[index] == '^' && text[index + 1] == '{') {
                val end = text.indexOf("}", index + 2)
                if (end > index) text.replace(index, end + 1, "^${text.substring(index + 2, end)}")
            }
            index++
        }
        return text.toString()
    }

    private fun expandFractions(source: String): String {
        val text = StringBuilder(source)
        repeat(32) {
            val at = text.indexOf("\\frac")
            if (at < 0) return text.toString()
            var cursor = at + 5
            val numerator = readBrace(text, cursor) ?: return source
            cursor = numerator.second
            val denominator = readBrace(text, cursor) ?: return source
            text.replace(at, denominator.second, "(${numerator.first})/(${denominator.first})")
        }
        return text.toString()
    }

    private fun readBrace(text: StringBuilder, open: Int): Pair<String, Int>? {
        if (open >= text.length || text[open] != '{') return null
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i) to i + 1
                }
            }
        }
        return null
    }
}
