/*
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

package com.google.devtools.j2objc.translate;

import com.google.devtools.j2objc.Options;
import com.google.devtools.j2objc.ast.Assignment;
import com.google.devtools.j2objc.ast.ClassInstanceCreation;
import com.google.devtools.j2objc.ast.ConstructorInvocation;
import com.google.devtools.j2objc.ast.EnumConstantDeclaration;
import com.google.devtools.j2objc.ast.EnumDeclaration;
import com.google.devtools.j2objc.ast.ExpressionStatement;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.NativeDeclaration;
import com.google.devtools.j2objc.ast.NumberLiteral;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.SingleVariableDeclaration;
import com.google.devtools.j2objc.ast.Statement;
import com.google.devtools.j2objc.ast.StringLiteral;
import com.google.devtools.j2objc.ast.SuperConstructorInvocation;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.types.GeneratedMethodBinding;
import com.google.devtools.j2objc.types.GeneratedVariableBinding;
import com.google.devtools.j2objc.util.BindingUtil;
import com.google.devtools.j2objc.util.NameTable;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.List;

/**
 * Modifies enum types for Objective C.
 *
 * @author Keith Stanger
 */
public class EnumRewriter extends TreeVisitor {

  private GeneratedVariableBinding nameVar = null;
  private GeneratedVariableBinding ordinalVar = null;

  private GeneratedMethodBinding addEnumConstructorParams(IMethodBinding method) {
    GeneratedMethodBinding newMethod = new GeneratedMethodBinding(method);
    newMethod.addParameter(typeEnv.resolveIOSType("NSString"));
    newMethod.addParameter(typeEnv.resolveJavaType("int"));
    return newMethod;
  }

  @Override
  public void endVisit(EnumDeclaration node) {
    List<Statement> stmts = node.getClassInitStatements().subList(0, 0);
    int i = 0;
    for (EnumConstantDeclaration constant : node.getEnumConstants()) {
      IVariableBinding varBinding = constant.getVariableBinding();
      IMethodBinding binding =
          addEnumConstructorParams(constant.getMethodBinding().getMethodDeclaration());
      ClassInstanceCreation creation = new ClassInstanceCreation(binding);
      TreeUtil.copyList(constant.getArguments(), creation.getArguments());
      creation.getArguments().add(new StringLiteral(varBinding.getName(), typeEnv));
      creation.getArguments().add(new NumberLiteral(i++, typeEnv));
      creation.setHasRetainedResult(true);
      stmts.add(new ExpressionStatement(new Assignment(new SimpleName(varBinding), creation)));
    }

    addExtraNativeDecls(node);
  }

  @Override
  public boolean visit(MethodDeclaration node) {
    assert nameVar == null && ordinalVar == null;
    IMethodBinding binding = node.getMethodBinding();
    ITypeBinding declaringClass = binding.getDeclaringClass();
    if (!binding.isConstructor() || !declaringClass.isEnum()) {
      return false;
    }
    GeneratedMethodBinding newBinding = addEnumConstructorParams(node.getMethodBinding());
    node.setMethodBinding(newBinding);
    // Enum constructors can't be called other than to create the enum values,
    // so mark as synthetic to avoid writing the declaration.
    node.addModifiers(BindingUtil.ACC_SYNTHETIC);
    node.removeModifiers(Modifier.PUBLIC | Modifier.PROTECTED);
    node.addModifiers(Modifier.PRIVATE);
    newBinding.setModifiers((newBinding.getModifiers() & ~(Modifier.PUBLIC | Modifier.PROTECTED))
        | Modifier.PRIVATE | BindingUtil.ACC_SYNTHETIC);
    nameVar = new GeneratedVariableBinding(
        "__name", 0, typeEnv.resolveIOSType("NSString"), false, true, declaringClass, newBinding);
    ordinalVar = new GeneratedVariableBinding(
        "__ordinal", 0, typeEnv.resolveJavaType("int"), false, true, declaringClass, newBinding);
    node.getParameters().add(new SingleVariableDeclaration(nameVar));
    node.getParameters().add(new SingleVariableDeclaration(ordinalVar));
    return true;
  }

  @Override
  public void endVisit(MethodDeclaration node) {
    nameVar = ordinalVar = null;
  }

  @Override
  public void endVisit(ConstructorInvocation node) {
    assert nameVar != null && ordinalVar != null;
    node.setMethodBinding(addEnumConstructorParams(node.getMethodBinding()));
    node.getArguments().add(new SimpleName(nameVar));
    node.getArguments().add(new SimpleName(ordinalVar));
  }

