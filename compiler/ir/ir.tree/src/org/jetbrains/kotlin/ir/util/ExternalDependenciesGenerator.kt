/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.ir.util

import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

class ExternalDependenciesGenerator(val symbolTable: SymbolTable, private val irProviders: List<IrProvider>) {
    fun generateUnboundSymbolsAsDependencies() {
        // There should be exactly one DeclarationStubGenerator
        irProviders.filterIsInstance<DeclarationStubGenerator>().forEach { it.unboundSymbolGeneration = true }
        /*
            Deserializing a reference may lead to new unbound references, so we loop until none are left.
         */
        var unbound = symbolTable.allUnbound
        while (unbound.isNotEmpty()) {
            for (symbol in unbound) {
                // Symbol could get bound as a side effect of deserializing other symbols.
                if (!symbol.isBound) {
                    irProviders.getDeclaration(symbol)
                }
                assert(symbol.isBound) { "$symbol unbound even after deserialization attempt" }
            }

            unbound = symbolTable.allUnbound
        }

        irProviders.forEach { (it as? IrDeserializer)?.declareForwardDeclarations() }
    }
}

private val SymbolTable.allUnbound: List<IrSymbol>
    get() {
        val r = mutableListOf<IrSymbol>()
        r.addAll(unboundClasses)
        r.addAll(unboundConstructors)
        r.addAll(unboundEnumEntries)
        r.addAll(unboundFields)
        r.addAll(unboundSimpleFunctions)
        r.addAll(unboundProperties)
        r.addAll(unboundTypeParameters)
        r.addAll(unboundTypeAliases)
        return r
    }

fun List<IrProvider>.getDeclaration(symbol: IrSymbol): IrDeclaration =
    firstNotNullResult { provider ->
        provider.getDeclaration(symbol)
    } ?: error("Could not find declaration for unbound symbol $symbol")

// In most cases, IrProviders list consist of an optional deserializer and a DeclarationStubGenerator.
fun generateTypicalIrProviderList(
    moduleDescriptor: ModuleDescriptor,
    irBuiltins: IrBuiltIns,
    symbolTable: SymbolTable,
    deserializer: IrDeserializer? = null,
    externalDeclarationOrigin: ((DeclarationDescriptor) -> IrDeclarationOrigin)? = null,
    facadeClassGenerator: (DeserializedContainerSource) -> IrClass? = { null }
): List<IrProvider> {
    val stubGenerator = DeclarationStubGenerator(
        moduleDescriptor, symbolTable, irBuiltins.languageVersionSettings, externalDeclarationOrigin, facadeClassGenerator
    )
    return listOfNotNull(deserializer, stubGenerator).also {
        stubGenerator.irProviders = it
    }
}
