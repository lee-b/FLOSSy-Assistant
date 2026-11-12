package ai.flossyapp.data.tools

import kotlin.random.Random

/**
 * Parses and rolls a dice expression such as "3d8+4", "d20", "2d6-1+2".
 *
 * The expression is a sum of signed terms: a plain number, or NdM / dM (N
 * dice of M sides; a missing N means one). Whitespace is ignored, and a term
 * without a sign is positive. Anything else - an empty term, a zero or
 * negative side count, a term longer than a hundred dice or a million sides -
 * is refused with a readable message rather than rolled, because a model that
 * mis-typed "1000000000d6" must not spin the CPU.
 */
object DiceParser {

    /** One term of the expression: how many dice of how many sides, or a constant. */
    private sealed class Term {
        data class Constant(val value: Int) : Term()
        data class Dice(val count: Int, val sides: Int) : Term()
    }

    /**
     * Rolls [expression] and answers a line the model can read aloud: the
     * expression, each die it rolled, and the total. A refused expression
     * returns a short explanation instead of a result.
     */
    fun roll(expression: String): String {
        val terms = parse(expression.trim()) ?: return "That is not a dice expression I can read, such as 3d8+4."
        var total = 0
        val parts = mutableListOf<String>()
        for (term in terms) {
            when (term) {
                is Term.Constant -> {
                    total += term.value
                    parts.add("${term.value}")
                }
                is Term.Dice -> {
                    val rolled = mutableListOf<Int>()
                    var sum = 0
                    repeat(term.count) {
                        val face = Random.nextInt(1, term.sides + 1)
                        rolled.add(face)
                        sum += face
                    }
                    total += sum
                    parts.add("d${term.sides}: [${rolled.joinToString(", ")}] = $sum")
                }
            }
        }
        return "${expression.trim()} -> ${parts.joinToString(", ")}, total $total"
    }

    /**
     * Splits the expression into signed terms and parses each one. Returns
     * null when the expression is unreadable.
     */
    private fun parse(expression: String): List<Term>? {
        if (expression.isBlank()) return null
        // Walk the expression keeping the signs: "3d8+4-1" yields the terms
        // 3d8, +4 and -1, where the first term is unsigned (positive).
        val terms = mutableListOf<Term>()
        var signed = 1
        var index = 0
        // A sign with no term after it ("+4-") is not an expression.
        var lastWasSign = false
        while (index < expression.length) {
            val c = expression[index]
            when (c) {
                ' ' -> { index++; continue }
                '+' -> { signed = 1; index++; lastWasSign = true; continue }
                '-' -> { signed = -1; index++; lastWasSign = true; continue }
            }
            var end = index
            while (end < expression.length && expression[end] != '+' && expression[end] != '-' && expression[end] != ' ') end++
            val body = expression.substring(index, end)
            index = end
            val term = parseTerm(body, signed) ?: return null
            signed = 1
            lastWasSign = false
            terms.add(term)
        }
        if (lastWasSign) return null
        return if (terms.isEmpty()) null else terms
    }

    /**
     * One term: "4" is a constant, "d8" or "3d8" is a dice group.
     */
    private fun parseTerm(body: String, sign: Int): Term? {
        if (body.isBlank()) return null
        val lower = body.lowercase()
        val dPosition = lower.indexOf('d')
        if (dPosition < 0) {
            val value = lower.toIntOrNull() ?: return null
            return Term.Constant(sign * value)
        }
        val countText = lower.substring(0, dPosition)
        val sidesText = lower.substring(dPosition + 1)
        val count = if (countText.isBlank()) 1 else countText.toIntOrNull() ?: return null
        val sides = sidesText.toIntOrNull() ?: return null
        if (count < 1 || count > 100 || sides < 2 || sides > 1_000_000) return null
        return Term.Dice(sign * count, sides)
    }
}
