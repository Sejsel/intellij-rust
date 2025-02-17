/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.rust.ide.icons.RsIcons
import org.rust.lang.core.macros.RsExpandedElement
import org.rust.lang.core.macros.decl.MacroGraph
import org.rust.lang.core.macros.decl.MacroGraphBuilder
import org.rust.lang.core.psi.*
import org.rust.lang.core.stubs.RsMacroStub
import org.rust.stdext.HashCode
import java.util.*
import javax.swing.Icon

abstract class RsMacroImplMixin : RsStubbedNamedElementImpl<RsMacroStub>,
                                  RsMacro {

    constructor(node: ASTNode) : super(node)

    constructor(stub: RsMacroStub, elementType: IStubElementType<*, *>) : super(stub, elementType)

    override fun getNameIdentifier(): PsiElement? =
        findChildrenByType<PsiElement>(RsElementTypes.IDENTIFIER)
            .getOrNull(1) // Zeroth is `macro_rules` itself

    override fun getIcon(flags: Int): Icon? = RsIcons.MACRO

    override val crateRelativePath: String? get() = name?.let { "::$it" }

    override val modificationTracker: SimpleModificationTracker =
        SimpleModificationTracker()

    override fun incModificationCount(element: PsiElement): Boolean {
        modificationTracker.incModificationCount()
        return false // force rustStructureModificationTracker to be incremented
    }

    override fun getContext(): PsiElement? = RsExpandedElement.getContextImpl(this)

    override val macroBodyStubbed: RsMacroBody?
        get() {
            val stub = stub ?: return macroBody
            val text = stub.macroBody ?: return null
            return CachedValuesManager.getCachedValue(this) {
                CachedValueProvider.Result.create(
                    RsPsiFactory(project, markGenerated = false).createMacroBody(text),
                    modificationTracker
                )
            }
        }

    override val bodyHash: HashCode?
        get() {
            val stub = greenStub
            if (stub !== null) return stub.bodyHash
            return CachedValuesManager.getCachedValue(this, MACRO_BODY_HASH_KEY) {
                val body = macroBody?.text
                val hash = body?.let { HashCode.compute(it) }
                CachedValueProvider.Result.create(hash, modificationTracker)
            }
        }

    override val hasRustcBuiltinMacro: Boolean
        get() = HAS_RUSTC_BUILTIN_MACRO_PROP.getByPsi(this)

    override val preferredBraces: MacroBraces
        get() = stub?.preferredBraces ?: guessPreferredBraces()
}

/** "macro_rules" identifier of `macro_rules! foo {}`; guaranteed to be non-null by the grammar */
val RsMacro.macroRules: PsiElement
    get() = node.findChildByType(RsElementTypes.IDENTIFIER)!!.psi

val RsMacro.macroBody: RsMacroBody?
    get() = childOfType()

val HAS_MACRO_EXPORT_PROP: StubbedAttributeProperty<RsMacro, RsMacroStub> =
    StubbedAttributeProperty(QueryAttributes<*>::hasMacroExport, RsMacroStub::mayHaveMacroExport)
val HAS_MACRO_EXPORT_LOCAL_INNER_MACROS_PROP: StubbedAttributeProperty<RsMacro, RsMacroStub> =
    StubbedAttributeProperty(QueryAttributes<*>::hasMacroExportLocalInnerMacros, RsMacroStub::mayHaveMacroExportLocalInnerMacros)
val HAS_RUSTC_BUILTIN_MACRO_PROP: StubbedAttributeProperty<RsMacro, RsMacroStub> =
    StubbedAttributeProperty(QueryAttributes<*>::hasRustcBuiltinMacro, RsMacroStub::mayHaveRustcBuiltinMacro)

val RsMacro.hasMacroExport: Boolean
    get() = HAS_MACRO_EXPORT_PROP.getByPsi(this)

val QueryAttributes<*>.hasMacroExport: Boolean
    get() = hasAttribute("macro_export")

/** `#[macro_export(local_inner_macros)]` */
val RsMacro.hasMacroExportLocalInnerMacros: Boolean
    get() = HAS_MACRO_EXPORT_LOCAL_INNER_MACROS_PROP.getByPsi(this)

val QueryAttributes<*>.hasMacroExportLocalInnerMacros: Boolean
    get() = hasAttributeWithArg("macro_export", "local_inner_macros")

val QueryAttributes<*>.hasRustcBuiltinMacro: Boolean
    get() = hasAttribute("rustc_builtin_macro")

val RsMacro.isRustcDocOnlyMacro: Boolean
    get() = queryAttributes.isRustcDocOnlyMacro

val QueryAttributes<*>.isRustcDocOnlyMacro: Boolean
    get() = hasAttribute("rustc_doc_only_macro")

private val MACRO_BODY_HASH_KEY: Key<CachedValue<HashCode>> = Key.create("MACRO_BODY_HASH_KEY")

private val MACRO_GRAPH_KEY: Key<CachedValue<MacroGraph?>> = Key.create("MACRO_GRAPH_KEY")

val RsMacro.graph: MacroGraph?
    get() = CachedValuesManager.getCachedValue(this, MACRO_GRAPH_KEY) {
        val graph = MacroGraphBuilder(this).build()
        CachedValueProvider.Result.create(graph, modificationTracker)
    }
