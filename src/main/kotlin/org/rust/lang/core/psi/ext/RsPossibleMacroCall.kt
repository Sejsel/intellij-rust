/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.rust.lang.core.macros.RsExpandedElement
import org.rust.lang.core.macros.RsMacroDataWithHash
import org.rust.lang.core.macros.proc.ProcMacroApplicationService
import org.rust.lang.core.psi.*
import org.rust.lang.core.resolve.KnownDerivableTrait
import org.rust.lang.core.resolve2.resolveToMacroWithoutPsi
import org.rust.lang.core.resolve2.resolveToProcMacroWithoutPsi
import org.rust.lang.core.stubs.RsAttrProcMacroOwnerStub
import org.rust.lang.utils.evaluation.CfgEvaluator
import org.rust.openapiext.testAssert
import org.rust.stdext.HashCode

/**
 * A PSI element that can be a declarative or (function-like, derive or attribute) procedural macro call.
 * It is implemented by [RsMacroCall] and [RsMetaItem]. A _possible_ macro call is a _real_ macro call if
 * [isMacroCall] returns `true` for it.
 */
interface RsPossibleMacroCall : RsExpandedElement {
    val path: RsPath?
}

val RsPossibleMacroCall.isMacroCall: Boolean
    get() = when (this) {
        is RsMacroCall -> true
        is RsMetaItem -> RsProcMacroPsiUtil.canBeCustomDerive(this) && resolveToProcMacroWithoutPsi() != null
        else -> error("unreachable")
    }

/** A syntax-based lightweight check. Returns `false` if the element can't be a macro call */
val RsPossibleMacroCall.canBeMacroCall: Boolean
    get() = when (this) {
        is RsMacroCall -> true
        is RsMetaItem -> RsProcMacroPsiUtil.canBeCustomDerive(this)
        else -> error("unreachable")
    }

val RsPossibleMacroCall.shouldSkipMacroExpansion: Boolean
    get() = when (this) {
        is RsMetaItem -> !ProcMacroApplicationService.isEnabled()
            || KnownDerivableTrait.shouldUseHardcodedTraitDerive(name)
        else -> false
    }

val RsPossibleMacroCall.macroBody: String?
    get() = when (this) {
        is RsMacroCall -> macroBody
        is RsMetaItem -> {
            val owner = owner
            if (owner is RsStructOrEnumItemElement) {
                owner.preparedCustomDeriveMacroCallBody
            } else {
                null
            }
        }
        else -> error("unreachable")
    }

private val RsStructOrEnumItemElement.preparedCustomDeriveMacroCallBody: String?
    get() = CachedValuesManager.getCachedValue(this) {
        CachedValueProvider.Result(
            doPrepareCustomDeriveMacroCallBody(this),
            PsiModificationTracker.MODIFICATION_COUNT
        )
    }

/**
 * Does things that Rustc does before passing a body to a custom derive proc macro:
 * removes `cfg` and `derive` attributes, unwraps `cfg_attr` attributes, moves docs before other attributes.
 */
private fun doPrepareCustomDeriveMacroCallBody(owner: RsStructOrEnumItemElement): String? {
    val text = owner.stubbedText ?: return null
    val endOfAttrsOffset = owner.endOfAttrsOffset
    if (endOfAttrsOffset == 0) return null // Impossible? There must be at least one `derive` attribute
    val item = RsPsiFactory(owner.project, markGenerated = false)
        .createFile(text.substring(0, endOfAttrsOffset) + "struct S;")
        .firstChild as? RsStructOrEnumItemElement
        ?: return null
    val evaluator = CfgEvaluator.forCrate(owner.containingCrate ?: return null)
    val docs = mutableListOf<PsiElement>()
    val attrs = mutableListOf<RsMetaItem>()
    for (child in item.childrenWithLeaves) {
        when (child) {
            is RsDocCommentImpl -> docs += child
            is RsOuterAttr -> evaluator.expandCfgAttrs(sequenceOf(child.metaItem)).forEach {
                when (it.name) {
                    "cfg", "derive" -> Unit
                    "doc" -> docs += it
                    else -> attrs += it
                }
            }
            is PsiComment, is PsiWhiteSpace -> continue
            else -> {
                testAssert { child.startOffsetInParent == endOfAttrsOffset }
                break
            }
        }
    }

    val sb = StringBuilder(text.length)
    for (doc in docs) {
        if (doc is RsDocCommentImpl) {
            sb.append(doc.text)
            sb.append("\n")
        } else {
            sb.append("#[")
            sb.append(doc.text)
            sb.append("]\n")
        }
    }
    for (meta in attrs) {
        sb.append("#[")
        sb.append(meta.text)
        sb.append("]\n")
    }
    sb.append(text.substring(endOfAttrsOffset, text.length))
    return sb.toString()
}

val RsStructOrEnumItemElement.stubbedText: String?
    get() {
        val stub = (this as StubBasedPsiElementBase<*>).greenStub as? RsAttrProcMacroOwnerStub
        if (stub != null) {
            return stub.stubbedText
        }

        return text
    }

val RsStructOrEnumItemElement.endOfAttrsOffset: Int
    get() {
        val stub = (this as StubBasedPsiElementBase<*>).greenStub as? RsAttrProcMacroOwnerStub
        if (stub != null) {
            return stub.endOfAttrsOffset
        }

        val firstKeyword = firstKeyword ?: return 0
        return firstKeyword.startOffsetInParent
    }

val RsPossibleMacroCall.bodyHash: HashCode?
    get() = when (this) {
        is RsMacroCall -> bodyHash
        is RsMetaItem -> bodyHash
        else -> error("unreachable")
    }

private val RsMetaItem.bodyHash: HashCode?
    get() = (owner as? RsStructOrEnumItemElement)?.bodyHash

private val RsStructOrEnumItemElement.bodyHash: HashCode?
    get() {
        val stub = (this as StubBasedPsiElementBase<*>).greenStub as? RsAttrProcMacroOwnerStub
        if (stub != null) {
            // If `stub.bodyHash` is `null`, there are `cfg_attr` attributes
            return stub.bodyHash ?: preparedCustomDeriveMacroCallBody?.let { HashCode.compute(it) }
        }
        return if (rawAttributes.hasAttribute("cfg_attr")) {
            // There are `cfg_attr` attributes - the macro body (and hence the hash) depends on cfg configuration
            preparedCustomDeriveMacroCallBody?.let { HashCode.compute(it) }
        } else {
            // No cfg_attr attributes - just use a hash of the text
            stubbedText?.let { HashCode.compute(it) }
        }
    }

fun RsPossibleMacroCall.resolveToMacroWithoutPsi(): RsMacroDataWithHash<*>? = when (this) {
    is RsMacroCall -> resolveToMacroWithoutPsi()
    is RsMetaItem -> resolveToProcMacroWithoutPsi()
        ?.takeIf { it.procMacroKind == RsProcMacroKind.fromMacroCall(this) }
        ?.let { RsMacroDataWithHash.fromDefInfo(it) }
    else -> error("unreachable")
}
