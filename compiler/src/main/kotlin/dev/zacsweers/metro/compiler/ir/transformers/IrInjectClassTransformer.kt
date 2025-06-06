// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.joinSimpleNames
import org.jetbrains.kotlin.descriptors.ClassKind
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.builder.buildPackageDirective
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildFile
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFile
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fileEntry
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.ClassId

internal class IrInjectClassTransformer(
  private val context: IrMetroContext,
  val moduleFragment: IrModuleFragment,
) : IrElementTransformerVoid(), IrMetroContext by context {

  private val transformedClasses = mutableSetOf<ClassId>()

  val data: IrInjectClassData = IrInjectClassData(context)

  override fun visitClass(declaration: IrClass): IrStatement {
    if (context.options.enableInjectConstructorHints) {
      transformScopedClass(declaration)
    }

    return super.visitClass(declaration)
  }

  private fun transformScopedClass(declaration: IrClass) {
    // If the class has a contribution then there's no functional need for a scoped constructor hint
    if (
      declaration.isAnnotatedWithAny(context.symbols.classIds.allContributesAnnotations) ||
        !declaration.isAnnotatedWithAny(context.symbols.classIds.injectAnnotations)
    ) {
      return
    }

    val scopes =
      declaration.annotationsAnnotatedWithAny(context.symbols.classIds.scopeAnnotations).map {
        IrAnnotation(it)
      }

    scopes.forEach { scope -> data.addContribution(scope, declaration.defaultType) }

    val classId = declaration.classIdOrFail
    if (classId !in transformedClasses) {
      // TODO(joel) need to create a companion object if it doesnt already exist
      // if (declaration.companionObject() == null) {
      //   declaration.addChild(
      //     pluginContext.irFactory
      //       .buildClass {
      //         origin = Origins.Default
      //         name = "CompanionForHints".asName()
      //         kind = ClassKind.OBJECT
      //         isCompanion = true
      //         modality = Modality.OPEN
      //       }.apply {
      //
      //       }
      //   )
      // }
      val companion = declaration.companionObject()
      for (scope in scopes) {
        declaration.generateAccessor(scope, companion!!)
        declaration.generateLookupHint(scope)
      }
      declaration.dumpToMetroLog()
    }
    transformedClasses += classId
  }

  private fun IrClass.generateAccessor(scope: IrAnnotation, companion: IrClass) {
    val bindingType = this.defaultType

    val name = Symbols.CallableIds.scopedInjectAccessor(scope).callableName
    companion
      .addFunction {
        this.name = name
        this.returnType = bindingType
        this.modality = Modality.ABSTRACT
      }
      .apply {
        annotations += scope.ir
        setDispatchReceiver(thisReceiver?.copyTo(this))
        pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(this)
      }
  }

  // TODO(joel) we could share most of this logic with [ContributionHintIrTransformer]
  private fun IrClass.generateLookupHint(scope: IrAnnotation) {
    val callableName = Symbols.CallableIds.scopedInjectHint(scope).callableName
    val bindingType = this.defaultType

    val function =
      pluginContext.irFactory
        .buildFun {
          name = callableName
          origin = Origins.Default
          returnType = pluginContext.irBuiltIns.unitType
        }
        .apply {
          parameters +=
            buildValueParameter(this) {
              name = Symbols.Names.instance
              type = bindingType
              kind = IrParameterKind.Regular
            }
          body = stubExpressionBody(metroContext)
          annotations += scope.ir
        }

    val fileNameWithoutExtension =
      sequence {
          val classId = classIdOrFail
          yieldAll(classId.packageFqName.pathSegments())
          yield(classId.joinSimpleNames(separator = "", camelCase = true).shortClassName)
          yield(callableName)
        }
        .joinToString(separator = "") { it.asString().capitalizeUS() }
        .decapitalizeUS()

    val fileName = "${fileNameWithoutExtension}.kt"
    val firFile = buildFile {
      moduleData = (metadata as FirMetadataSource.Class).fir.moduleData
      origin = FirDeclarationOrigin.Synthetic.PluginFile
      packageDirective = buildPackageDirective { packageFqName = Symbols.FqNames.metroHintsPackage }
      name = fileName
    }

    /*
    This is weird! In short, kotlinc's incremental compilation support _wants_ this to be an
    absolute path. We obviously don't have a real path to offer it here though since this is a
    synthetic file. However, if we just... make up a file path (in this case — a deterministic
    synthetic sibling file in the same directory as the source file), it seems to work fine.

    Is this good? Heeeeeell no. Will it probably some day break? Maybe. But for now, this works
    and we can keep an eye on https://youtrack.jetbrains.com/issue/KT-74778 for a better long term
    solution.
    */
    val fakeNewPath = Path(fileEntry.name).parent.resolve(fileName)
    val hintFile =
      IrFileImpl(
          fileEntry = NaiveSourceBasedFileEntryImpl(fakeNewPath.absolutePathString()),
          packageFragmentDescriptor =
            EmptyPackageFragmentDescriptor(
              moduleFragment.descriptor,
              Symbols.FqNames.metroHintsPackage,
            ),
          module = moduleFragment,
        )
        .also { it.metadata = FirMetadataSource.File(firFile) }
    moduleFragment.addFile(hintFile)
    hintFile.addChild(function)
    pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
    hintFile.dumpToMetroLog(fakeNewPath.name)
  }
}
