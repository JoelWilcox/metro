// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import org.jetbrains.kotlin.ir.types.IrType

internal class IrInjectClassData(private val metroContext: IrMetroContext) {
  private val scopeToClasses = mutableMapOf<IrAnnotation, MutableSet<IrType>>()
  private val externalScopeToClasses = mutableMapOf<IrAnnotation, Set<IrType>>()

  fun addContribution(scope: IrAnnotation, contribution: IrType) {
    scopeToClasses.getOrPut(scope) { mutableSetOf() }.add(contribution)
  }

  operator fun get(scope: IrAnnotation): Set<IrType> = buildSet {
    scopeToClasses[scope]?.let(::addAll)
    addAll(findExternalContributions(scope))
  }

  private fun findExternalContributions(scope: IrAnnotation): Set<IrType> {
    return externalScopeToClasses.getOrPut(scope) {
      val functionsInPackage =
        metroContext.pluginContext.referenceFunctions(Symbols.CallableIds.scopedInjectHint(scope))

      val filteredFunctions =
        functionsInPackage
          .filter { function ->
            function.owner.annotations.any { IrAnnotation(it) == scope }
          }
          .map { contribution ->
            // This is the single value param
            contribution.owner.regularParameters.single().type
          }
          .toSet()
      filteredFunctions.toSet()
    }
  }
}
