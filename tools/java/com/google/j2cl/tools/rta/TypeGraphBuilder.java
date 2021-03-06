/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.j2cl.tools.rta;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Streams.concat;
import static java.util.stream.Collectors.toSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedHashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import com.google.j2cl.libraryinfo.LibraryInfo;
import com.google.j2cl.libraryinfo.LibraryInfoBuilder;
import com.google.j2cl.libraryinfo.MemberInfo;
import com.google.j2cl.libraryinfo.MethodInvocation;
import com.google.j2cl.libraryinfo.TypeInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Give information about inheritance relationships between types. */
public class TypeGraphBuilder {

  static List<Type> build(List<LibraryInfo> libraryInfos) {
    List<Type> types = createTypes(libraryInfos);

    LinkedHashMultiset<Type> typesInTopologicalOrder = sortTypesInTopologicalOrder(types);

    addInheritedMembers(typesInTopologicalOrder);

    computeOverrideFrontier(ImmutableList.copyOf(typesInTopologicalOrder.elementSet()));

    return types;
  }

  private static List<Type> createTypes(List<LibraryInfo> libraryInfos) {
    Map<String, Type> typesByName = new HashMap<>();

    // Create all types and members.
    for (LibraryInfo libraryInfo : libraryInfos) {
      for (TypeInfo typeInfo : libraryInfo.getTypeList()) {
        Type type = Type.buildFrom(typeInfo, libraryInfo.getTypeMap(typeInfo.getTypeId()));
        typesByName.put(type.getName(), type);
      }
    }

    // Build cross-references between types and members
    for (LibraryInfo libraryInfo : libraryInfos) {
      addMethodReferences(typesByName, libraryInfo);
    }

    return ImmutableList.copyOf(typesByName.values());
  }

  private static void addMethodReferences(Map<String, Type> typesByName, LibraryInfo libraryInfo) {
    for (TypeInfo typeInfo : libraryInfo.getTypeList()) {
      Type type = typesByName.get(libraryInfo.getTypeMap(typeInfo.getTypeId()));
      type.setSuperTypes(
          concat(Stream.of(typeInfo.getExtendsType()), typeInfo.getImplementsTypeList().stream())
              .filter(x -> x != LibraryInfoBuilder.NULL_TYPE)
              .map(x -> checkNotNull(typesByName.get(libraryInfo.getTypeMap(x))))
              .collect(toImmutableList()));

      for (MemberInfo memberInfo : typeInfo.getMemberList()) {
        Member member = type.getMemberByName(memberInfo.getName());
        member.setReferencedTypes(
            memberInfo.getReferencedTypesList().stream()
                .map(libraryInfo::getTypeMap)
                .map(typesByName::get)
                .collect(toImmutableList()));

        addMethodReferences(libraryInfo, memberInfo.getInvokedMethodsList(), member, typesByName);
      }
    }
  }

  private static void addMethodReferences(
      LibraryInfo libraryInfo,
      List<MethodInvocation> methodInvocations,
      Member member,
      Map<String, Type> typesByName) {
    for (MethodInvocation methodInvocation : methodInvocations) {
      Type enclosingType =
          typesByName.get(libraryInfo.getTypeMap(methodInvocation.getEnclosingType()));
      Member referenceMember = enclosingType.getMemberByName(methodInvocation.getMethod());
      member.addReferencedMember(methodInvocation.getKind(), referenceMember);
    }
  }

