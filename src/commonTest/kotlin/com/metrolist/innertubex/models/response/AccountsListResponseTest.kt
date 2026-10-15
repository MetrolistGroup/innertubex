package com.metrolist.innertubex.models.response

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountsListResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun extractsPopupMenuAccountsAndSkipsUnrelatedRenderers() {
        val response =
            json.decodeFromString<AccountsListResponse>(
                """
                {"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[
                  {"unrelatedRenderer":{}},
                  {"accountSectionListRenderer":{"contents":[
                    {},
                    {"accountItemSectionRenderer":{"contents":[
                      {}, {"accountItem":$accountItem}, {"accountItem":{}}
                    ]}}
                  ]}}
                ]}}}}]}
                """.trimIndent(),
            )

        assertEquals(listOf(expectedAccount, emptyAccount), response.extractAccounts())
    }

    @Test
    fun extractsChannelSwitcherAccountsAndSkipsUnrelatedRenderers() {
        val response =
            json.decodeFromString<AccountsListResponse>(
                """
                {"actions":[{"updateChannelSwitcherPageAction":{"page":{"channelSwitcherPageRenderer":{"contents":[
                  {"unrelatedRenderer":{}}, {"accountItemRenderer":$accountItem}, {"accountItemRenderer":{}}
                ]}}}}]}
                """.trimIndent(),
            )

        assertEquals(listOf(expectedAccount, emptyAccount), response.extractAccounts())
    }

    @Test
    fun missingAndUnknownAccountMenusAreEmpty() {
        for (fixture in listOf(
            "{}",
            """{"actions":[{}, {"unknownAction":{}}, {"getMultiPageMenuAction":{}}, {"updateChannelSwitcherPageAction":{}}]}""",
            """{"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{}}}}]}""",
            """{"actions":[{"updateChannelSwitcherPageAction":{"page":{"channelSwitcherPageRenderer":{}}}}]}""",
        )) {
            assertTrue(json.decodeFromString<AccountsListResponse>(fixture).extractAccounts().isEmpty())
        }
    }

    private val accountItem =
        """
        {
          "accountName":{"runs":[{"text":"Fixture channel"}]},
          "accountByline":{"runs":[{"text":"Fixture byline"}]},
          "channelHandle":{"runs":[{"text":"@fixture"}]},
          "accountPhoto":{"thumbnails":[
            {"url":"https://example.test/small","width":32,"height":32},
            {"url":"https://example.test/large","width":128,"height":128}
          ]},
          "isSelected":true,
          "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
            {},
            {"accountSigninToken":{"signinUrl":"https://example.test/signin"}},
            {"pageIdToken":{"pageId":"fixture-page"}},
            {"datasyncIdToken":{"datasyncIdToken":"fixture-sync"}}
          ]}}
        }
        """.trimIndent()

    private val expectedAccount =
        AccountsListResponse.Account(
            name = "Fixture channel",
            byline = "Fixture byline",
            channelHandle = "@fixture",
            thumbnailUrl = "https://example.test/large",
            isSelected = true,
            signinUrl = "https://example.test/signin",
            pageId = "fixture-page",
            dataSyncId = "fixture-sync",
        )
    private val emptyAccount = AccountsListResponse.Account("", null, null, null, false, null, null, null)
}
