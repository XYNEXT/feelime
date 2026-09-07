package com.feelime.ime.nativeengine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class ZiranmaDoublePinyinGoldenTest {
    @Test fun allInitialsFinalsAndZeroInitials() {
        val fixture = JSONObject(fixtureFile().readText())
        val initials = fixture.getJSONObject("initials")
        val finals = fixture.getJSONObject("finals")
        val zero = fixture.getJSONObject("zeroInitialExamples")
        val zeroAllowed = fixture.getJSONObject("zeroInitialAllowedSpellings")
        val golden = fixture.getJSONObject("goldenSyllables")

        assertEquals("v", initials.getString("zh"))
        assertEquals("i", initials.getString("ch"))
        assertEquals("u", initials.getString("sh"))
        listOf("iu", "ia", "ua", "uan", "er", "ue", "uai", "ing", "iong", "ong",
            "iang", "uang", "iao", "ie", "ui", "un", "ian").forEach { final ->
            assertTrue("missing compound final $final", finals.has(final))
        }
        zero.keys().forEach { syllable ->
            assertEquals(encode(syllable, initials, finals), zero.getString(syllable))
        }
        golden.keys().forEach { syllable ->
            val spellings = golden.getJSONArray(syllable)
            assertTrue(
                "canonical spelling missing for $syllable",
                spellings.let { array -> (0 until array.length()).any { array.getString(it) == encode(syllable, initials, finals) } },
            )
        }

        val algebra = parseAlgebra(schemaFile())
        zeroAllowed.keys().forEach { syllable ->
            val expected = zeroAllowed.getJSONArray(syllable).let { array ->
                (0 until array.length()).mapTo(linkedSetOf()) { array.getString(it) }
            }
            assertEquals(
                "source schema must accept exactly the frozen zero-initial set for $syllable",
                expected,
                applyAlgebra(syllable, algebra),
            )
            assertEquals(
                "compiled production prism must match the source schema for $syllable",
                expected,
                prismSpellings(syllable),
            )
            assertTrue("canonical spelling missing for $syllable", zero.getString(syllable) in expected)
        }
        golden.keys().forEach { syllable ->
            val expected = golden.getJSONArray(syllable).let { array ->
                (0 until array.length()).mapTo(linkedSetOf()) { array.getString(it) }
            }
            assertEquals("source schema mismatch for $syllable", expected, applyAlgebra(syllable, algebra))
            assertEquals("compiled production prism mismatch for $syllable", expected, prismSpellings(syllable))
        }
        assertEquals(setOf("aa", "oa", "a", "o"), prismSpellings("a"))
    }

    private sealed interface Rule {
        data class Regex(
            val pattern: Pattern,
            val replacement: String,
            val erase: Boolean,
            val preserveSource: Boolean,
        ) : Rule
        data class Xlit(val from: String, val to: String) : Rule
    }

    private fun parseAlgebra(schema: File): List<Rule> = schema.readLines()
        .map(String::trim)
        .filter { it.startsWith("- ") }
        .mapNotNull { line ->
            val expression = line.removePrefix("- ").trim().trim('"')
            when {
                expression.startsWith("xform/") || expression.startsWith("derive/") ||
                    expression.startsWith("erase/") || expression.startsWith("abbrev/") -> {
                    val operation = expression.substringBefore('/')
                    val pieces = expression.substringAfter('/').split('/')
                    val pattern = pieces.getOrNull(0) ?: error("bad schema algebra: $line")
                    val replacement = if (operation == "erase") "" else pieces.getOrNull(1)
                        ?: error("bad schema algebra: $line")
                    Rule.Regex(
                        Pattern.compile(pattern), replacement,
                        erase = operation == "erase",
                        // abbrev behaves like derive for spelling-set purposes; librime
                        // only halves the weight of abbreviation-derived spellings.
                        preserveSource = operation == "derive" || operation == "abbrev",
                    )
                }
                expression.startsWith("xlit/") -> {
                    val pieces = expression.substringAfter('/').split('/')
                    Rule.Xlit(pieces[0], pieces[1])
                }
                else -> null
            }
        }

    private fun applyAlgebra(input: String, rules: List<Rule>): Set<String> {
        var values = linkedSetOf(input)
        rules.forEach { rule ->
            when (rule) {
                is Rule.Regex -> {
                    val next = linkedSetOf<String>()
                    values.forEach { value ->
                        val matcher = rule.pattern.matcher(value)
                        if (matcher.find()) {
                            val transformed = matcher.replaceFirst(rule.replacement)
                            if (!rule.erase || transformed.isNotEmpty()) next += transformed
                            if (rule.preserveSource) next += value
                        } else next += value
                    }
                    values = next
                }
                is Rule.Xlit -> values = values.mapTo(linkedSetOf()) { value ->
                    value.map { char ->
                        val index = rule.from.indexOf(char)
                        if (index >= 0) rule.to[index] else char
                    }.joinToString("")
                }
            }
        }
        return values
    }

    private fun encode(syllable: String, initials: JSONObject, finals: JSONObject): String {
        if (syllable.first() in "aoe") return "o" + finals.getString(syllable)
        val initial = initials.keys().asSequence()
            .filter(syllable::startsWith)
            .maxByOrNull(String::length) ?: error("no initial for $syllable")
        val final = syllable.removePrefix(initial)
        return initials.getString(initial) + finals.getString(final)
    }

    private fun fixtureFile(): File = repoFile("test-fixtures/text/ziranma-double-pinyin.json")
    private fun schemaFile(): File = repoFile(
        "spikes/native-engine-smoke/original-schemas/ziranma_double_pinyin.schema.yaml",
    )

    private fun prismSpellings(syllable: String): Set<String> {
        // The dumper prints the spelling column once per spelling group;
        // continuation rows (abbrev entries with a weight) leave col0 empty
        // and inherit the previous row's spelling.
        var current = ""
        val spellings = linkedSetOf<String>()
        repoFile("app/src/main/assets/engine-data/rime/ziranma_double_pinyin.prism.txt")
            .useLines { lines ->
                lines.forEach { line ->
                    val fields = line.split('\t')
                    if (fields.size < 2) return@forEach
                    if (fields[0].isNotEmpty()) current = fields[0]
                    if (current.isNotEmpty() && fields[1] == syllable) spellings += current
                }
            }
        return spellings
    }

    private fun repoFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is unavailable")
        var current = File(userDir).canonicalFile
        repeat(8) {
            val candidate = File(current, path)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("could not locate repository file $path from ${System.getProperty("user.dir")}")
    }
}