  /** Add inherited members to all types. */
  private static void addInheritedMembers(LinkedHashMultiset<Type> typesInTopologicalOrder) {
    for (Type type : typesInTopologicalOrder.elementSet()) {
      Set<String> declaredMemberNames =
          type.getMembers().stream().map(Member::getName).collect(toSet());
      Map<String, Member> inheritedMemberByName = new HashMap<>();

      for (Type superType : type.getSuperTypes()) {
        for (Member member : superType.getMembers()) {
          String memberName = member.getName();
          if (declaredMemberNames.contains(memberName)) {
            // Member declared in the type, not inherited.
            continue;
          }

          // Member is inherited, keep from the parents the one with the highest topological
          // number.
          // Ex:
          // interface IFoo {       interface IBar extends IFoo {
          //   void f();              void f();
          // }                      }
          //
          // interface IFooBar extends IFoo, IBar {}
          //
          // IFooBar can inherits f() from either IFoo or IBar. We choose IBar::f because IBar
          // is topologically greater than IFoo (IFoo being a parent of IBar)
          inheritedMemberByName.putIfAbsent(memberName, member);

          Member priorCandidateMember = inheritedMemberByName.get(memberName);

          if (isTopologicallyGreater(
              typesInTopologicalOrder,
              member.getDeclaringType(),
              priorCandidateMember.getDeclaringType())) {
            inheritedMemberByName.put(memberName, member);
          }
        }
      }

      type.addMembers(inheritedMemberByName.values());
    }
  }

  private static boolean isTopologicallyGreater(
      Multiset<Type> typesInTopologicalOrder, Type type, Type priorCandidate) {
    return typesInTopologicalOrder.count(type) > typesInTopologicalOrder.count(priorCandidate);
  }

  private static void computeOverrideFrontier(List<Type> typesInTopologicalOrder) {
    // Build the overrides set by starting from children and check if a member with same name exist
    // on the parent with a different member identifiers.
    // visit children first
    for (Type type : Lists.reverse(typesInTopologicalOrder)) {
      for (Member member : type.getMembers()) {
        // register this type as inheriting the current member.
        member.addInheritingType(type);

        String memberName = member.getName();

        for (Type superType : type.getSuperTypes()) {
          Member parentMember = superType.getMemberByName(memberName);

          if (parentMember == null) {
            continue;
          }

          if (!member.equals(parentMember)) {
            // Member is overridden by the current type.

            // We register the type defining the member as the overriding type of the parent member.
            // Let's take the following example:
            //
            // class A {      class B extends A implements I {}     interface I {
            //   foo();                                               foo();
            // }                                                    }
            //
            // in this case A needs to be registered as a type that provides an implementation of
            // I:foo() because A:foo() is inherited by B.

            parentMember.addOverridingType(member.getDeclaringType());
          }
        }
      }
    }
  }

  /**
   * Returns a LinkedHashMultiset where elements are the types sorted in order such as for every
   * types, its super types comes first and implementation class comes after any interface that is
   * its sibling.
   *
   * <pre>
   *   class B {}
   *   class A extends B implements IA {}
   *   class D extends A implements ID {}
   *
   *  The types could be sorted as: IA, B, ID, A, D
   * </pre>
   *
   * <p>The getCount() member on the returned MultiSet can be used to know the topological number of
   * a type.
   */
  private static LinkedHashMultiset<Type> sortTypesInTopologicalOrder(List<Type> types) {
    LinkedHashMultiset<Type> topologicalSortedSet = LinkedHashMultiset.create();

    // Insert all interfaces first so that they have a smaller topological number than any class.
    for (Type type : types) {
      if (!type.isInterface()) {
        continue;
      }
      insertInTopologicalOrder(type, topologicalSortedSet);
    }

    // Now insert all classes.
    for (Type type : types) {
      insertInTopologicalOrder(type, topologicalSortedSet);
    }

    checkState(topologicalSortedSet.elementSet().size() == types.size());

    return topologicalSortedSet;
  }

  private static void insertInTopologicalOrder(Type type, Multiset<Type> topologicalSortedSet) {
    if (topologicalSortedSet.contains(type)) {
      return;
    }

    for (Type supertype : type.getSuperTypes()) {
      insertInTopologicalOrder(supertype, topologicalSortedSet);
    }

    topologicalSortedSet.add(type, topologicalSortedSet.elementSet().size() + 1);
  }
}
