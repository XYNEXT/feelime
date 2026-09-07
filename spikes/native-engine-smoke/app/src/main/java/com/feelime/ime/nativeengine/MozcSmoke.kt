package com.feelime.ime.nativeengine

import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import org.json.JSONArray
import org.json.JSONObject

internal object MozcSmoke {
    private const val SPACE = 4
    private const val BACKSPACE = 12
    private const val F7 = 25
    private const val UNDO = 9
    private const val SELECT_CANDIDATE = 3

    fun run(userDir: String, dataPath: String): JSONObject {
        check(MozcJNI.onPostLoad(userDir, dataPath)) { "Mozc onPostLoad failed" }
        eval(ProtoWire.setRequest())
        val cases = JSONArray()
        val kanji = runCandidateSelection("konnichiha", "今日は")
        cases.put(kanji)
        cases.put(runPreedit("gakkou"))
        cases.put(runConversion("gakkou", F7))
        cases.put(runPreedit("nn"))
        cases.put(runPreedit("nna"))
        cases.put(runPreedit("nnna"))
        cases.put(runPreedit("n'"))
        cases.put(runPreedit("sha"))
        cases.put(runConversion("ko-hi-", SPACE))
        cases.put(runPreedit("."))
        cases.put(runPreedit(","))
        cases.put(runCandidateSelection("kanji", "漢字"))
        val undo = runUndo()
        val conversionBackspace = runConversionBackspace()
        return JSONObject()
            .put("dataVersion", MozcJNI.getDataVersion())
            .put("cases", cases)
            .put("undo", undo)
            .put("conversionBackspace", conversionBackspace)
    }

    private fun runCandidateSelection(roman: String, expected: String): JSONObject = withSession { id ->
        type(id, roman)
        val converted = eval(ProtoWire.sendSpecial(id, SPACE))
        val candidates = candidateWords(converted)
        val chosen = candidates.firstOrNull { (_, value) -> value == expected }
            ?: error("Mozc conversion returned no '$expected' candidate: ${state(roman, converted)}")
        val selected = eval(ProtoWire.sendSessionCommand(id, SELECT_CANDIDATE, chosen.first))
        val committed = eval(ProtoWire.sendSpecial(id, 5))
        state(roman, converted)
            .put("selectedCandidateId", chosen.first)
            .put("selectedCandidate", chosen.second)
            .put("selectionOutput", state("selection", selected))
            .put("commitOutput", state("commit", committed))
    }

    private fun runConversion(roman: String, special: Int): JSONObject = withSession { id ->
        var output = type(id, roman)
        output = eval(ProtoWire.sendSpecial(id, special))
        state(roman, output)
    }

    private fun runPreedit(roman: String): JSONObject = withSession { id ->
        state(roman, type(id, normalizeRomanInput(roman)))
    }

    private fun runUndo(): JSONObject = withSession { id ->
        type(id, "konnichiha")
        eval(ProtoWire.sendSpecial(id, SPACE))
        val committed = eval(ProtoWire.sendSpecial(id, 5))
        val committedText = ProtoWire.nested(committed, 4)
            .firstOrNull { it.number == 2 }?.text().orEmpty()
        state(
            "undo",
            eval(ProtoWire.sendSessionCommand(id, UNDO, precedingText = committedText)),
        ).put("committedBeforeUndo", committedText)
    }

    private fun runConversionBackspace(): JSONObject = withSession { id ->
        type(id, "kanji")
        val converted = eval(ProtoWire.sendSpecial(id, SPACE))
        val reverted = eval(ProtoWire.sendSpecial(id, BACKSPACE))
        val shortened = eval(ProtoWire.sendSpecial(id, BACKSPACE))
        val normalizedShortened = if (state("shortened", shortened).getString("preedit") == "かんじ") {
            // Mozc first dismisses the post-conversion suggestion window.  A
            // production adapter coalesces this no-op and sends one more DEL
            // so one user Backspace still removes one composing unit.
            eval(ProtoWire.sendSpecial(id, BACKSPACE))
        } else {
            shortened
        }
        JSONObject()
            .put("converted", state("converted", converted))
            .put("reverted", state("reverted", reverted))
            .put("shortened", state("shortened", shortened))
            .put("adapterNormalizedShortened", state("adapterNormalizedShortened", normalizedShortened))
    }

    private inline fun withSession(block: (Long) -> JSONObject): JSONObject {
        val created = eval(ProtoWire.createSession())
        val id = ProtoWire.integer(created, 1) ?: error("Mozc CREATE_SESSION returned no id")
        return try {
            block(id)
        } finally {
            eval(ProtoWire.deleteSession(id))
        }
    }

    private fun type(id: Long, roman: String): List<ProtoField> {
        var output = emptyList<ProtoField>()
        roman.forEach { output = eval(ProtoWire.sendCharacter(id, it.code)) }
        return output
    }

    // Mozc parses raw "nna" as んあ.  The IME contract follows the common
    // romaji expectation んな, so the adapter makes the mora boundary
    // explicit without changing what is displayed as raw input.
    private fun normalizeRomanInput(roman: String): String = roman.replace("nna", "nnna")

    private fun eval(command: ByteArray): List<ProtoField> =
        ProtoWire.output(MozcJNI.evalCommand(command))

    private fun state(label: String, output: List<ProtoField>): JSONObject {
        val preedit = ProtoWire.nested(output, 5)
            .filter { it.number == 2 && it.wireType == 3 }
            .flatMap { it.group.orEmpty() }
            .filter { it.number == 4 }
            .mapNotNull(ProtoField::text)
            .joinToString("")
        val candidates = candidateWords(output).map { it.second }
        val result = ProtoWire.nested(output, 4)
            .firstOrNull { it.number == 2 }
            ?.text().orEmpty()
        val deletion = ProtoWire.nested(output, 16)
        val deletionOffset = ProtoWire.integer(deletion, 1)?.toInt()
        val deletionLength = ProtoWire.integer(deletion, 2)?.toInt()
        return JSONObject()
            .put("input", label)
            .put("consumed", ProtoWire.integer(output, 3) == 1L)
            .put("preedit", preedit)
            .put("result", result)
            .put("deletionOffset", deletionOffset ?: JSONObject.NULL)
            .put("deletionLength", deletionLength ?: JSONObject.NULL)
            .put("candidates", JSONArray(candidates))
    }

    private fun candidateWords(output: List<ProtoField>): List<Pair<Long, String>> {
        val window = ProtoWire.nested(output, 6)
            .filter { it.number == 3 && it.wireType == 3 }
            .mapNotNull { candidate ->
                val fields = candidate.group.orEmpty()
                val id = fields.firstOrNull { it.number == 9 }?.integer ?: return@mapNotNull null
                val value = fields.firstOrNull { it.number == 5 }?.text() ?: return@mapNotNull null
                id to value
            }
        val all = ProtoWire.nested(output, 14)
            .filter { it.number == 2 && it.wireType == 2 }
            .mapNotNull { candidate ->
                val fields = candidate.bytes?.let(ProtoWire::fields).orEmpty()
                val id = fields.firstOrNull { it.number == 1 }?.integer ?: return@mapNotNull null
                val value = fields.firstOrNull { it.number == 4 }?.text() ?: return@mapNotNull null
                id to value
            }
        return (window + all).distinctBy { it.first }
    }

    private fun isKanji(character: Char): Boolean = character.code in 0x3400..0x9fff
}
