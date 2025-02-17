/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.formatter

import com.intellij.psi.codeStyle.CodeStyleSettings
import org.rust.ide.formatter.settings.RsCodeStyleSettings
import org.rust.openapiext.Testmark

val CodeStyleSettings.rust: RsCodeStyleSettings
    get() = getCustomSettings(RsCodeStyleSettings::class.java)

object RustfmtTestmarks {
    val rustfmtUsed: Testmark = Testmark("rustfmtUsed")
    val builtinPostProcess: Testmark = Testmark("builtinPostProcess")
}
