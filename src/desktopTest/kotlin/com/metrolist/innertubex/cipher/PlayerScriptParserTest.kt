package com.metrolist.innertubex.cipher

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerScriptParserTest {
    @Test
    fun resolvesRolesFromCallSitesRegardlessOfDeclarationOrderOrUnrelatedCandidates() {
        val n = "var n=function(a){a=a.split(\"\");a=a.slice(1);return a.join(\"\")};"
        val sig = "var sig=function(a){a=a.split(\"\");a.reverse();return a.join(\"\")};"
        val unrelated = "var other=function(a){a=a.split(\"\");a.pop();return a.join(\"\")};"
        for (declarations in listOf(sig + n, n + sig, unrelated + sig + n)) {
            val result =
                PlayerScriptParser.parse(
                    declarations + "params.set('n', n(value));params.set('signature', encodeURIComponent(sig(value)));",
                )
            assertEquals("n", result.nInvoker)
            assertEquals("sig", result.sigInvoker)
        }
    }

    @Test
    fun orderOnlyAndConflictingCallSitesAreRejected() {
        val code =
            "var sig=function(a){a=a.split(\"\");a.reverse();return a.join(\"\")};" +
                "var n=function(a){a=a.split(\"\");a.pop();return a.join(\"\")};"
        val ambiguous = PlayerScriptParser.parse(code)
        assertNull(ambiguous.nInvoker)
        assertNull(ambiguous.sigInvoker)
        assertNull(PlayerScriptParser.parse(code + "params.set('n', sig(value));params.set('n', n(value));").nInvoker)
    }

    @Test
    fun extractsFunctionsAcrossLiteralsCommentsRegexAndTemplateExpressions() {
        val code =
            """
            var n = function(a){a=a.split(""); /* } */ var x = /[{}]/; a.reverse(); return a.join("")}
            var sig = function(a){ // }
              a=a.split("");
              const t = `literal } ${'$'}{{value: "}"}}`;
              const q = 'escaped \\\\';
              a.reverse();
              return a.join("")
            }
            params.set("n", n(value));
            params.set("signature", sig(value));
            """.trimIndent()

        val result = PlayerScriptParser.parse(code)

        assertEquals("n", result.nInvoker)
        assertEquals("sig", result.sigInvoker)
        assertContains(result.nFunctionCode!!, "return a.join")
        assertContains(result.sigFunctionCode!!, "const t")
    }

    @Test
    fun escapedQuoteParityDoesNotEndFunctionEarly() {
        val code = "var f = function(a){a=a.split(\"\"); var s = 'a\\\\'; var o = {x: 1}; return a.join(\"\")}"
        val result = PlayerScriptParser.parse(code + "params.set(\"n\", f(value));")
        assertContains(result.nFunctionCode!!, "var o = {x: 1}")
    }

    @Test
    fun regexAfterKeywordDoesNotEndFunctionAndMalformedFunctionIsRejected() {
        val code =
            "var f=function(a){a=a.split(\"\");var r=function(){return /}/.test(\"}\")};" +
                "if(a.length) /* } */ /}/.test(\"}\");return a.join(\"\")}"

        assertContains(PlayerScriptParser.parse(code + "params.set(\"n\", f(value));").nFunctionCode!!, "return /}/")
        assertContains(PlayerScriptParser.parse(code + "params.set(\"n\", f(value));").nFunctionCode!!, "if(a.length) /* } */ /}/")
        assertNull(PlayerScriptParser.parse("var f=function(a){a=a.split(\"\");return a.join(\"\")").nFunctionCode)
    }
}
