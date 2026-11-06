package com.circaso.designsystem.coins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.components.CoinIcon
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** The credits sheet's body (You → Acknowledgements): the open-source work the app ships, with its licence. */
@Composable
fun ColumnScope.AcknowledgementsSheetContent(onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    SheetHead("Acknowledgements", onClose)
    Column(
        Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            CoinIcon("USDC")
            Column {
                BasicText("Web3 Icons", style = t.headline.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                BasicText("Coin and network icons, by 0xa3k5", style = t.footnote.copy(color = c.inkSecondary))
            }
        }
        BasicText(WEB3_ICONS_LICENCE, style = t.footnote.copy(color = c.inkSecondary))
    }
}

/** The MIT notice for `@web3icons/core` (github.com/0xa3k5/web3icons), as its licence asks to be kept. */
const val WEB3_ICONS_LICENCE = """MIT License

Copyright (c) 2024 0xa3k5

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""
