package com.metrolist.innertubex.models.body

import com.metrolist.innertubex.models.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class AccountsListBody(
    val context: Context,
    val requestType: String,
    val callCircumstance: String,
)
