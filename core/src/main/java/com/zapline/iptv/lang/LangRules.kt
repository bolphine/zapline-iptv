package com.zapline.iptv.lang

import com.zapline.iptv.model.Channel

/**
 * User rules, one per line:  `pattern => language`
 *   - pattern is a case-insensitive regex matched against the group title and channel name
 *   - prefix with `group:` or `name:` to match only one of them
 *   - target `hide` removes matching channels entirely
 *   - lines starting with # are comments
 */
class LangRules private constructor(val rules: List<Rule>, val errors: List<String>) {

    enum class Scope { BOTH, GROUP, NAME }
    data class Rule(val regex: Regex, val scope: Scope, val target: String)

    /** Returns a language key, [HIDE], or null when no rule matches. */
    fun match(c: Channel): String? {
        for (r in rules) {
            val hit = when (r.scope) {
                Scope.BOTH -> r.regex.containsMatchIn(c.group) || r.regex.containsMatchIn(c.name)
                Scope.GROUP -> r.regex.containsMatchIn(c.group)
                Scope.NAME -> r.regex.containsMatchIn(c.name)
            }
            if (hit) return r.target
        }
        return null
    }

    companion object {
        const val HIDE = "hide"
        val EMPTY = LangRules(emptyList(), emptyList())

        fun parse(text: String): LangRules {
            val rules = ArrayList<Rule>()
            val errors = ArrayList<String>()
            text.lines().forEachIndexed { idx, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val arrow = line.lastIndexOf("=>")
                if (arrow < 0) { errors += "Line ${idx + 1}: missing =>"; return@forEachIndexed }
                var pattern = line.substring(0, arrow).trim()
                val targetWord = line.substring(arrow + 2).trim()
                var scope = Scope.BOTH
                when {
                    pattern.startsWith("group:", true) -> { scope = Scope.GROUP; pattern = pattern.substring(6).trim() }
                    pattern.startsWith("name:", true) -> { scope = Scope.NAME; pattern = pattern.substring(5).trim() }
                }
                if (pattern.isEmpty()) { errors += "Line ${idx + 1}: empty pattern"; return@forEachIndexed }
                val target = if (targetWord.equals(HIDE, true)) HIDE else Languages.resolve(targetWord)
                if (target == null) { errors += "Line ${idx + 1}: unknown language \"$targetWord\""; return@forEachIndexed }
                val regex = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (e: Exception) {
                    errors += "Line ${idx + 1}: bad pattern (${e.message?.lineSequence()?.firstOrNull()})"
                    return@forEachIndexed
                }
                rules += Rule(regex, scope, target)
            }
            return LangRules(rules, errors)
        }
    }
}
