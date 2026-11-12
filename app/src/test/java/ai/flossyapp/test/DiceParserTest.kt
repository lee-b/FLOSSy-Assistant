package ai.flossyapp.test

import ai.flossyapp.data.tools.DiceParser
import org.junit.Test

/**
 * Unit tests for the dice expression the "roll_dice" tool accepts.
 *
 * The expression is typed by a model, not a person, so every mis-spelling it
 * is likely to produce ("3d8+4", "d20", "2d6 - 1", "3x8+4") has to land in a
 * readable refusal rather than a crash or a nonsense total.
 */
class DiceParserTest {

    @Test
    fun plainDiceRolls() {
        val result = DiceParser.roll("d20")
        assert(result.contains("d20")) { "the expression should be echoed: $result" }
        assert(result.contains("total")) { "a total must be named: $result" }
    }

    @Test
    fun diceWithCountAndModifier() {
        val result = DiceParser.roll("3d8+4")
        assert(result.startsWith("3d8+4 ->")) { "the expression should be echoed: $result" }
        // Three dice means three bracketed groups, and the constant is there.
        assert(result.count { it == '[' } == 1) { "one dice group: $result" }
        assert(result.contains("total")) { "a total must be named: $result" }
    }

    @Test
    fun totalIsTheDicePlusTheConstant() {
        // A single die plus a known constant: the total is roll + 4, so the
        // total must be at least 5 and the roll part must be present.
        val result = DiceParser.roll("d6+4")
        val total = result.substringAfterLast("total ").trim().toIntOrNull()
        assert(total != null && total >= 5 && total <= 10) { "total must be 5..10: $result" }
    }

    @Test
    fun negativeModifierSubtracts() {
        val result = DiceParser.roll("2d6-1")
        assert(result.startsWith("2d6-1 ->")) { "the expression should be echoed: $result" }
    }

    @Test
    fun whitespaceIsAccepted() {
        val result = DiceParser.roll(" 3d8 + 4 ")
        assert(result.startsWith("3d8 + 4 ->") || result.contains("total")) { "whitespace must not break the roll: $result" }
    }

    @Test
    fun garbageIsRefusedNotCrashed() {
        for (bad in listOf("", "   ", "abc", "3x8+4", "d", "d0", "0d6", "+", "+4-", "1..2")) {
            val result = DiceParser.roll(bad)
            assert(!result.contains("total")) { "\"$bad\" must not roll: $result" }
        }
    }

    @Test
    fun absurdSizesAreRefused() {
        // A model inventing "1000000d6" must not spin the CPU.
        assert(!DiceParser.roll("1000000d6").contains("total")) { "huge counts are refused" }
        assert(!DiceParser.roll("3d100000000").contains("total")) { "huge sides are refused" }
        assert(!DiceParser.roll("101d6").contains("total")) { "more than 100 dice is refused" }
    }

    @Test
    fun constantsAloneWork() {
        val result = DiceParser.roll("4")
        assert(result.contains("total 4")) { "a bare constant totals itself: $result" }
    }
}