  @Override
  public void endVisit(SuperConstructorInvocation node) {
    assert nameVar != null && ordinalVar != null;
    node.setMethodBinding(addEnumConstructorParams(node.getMethodBinding()));
    node.getArguments().add(new SimpleName(nameVar));
    node.getArguments().add(new SimpleName(ordinalVar));
  }

  private void addExtraNativeDecls(EnumDeclaration node) {
    String typeName = nameTable.getFullName(node.getTypeBinding());
    int numConstants = node.getEnumConstants().size();
    boolean swiftFriendly = Options.swiftFriendly();

    StringBuilder header = new StringBuilder();
    header.append(String.format(
        "+ (IOSObjectArray *)values;\n\n"
        + "+ (%s *)valueOfWithNSString:(NSString *)name;\n\n"
        + "- (id)copyWithZone:(NSZone *)zone;\n", typeName));

    // Append enum type suffix.
    String nativeName = NameTable.getNativeEnumName(typeName);

    // The native type is not declared for an empty enum.
    if (swiftFriendly && numConstants > 0) {
      header.append(String.format("- (%s)toNSEnum;\n", nativeName));
    }

    StringBuilder implementation = new StringBuilder();
    implementation.append(String.format(
        "+ (IOSObjectArray *)values {\n"
        + "  return %s_values();\n"
        + "}\n\n", typeName));

    implementation.append(String.format(
        "+ (%s *)valueOfWithNSString:(NSString *)name {\n"
        + "  return %s_valueOfWithNSString_(name);\n"
        + "}\n\n", typeName, typeName));

    if (swiftFriendly && numConstants > 0) {
      implementation.append(String.format(
          "- (%s)toNSEnum {\n"
              + "  return (%s)[self ordinal];\n"
              + "}\n\n", nativeName, nativeName));
    }

    // Enum constants needs to implement NSCopying. Being singletons, they can
    // just return self. No need to increment the retain count because enum
    // values are never deallocated.
    implementation.append("- (id)copyWithZone:(NSZone *)zone {\n  return self;\n}\n");

    node.getBodyDeclarations().add(NativeDeclaration.newInnerDeclaration(
        header.toString(), implementation.toString()));

    StringBuilder outerHeader = new StringBuilder();
    StringBuilder outerImpl = new StringBuilder();
    outerHeader.append(String.format(
        "FOUNDATION_EXPORT IOSObjectArray *%s_values();\n\n"
        + "FOUNDATION_EXPORT %s *%s_valueOfWithNSString_(NSString *name);\n\n"
        + "FOUNDATION_EXPORT %s *%s_fromOrdinal(NSUInteger ordinal);\n",
        typeName, typeName, typeName, typeName, typeName));

    outerImpl.append(String.format(
        "IOSObjectArray *%s_values() {\n"
        + "  %s_initialize();\n"
        + "  return [IOSObjectArray arrayWithObjects:%s_values_ count:%s type:%s_class_()];\n"
        + "}\n\n", typeName, typeName, typeName, numConstants, typeName));

    outerImpl.append(String.format(
        "%s *%s_valueOfWithNSString_(NSString *name) {\n"
        + "  %s_initialize();\n", typeName, typeName, typeName));
    if (numConstants > 0) {
      outerImpl.append(String.format(
          "  for (int i = 0; i < %s; i++) {\n"
          + "    %s *e = %s_values_[i];\n"
          + "    if ([name isEqual:[e name]]) {\n"
          + "      return e;\n"
          + "    }\n"
          + "  }\n", numConstants, typeName, typeName));
    }
    if (Options.useReferenceCounting()) {
      outerImpl.append(
          "  @throw [[[JavaLangIllegalArgumentException alloc] initWithNSString:name]"
          + " autorelease];\n");
    } else {
      outerImpl.append(
          "  @throw [[JavaLangIllegalArgumentException alloc] initWithNSString:name];\n");
    }
    outerImpl.append("  return nil;\n}\n\n");

    outerImpl.append(String.format(
        "%s *%s_fromOrdinal(NSUInteger ordinal) {\n", typeName, typeName));
    // Avoid "comparison of unsigned expression >= 0 is always true" error.
    if (numConstants == 0) {
      outerImpl.append("  return nil;\n}\n");
    } else {
      outerImpl.append(String.format(
          "  %s_initialize();\n"
          // Param is unsigned, so don't need to check lower bound.
          + "  if (ordinal >= %s) {\n"
          + "    return nil;\n"
          + "  }\n"
          + "  return %s_values_[ordinal];\n"
          + "}\n",
          typeName, numConstants, typeName));
    }

    node.getBodyDeclarations().add(NativeDeclaration.newOuterDeclaration(
        outerHeader.toString(), outerImpl.toString()));
  }
}