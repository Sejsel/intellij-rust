/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Query
import org.rust.lang.core.psi.*
import org.rust.openapiext.filterIsInstanceQuery
import org.rust.openapiext.mapQuery

interface RsStructOrEnumItemElement : RsQualifiedNamedElement, RsItemElement, RsGenericDeclaration, RsTypeDeclarationElement, RsNameIdentifierOwner

val RsStructOrEnumItemElement.derivedTraits: Collection<RsTraitItem>
    get() = deriveMetaItems
        .mapNotNull { it.resolveToDerivedTrait() }
        .toList()

val RsStructOrEnumItemElement.derivedTraitsToMetaItems: Map<RsTraitItem, RsMetaItem>
    get() = deriveMetaItems
        .mapNotNull { meta -> (meta.resolveToDerivedTrait())?.let { it to meta } }
        .toMap()

val RsStructOrEnumItemElement.deriveMetaItems: Sequence<RsMetaItem>
    get() = queryAttributes.deriveMetaItems

val QueryAttributes<RsMetaItem>.deriveMetaItems: Sequence<RsMetaItem>
    get() = deriveAttributes
        .flatMap { it.metaItemArgs?.metaItemList?.asSequence() ?: emptySequence() }

val RsStructOrEnumItemElement.firstKeyword: PsiElement?
    get() = when (this) {
        is RsStructItem -> vis ?: struct
        is RsEnumItem -> vis ?: enum
        else -> null
    }

fun RsStructOrEnumItemElement.searchForImplementations(): Query<RsImplItem> {
    return ReferencesSearch.search(this, useScope)
        .mapQuery { ref ->
            PsiTreeUtil.getTopmostParentOfType(ref.element, RsTypeReference::class.java)?.parent
        }
        .filterIsInstanceQuery()
}
