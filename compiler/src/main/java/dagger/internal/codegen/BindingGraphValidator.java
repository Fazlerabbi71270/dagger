/*
 * Copyright (C) 2015 Google, Inc.
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
package dagger.internal.codegen;

import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.auto.value.AutoValue;
import com.google.common.base.Equivalence;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.squareup.javapoet.TypeName;
import dagger.Component;
import dagger.Lazy;
import dagger.MapKey;
import dagger.internal.codegen.ComponentDescriptor.BuilderSpec;
import dagger.internal.codegen.ComponentDescriptor.ComponentMethodDescriptor;
import dagger.internal.codegen.SourceElement.HasSourceElement;
import dagger.producers.ProductionComponent;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Formatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import javax.inject.Provider;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleTypeVisitor6;
import javax.lang.model.util.Types;

import static com.google.auto.common.MoreElements.getAnnotationMirror;
import static com.google.auto.common.MoreTypes.asDeclared;
import static com.google.auto.common.MoreTypes.asExecutable;
import static com.google.auto.common.MoreTypes.asTypeElements;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Iterables.indexOf;
import static com.google.common.collect.Maps.filterKeys;
import static dagger.internal.codegen.ComponentDescriptor.ComponentMethodDescriptor.isOfKind;
import static dagger.internal.codegen.ComponentDescriptor.ComponentMethodKind.PRODUCTION_SUBCOMPONENT;
import static dagger.internal.codegen.ComponentDescriptor.ComponentMethodKind.SUBCOMPONENT;
import static dagger.internal.codegen.ConfigurationAnnotations.getComponentDependencies;
import static dagger.internal.codegen.ContributionBinding.indexMapBindingsByAnnotationType;
import static dagger.internal.codegen.ContributionBinding.indexMapBindingsByMapKey;
import static dagger.internal.codegen.ContributionBinding.Kind.IS_SYNTHETIC_KIND;
import static dagger.internal.codegen.ContributionBinding.Kind.SYNTHETIC_MULTIBOUND_MAP;
import static dagger.internal.codegen.ContributionType.indexByContributionType;
import static dagger.internal.codegen.ErrorMessages.CONTAINS_DEPENDENCY_CYCLE_FORMAT;
import static dagger.internal.codegen.ErrorMessages.DUPLICATE_SIZE_LIMIT;
import static dagger.internal.codegen.ErrorMessages.INDENT;
import static dagger.internal.codegen.ErrorMessages.MEMBERS_INJECTION_WITH_UNBOUNDED_TYPE;
import static dagger.internal.codegen.ErrorMessages.REQUIRES_AT_INJECT_CONSTRUCTOR_OR_PROVIDER_FORMAT;
import static dagger.internal.codegen.ErrorMessages.REQUIRES_AT_INJECT_CONSTRUCTOR_OR_PROVIDER_OR_PRODUCER_FORMAT;
import static dagger.internal.codegen.ErrorMessages.REQUIRES_PROVIDER_FORMAT;
import static dagger.internal.codegen.ErrorMessages.REQUIRES_PROVIDER_OR_PRODUCER_FORMAT;
import static dagger.internal.codegen.ErrorMessages.duplicateMapKeysError;
import static dagger.internal.codegen.ErrorMessages.inconsistentMapKeyAnnotationsError;
import static dagger.internal.codegen.ErrorMessages.nullableToNonNullable;
import static dagger.internal.codegen.ErrorMessages.stripCommonTypePrefixes;
import static dagger.internal.codegen.Util.componentCanMakeNewInstances;
import static javax.tools.Diagnostic.Kind.ERROR;

public class BindingGraphValidator {

  private final Elements elements;
  private final Types types;
  private final CompilerOptions compilerOptions;
  private final InjectBindingRegistry injectBindingRegistry;
  private final HasSourceElementFormatter hasSourceElementFormatter;
  private final MethodSignatureFormatter methodSignatureFormatter;
  private final DependencyRequestFormatter dependencyRequestFormatter;
  private final KeyFormatter keyFormatter;
  private final Key.Factory keyFactory;

  BindingGraphValidator(
      Elements elements,
      Types types,
      CompilerOptions compilerOptions,
      InjectBindingRegistry injectBindingRegistry,
      HasSourceElementFormatter hasSourceElementFormatter,
      MethodSignatureFormatter methodSignatureFormatter,
      DependencyRequestFormatter dependencyRequestFormatter,
      KeyFormatter keyFormatter,
      Key.Factory keyFactory) {
    this.elements = elements;
    this.types = types;
    this.compilerOptions = compilerOptions;
    this.injectBindingRegistry = injectBindingRegistry;
    this.hasSourceElementFormatter = hasSourceElementFormatter;
    this.methodSignatureFormatter = methodSignatureFormatter;
    this.dependencyRequestFormatter = dependencyRequestFormatter;
    this.keyFormatter = keyFormatter;
    this.keyFactory = keyFactory;
  }

  private class Validation {
    final BindingGraph subject;
    final ValidationReport.Builder<TypeElement> reportBuilder;
    final Optional<Validation> parent;

    Validation(BindingGraph subject, Optional<Validation> parent) {
      this.subject = subject;
      this.reportBuilder =
          ValidationReport.about(subject.componentDescriptor().componentDefinitionType());
      this.parent = parent;
    }

    Validation(BindingGraph topLevelGraph) {
      this(topLevelGraph, Optional.<Validation>absent());
    }

    BindingGraph topLevelGraph() {
      return parent.isPresent() ? parent.get().topLevelGraph() : subject;
    }

    ValidationReport<TypeElement> buildReport() {
      return reportBuilder.build();
    }

    void validateSubgraph() {
      validateComponentScope();
      validateDependencyScopes();
      validateComponentHierarchy();
      validateBuilders();

      for (ComponentMethodDescriptor componentMethod :
           subject.componentDescriptor().componentMethods()) {
        Optional<DependencyRequest> entryPoint = componentMethod.dependencyRequest();
        if (entryPoint.isPresent()) {
          traverseRequest(
              entryPoint.get(),
              new ArrayDeque<ResolvedRequest>(),
              new LinkedHashSet<BindingKey>(),
              subject,
              new HashSet<DependencyRequest>());
        }
      }

      for (Map.Entry<ComponentMethodDescriptor, ComponentDescriptor> entry :
          filterKeys(
                  subject.componentDescriptor().subcomponents(),
                  isOfKind(SUBCOMPONENT, PRODUCTION_SUBCOMPONENT))
              .entrySet()) {
        validateSubcomponentFactoryMethod(
            entry.getKey().methodElement(), entry.getValue().componentDefinitionType());
      }

      for (BindingGraph subgraph : subject.subgraphs().values()) {
        Validation subgraphValidation = new Validation(subgraph, Optional.of(this));
        subgraphValidation.validateSubgraph();
        reportBuilder.addSubreport(subgraphValidation.buildReport());
      }
    }

    private void validateSubcomponentFactoryMethod(
        ExecutableElement factoryMethod, TypeElement subcomponentType) {
      BindingGraph subgraph = subject.subgraphs().get(factoryMethod);
      FluentIterable<TypeElement> missingModules =
          FluentIterable.from(subgraph.componentRequirements())
              .filter(not(in(subgraphFactoryMethodParameters(factoryMethod))))
              .filter(
                  new Predicate<TypeElement>() {
                    @Override
                    public boolean apply(TypeElement moduleType) {
                      return !componentCanMakeNewInstances(moduleType);
                    }
                  });
      if (!missingModules.isEmpty()) {
        reportBuilder.addError(
            String.format(
                "%s requires modules which have no visible default constructors. "
                    + "Add the following modules as parameters to this method: %s",
                subcomponentType.getQualifiedName(),
                Joiner.on(", ").join(missingModules.toSet())),
            factoryMethod);
      }
    }

    private ImmutableSet<TypeElement> subgraphFactoryMethodParameters(
        ExecutableElement factoryMethod) {
      DeclaredType componentType =
          asDeclared(subject.componentDescriptor().componentDefinitionType().asType());
      ExecutableType factoryMethodType =
          asExecutable(types.asMemberOf(componentType, factoryMethod));
      return asTypeElements(factoryMethodType.getParameterTypes());
    }

    /**
     * Traverse the resolved dependency requests, validating resolved bindings, and reporting any
     * cycles found.
     *
     * @param request the current dependency request
     * @param bindingPath the dependency request path from the parent of {@code request} at the head
     *     up to the root dependency request from the component method at the tail
     * @param keysInPath the binding keys corresponding to the dependency requests in
     *     {@code bindingPath}, but in reverse order: the first element is the binding key from the
     *     component method
     * @param resolvedRequests the requests that have already been resolved, so we can avoid
     *     traversing that part of the graph again
     */
    // TODO(dpb): It might be simpler to invert bindingPath's order.
    private void traverseRequest(
        DependencyRequest request,
        Deque<ResolvedRequest> bindingPath,
        LinkedHashSet<BindingKey> keysInPath,
        BindingGraph graph,
        Set<DependencyRequest> resolvedRequests) {
      verify(bindingPath.size() == keysInPath.size(),
          "mismatched path vs keys -- (%s vs %s)", bindingPath, keysInPath);
      BindingKey requestKey = request.bindingKey();
      if (keysInPath.contains(requestKey)) {
        reportCycle(
            // Invert bindingPath to match keysInPath's order
            ImmutableList.copyOf(bindingPath).reverse(),
            request,
            indexOf(keysInPath, equalTo(requestKey)));
        return;
      }

      // If request has already been resolved, avoid re-traversing the binding path.
      if (resolvedRequests.add(request)) {
        ResolvedRequest resolvedRequest = ResolvedRequest.create(request, graph);
        bindingPath.push(resolvedRequest);
        keysInPath.add(requestKey);
        validateResolvedBinding(bindingPath, resolvedRequest.binding());

        for (Binding binding : resolvedRequest.binding().bindings()) {
          for (DependencyRequest nextRequest : binding.implicitDependencies()) {
            traverseRequest(nextRequest, bindingPath, keysInPath, graph, resolvedRequests);
          }
        }
        bindingPath.poll();
        keysInPath.remove(requestKey);
      }
    }

    /**
     * Validates that the set of bindings resolved is consistent with the type of the binding, and
     * returns true if the bindings are valid.
     */
    private boolean validateResolvedBinding(
        Deque<ResolvedRequest> path, ResolvedBindings resolvedBinding) {
      if (resolvedBinding.isEmpty()) {
        reportMissingBinding(path);
        return false;
      }

      switch (resolvedBinding.bindingKey().kind()) {
        case CONTRIBUTION:
          if (Iterables.any(
              resolvedBinding.bindings(), BindingType.isOfType(BindingType.MEMBERS_INJECTION))) {
            // TODO(dpb): How could this ever happen, even in an invalid graph?
            throw new AssertionError(
                "contribution binding keys should never have members injection bindings");
          }
          validateNullability(path.peek().request(), resolvedBinding.contributionBindings());
          if (resolvedBinding.contributionBindings().size() > 1) {
            reportDuplicateBindings(path);
            return false;
          }
          ContributionBinding contributionBinding = resolvedBinding.contributionBinding();
          if (contributionBinding.bindingType().equals(BindingType.PRODUCTION)
              && doesPathRequireProvisionOnly(path)) {
            reportProviderMayNotDependOnProducer(path);
            return false;
          }
          if (compilerOptions.usesProducers()) {
            Key productionImplementationExecutorKey =
                keyFactory.forProductionImplementationExecutor();
            // only forbid depending on the production executor if it's not the Dagger-specific
            // binding to the implementation
            if (!contributionBinding.key().equals(productionImplementationExecutorKey)) {
              Key productionExecutorKey = keyFactory.forProductionExecutor();
              for (DependencyRequest request : contributionBinding.dependencies()) {
                if (request.key().equals(productionExecutorKey)
                    || request.key().equals(productionImplementationExecutorKey)) {
                  reportDependsOnProductionExecutor(path);
                  return false;
                }
              }
            }
          }
          if (contributionBinding.bindingKind().equals(SYNTHETIC_MULTIBOUND_MAP)) {
            ImmutableSet<ContributionBinding> multibindings =
                inlineSyntheticContributions(resolvedBinding).contributionBindings();
            boolean duplicateMapKeys = reportIfDuplicateMapKeys(path, multibindings);
            boolean inconsistentMapKeyAnnotationTypes =
                reportIfInconsistentMapKeyAnnotationTypes(path, multibindings);
            return !duplicateMapKeys && !inconsistentMapKeyAnnotationTypes;
          }
          break;
        case MEMBERS_INJECTION:
          if (!Iterables.all(
              resolvedBinding.bindings(), BindingType.isOfType(BindingType.MEMBERS_INJECTION))) {
            // TODO(dpb): How could this ever happen, even in an invalid graph?
            throw new AssertionError(
                "members injection binding keys should never have contribution bindings");
          }
          if (resolvedBinding.bindings().size() > 1) {
            reportDuplicateBindings(path);
            return false;
          }
          return validateMembersInjectionBinding(getOnlyElement(resolvedBinding.bindings()), path);
        default:
          throw new AssertionError();
      }
      return true;
    }

    /**
     * Returns an object that contains all the same bindings as {@code resolvedBindings}, except
     * that any synthetic {@link ContributionBinding}s are replaced by the contribution bindings and
     * multibinding declarations of their dependencies.
     *
     * <p>For example, if:
     *
     * <ul>
     * <li>The bindings for {@code key1} are {@code A} and {@code B}, with multibinding declaration
     *     {@code X}.
     * <li>{@code B} is a synthetic binding with a dependency on {@code key2}.
     * <li>The bindings for {@code key2} are {@code C} and {@code D}, with multibinding declaration
     *     {@code Y}.
     * </ul>
     *
     * then {@code inlineSyntheticBindings(bindingsForKey1)} has bindings {@code A}, {@code C}, and
     * {@code D}, with multibinding declarations {@code X} and {@code Y}.
     *
     * <p>The replacement is repeated until none of the bindings are synthetic.
     */
    private ResolvedBindings inlineSyntheticContributions(ResolvedBindings resolvedBinding) {
      if (!FluentIterable.from(resolvedBinding.contributionBindings())
          .transform(ContributionBinding.KIND)
          .anyMatch(IS_SYNTHETIC_KIND)) {
        return resolvedBinding;
      }

      ImmutableSetMultimap.Builder<ComponentDescriptor, ContributionBinding> contributions =
          ImmutableSetMultimap.builder();
      ImmutableSet.Builder<MultibindingDeclaration> multibindingDeclarations =
          ImmutableSet.builder();

      Queue<ResolvedBindings> queue = new ArrayDeque<>();
      queue.add(resolvedBinding);

      for (ResolvedBindings queued = queue.poll(); queued != null; queued = queue.poll()) {
        multibindingDeclarations.addAll(queued.multibindingDeclarations());
        for (Map.Entry<ComponentDescriptor, ContributionBinding> bindingEntry :
            queued.allContributionBindings().entries()) {
          ContributionBinding binding = bindingEntry.getValue();
          if (binding.isSyntheticBinding()) {
            for (DependencyRequest dependency : binding.dependencies()) {
              queue.add(subject.resolvedBindings().get(dependency.bindingKey()));
            }
          } else {
            contributions.put(bindingEntry);
          }
        }
      }
      return ResolvedBindings.forContributionBindings(
          resolvedBinding.bindingKey(),
          resolvedBinding.owningComponent(),
          contributions.build(),
          multibindingDeclarations.build());
    }

    private ImmutableListMultimap<ContributionType, HasSourceElement> declarationsByType(
        ResolvedBindings resolvedBinding) {
      ResolvedBindings inlined = inlineSyntheticContributions(resolvedBinding);
      return new ImmutableListMultimap.Builder<ContributionType, HasSourceElement>()
          .putAll(indexByContributionType(inlined.contributionBindings()))
          .putAll(indexByContributionType(inlined.multibindingDeclarations()))
          .build();
    }

    /** Ensures that if the request isn't nullable, then each contribution is also not nullable. */
    private void validateNullability(DependencyRequest request, Set<ContributionBinding> bindings) {
      if (request.isNullable()) {
        return;
      }

      // Note: the method signature will include the @Nullable in it!
      /* TODO(sameb): Sometimes javac doesn't include the Element in its output.
       * (Maybe this happens if the code was already compiled before this point?)
       * ... we manually print out the request in that case, otherwise the error
       * message is kind of useless. */
      String typeName = TypeName.get(request.key().type()).toString();

      for (ContributionBinding binding : bindings) {
        if (binding.nullableType().isPresent()) {
          reportBuilder.addItem(
              nullableToNonNullable(typeName, hasSourceElementFormatter.format(binding))
                  + "\n at: "
                  + dependencyRequestFormatter.format(request),
              compilerOptions.nullableValidationKind(),
              request.requestElement());
        }
      }
    }

    /**
     * Returns {@code true} (and reports errors) if {@code mapBindings} has more than one binding
     * for the same map key.
     */
    private boolean reportIfDuplicateMapKeys(
        Deque<ResolvedRequest> path, Set<ContributionBinding> mapBindings) {
      boolean hasDuplicateMapKeys = false;
      for (Collection<ContributionBinding> mapBindingsForMapKey :
          indexMapBindingsByMapKey(mapBindings).asMap().values()) {
        if (mapBindingsForMapKey.size() > 1) {
          hasDuplicateMapKeys = true;
          reportDuplicateMapKeys(path, mapBindingsForMapKey);
        }
      }
      return hasDuplicateMapKeys;
    }

    /**
     * Returns {@code true} (and reports errors) if {@code mapBindings} uses more than one
     * {@link MapKey} annotation type.
     */
    private boolean reportIfInconsistentMapKeyAnnotationTypes(
        Deque<ResolvedRequest> path, Set<ContributionBinding> contributionBindings) {
      ImmutableSetMultimap<Equivalence.Wrapper<DeclaredType>, ContributionBinding>
          mapBindingsByAnnotationType = indexMapBindingsByAnnotationType(contributionBindings);
      if (mapBindingsByAnnotationType.keySet().size() > 1) {
        reportInconsistentMapKeyAnnotations(path, mapBindingsByAnnotationType);
        return true;
      }
      return false;
    }

    /**
     * Validates a members injection binding, returning false (and reporting the error) if it wasn't
     * valid.
     */
    private boolean validateMembersInjectionBinding(
        Binding binding, final Deque<ResolvedRequest> path) {
      return binding
          .key()
          .type()
          .accept(
              new SimpleTypeVisitor6<Boolean, Void>() {
                @Override
                protected Boolean defaultAction(TypeMirror e, Void p) {
                  reportBuilder.addError(
                      "Invalid members injection request.", path.peek().request().requestElement());
                  return false;
                }

                @Override
                public Boolean visitDeclared(DeclaredType type, Void ignored) {
                  // If the key has type arguments, validate that each type argument is declared.
                  // Otherwise the type argument may be a wildcard (or other type), and we can't
                  // resolve that to actual types.  If the arg was an array, validate the type
                  // of the array.
                  for (TypeMirror arg : type.getTypeArguments()) {
                    boolean declared;
                    switch (arg.getKind()) {
                      case ARRAY:
                        declared =
                            MoreTypes.asArray(arg)
                                .getComponentType()
                                .accept(
                                    new SimpleTypeVisitor6<Boolean, Void>() {
                                      @Override
                                      protected Boolean defaultAction(TypeMirror e, Void p) {
                                        return false;
                                      }

                                      @Override
                                      public Boolean visitDeclared(DeclaredType t, Void p) {
                                        for (TypeMirror arg : t.getTypeArguments()) {
                                          if (!arg.accept(this, null)) {
                                            return false;
                                          }
                                        }
                                        return true;
                                      }

                                      @Override
                                      public Boolean visitArray(ArrayType t, Void p) {
                                        return t.getComponentType().accept(this, null);
                                      }

                                      @Override
                                      public Boolean visitPrimitive(PrimitiveType t, Void p) {
                                        return true;
                                      }
                                    },
                                    null);
                        break;
                      case DECLARED:
                        declared = true;
                        break;
                      default:
                        declared = false;
                    }
                    if (!declared) {
                      ImmutableList<String> printableDependencyPath =
                          FluentIterable.from(path)
                              .transform(REQUEST_FROM_RESOLVED_REQUEST)
                              .transform(dependencyRequestFormatter)
                              .filter(Predicates.not(Predicates.equalTo("")))
                              .toList()
                              .reverse();
                      reportBuilder.addError(
                          String.format(
                              MEMBERS_INJECTION_WITH_UNBOUNDED_TYPE,
                              arg.toString(),
                              type.toString(),
                              Joiner.on('\n').join(printableDependencyPath)),
                          path.peek().request().requestElement());
                      return false;
                    }
                  }

                  TypeElement element = MoreElements.asType(type.asElement());
                  // Also validate that the key is not the erasure of a generic type.
                  // If it is, that means the user referred to Foo<T> as just 'Foo',
                  // which we don't allow.  (This is a judgement call -- we *could*
                  // allow it and instantiate the type bounds... but we don't.)
                  if (!MoreTypes.asDeclared(element.asType()).getTypeArguments().isEmpty()
                      && types.isSameType(types.erasure(element.asType()), type)) {
                    ImmutableList<String> printableDependencyPath =
                        FluentIterable.from(path)
                            .transform(REQUEST_FROM_RESOLVED_REQUEST)
                            .transform(dependencyRequestFormatter)
                            .filter(Predicates.not(Predicates.equalTo("")))
                            .toList()
                            .reverse();
                    reportBuilder.addError(
                        String.format(
                            ErrorMessages.MEMBERS_INJECTION_WITH_RAW_TYPE,
                            type.toString(),
                            Joiner.on('\n').join(printableDependencyPath)),
                        path.peek().request().requestElement());
                    return false;
                  }

                  return true; // valid
                }
              },
              null);
    }

    /**
     * Validates that component dependencies do not form a cycle.
     */
    private void validateComponentHierarchy() {
      ComponentDescriptor descriptor = subject.componentDescriptor();
      TypeElement componentType = descriptor.componentDefinitionType();
      validateComponentHierarchy(componentType, componentType, new ArrayDeque<TypeElement>());
    }

    /**
     * Recursive method to validate that component dependencies do not form a cycle.
     */
    private void validateComponentHierarchy(
        TypeElement rootComponent,
        TypeElement componentType,
        Deque<TypeElement> componentStack) {

      if (componentStack.contains(componentType)) {
        // Current component has already appeared in the component chain.
        StringBuilder message = new StringBuilder();
        message.append(rootComponent.getQualifiedName());
        message.append(" contains a cycle in its component dependencies:\n");
        componentStack.push(componentType);
        appendIndentedComponentsList(message, componentStack);
        componentStack.pop();
        reportBuilder.addItem(
            message.toString(),
            compilerOptions.scopeCycleValidationType().diagnosticKind().get(),
            rootComponent,
            getAnnotationMirror(rootComponent, Component.class).get());
      } else {
        Optional<AnnotationMirror> componentAnnotation =
            getAnnotationMirror(componentType, Component.class);
        if (componentAnnotation.isPresent()) {
          componentStack.push(componentType);

          ImmutableSet<TypeElement> dependencies =
              MoreTypes.asTypeElements(getComponentDependencies(componentAnnotation.get()));
          for (TypeElement dependency : dependencies) {
            validateComponentHierarchy(rootComponent, dependency, componentStack);
          }

          componentStack.pop();
        }
      }
    }

    /**
     * Validates that among the dependencies are at most one scoped dependency,
     * that there are no cycles within the scoping chain, and that singleton
     * components have no scoped dependencies.
     */
    private void validateDependencyScopes() {
      ComponentDescriptor descriptor = subject.componentDescriptor();
      ImmutableSet<Scope> scopes = descriptor.scopes();
      ImmutableSet<TypeElement> scopedDependencies = scopedTypesIn(descriptor.dependencies());
      if (!scopes.isEmpty()) {
        Scope singletonScope = Scope.singletonScope(elements);
        // Dagger 1.x scope compatibility requires this be suppress-able.
        if (compilerOptions.scopeCycleValidationType().diagnosticKind().isPresent()
            && scopes.contains(singletonScope)) {
          // Singleton is a special-case representing the longest lifetime, and therefore
          // @Singleton components may not depend on scoped components
          if (!scopedDependencies.isEmpty()) {
            StringBuilder message = new StringBuilder(
                "This @Singleton component cannot depend on scoped components:\n");
            appendIndentedComponentsList(message, scopedDependencies);
            reportBuilder.addItem(
                message.toString(),
                compilerOptions.scopeCycleValidationType().diagnosticKind().get(),
                descriptor.componentDefinitionType(),
                descriptor.componentAnnotation());
          }
        } else if (scopedDependencies.size() > 1) {
          // Scoped components may depend on at most one scoped component.
          StringBuilder message = new StringBuilder();
          for (Scope scope : scopes) {
            message.append(scope.getReadableSource()).append(' ');
          }
          message
              .append(descriptor.componentDefinitionType().getQualifiedName())
              .append(" depends on more than one scoped component:\n");
          appendIndentedComponentsList(message, scopedDependencies);
          reportBuilder.addError(
              message.toString(),
              descriptor.componentDefinitionType(),
              descriptor.componentAnnotation());
        } else {
          // Dagger 1.x scope compatibility requires this be suppress-able.
          if (!compilerOptions.scopeCycleValidationType().equals(ValidationType.NONE)) {
            validateScopeHierarchy(descriptor.componentDefinitionType(),
                descriptor.componentDefinitionType(),
                new ArrayDeque<ImmutableSet<Scope>>(),
                new ArrayDeque<TypeElement>());
          }
        }
      } else {
        // Scopeless components may not depend on scoped components.
        if (!scopedDependencies.isEmpty()) {
          StringBuilder message =
              new StringBuilder(descriptor.componentDefinitionType().getQualifiedName())
                  .append(" (unscoped) cannot depend on scoped components:\n");
          appendIndentedComponentsList(message, scopedDependencies);
          reportBuilder.addError(
              message.toString(),
              descriptor.componentDefinitionType(),
              descriptor.componentAnnotation());
        }
      }
    }

    private void validateBuilders() {
      ComponentDescriptor componentDesc = subject.componentDescriptor();
      if (!componentDesc.builderSpec().isPresent()) {
        // If no builder, nothing to validate.
        return;
      }

      Set<TypeElement> availableDependencies = subject.availableDependencies();
      Set<TypeElement> requiredDependencies =
          Sets.filter(
              availableDependencies,
              new Predicate<TypeElement>() {
                @Override
                public boolean apply(TypeElement input) {
                  return !Util.componentCanMakeNewInstances(input);
                }
              });
      final BuilderSpec spec = componentDesc.builderSpec().get();
      Map<TypeElement, ExecutableElement> allSetters = spec.methodMap();

      ErrorMessages.ComponentBuilderMessages msgs =
          ErrorMessages.builderMsgsFor(subject.componentDescriptor().kind());
      Set<TypeElement> extraSetters = Sets.difference(allSetters.keySet(), availableDependencies);
      if (!extraSetters.isEmpty()) {
        Collection<ExecutableElement> excessMethods =
            Maps.filterKeys(allSetters, Predicates.in(extraSetters)).values();
        Iterable<String> formatted = FluentIterable.from(excessMethods).transform(
            new Function<ExecutableElement, String>() {
              @Override public String apply(ExecutableElement input) {
                return methodSignatureFormatter.format(input,
                    Optional.of(MoreTypes.asDeclared(spec.builderDefinitionType().asType())));
              }});
        reportBuilder.addError(
            String.format(msgs.extraSetters(), formatted), spec.builderDefinitionType());
      }

      Set<TypeElement> missingSetters = Sets.difference(requiredDependencies, allSetters.keySet());
      if (!missingSetters.isEmpty()) {
        reportBuilder.addError(
            String.format(msgs.missingSetters(), missingSetters), spec.builderDefinitionType());
      }
    }

    /**
     * Validates that scopes do not participate in a scoping cycle - that is to say, scoped
     * components are in a hierarchical relationship terminating with Singleton.
     *
     * <p>As a side-effect, this means scoped components cannot have a dependency cycle between
     * themselves, since a component's presence within its own dependency path implies a cyclical
     * relationship between scopes. However, cycles in component dependencies are explicitly
     * checked in {@link #validateComponentHierarchy()}.
     */
    private void validateScopeHierarchy(TypeElement rootComponent,
        TypeElement componentType,
        Deque<ImmutableSet<Scope>> scopeStack,
        Deque<TypeElement> scopedDependencyStack) {
      ImmutableSet<Scope> scopes = Scope.scopesOf(componentType);
      if (stackOverlaps(scopeStack, scopes)) {
        scopedDependencyStack.push(componentType);
        // Current scope has already appeared in the component chain.
        StringBuilder message = new StringBuilder();
        message.append(rootComponent.getQualifiedName());
        message.append(" depends on scoped components in a non-hierarchical scope ordering:\n");
        appendIndentedComponentsList(message, scopedDependencyStack);
        if (compilerOptions.scopeCycleValidationType().diagnosticKind().isPresent()) {
          reportBuilder.addItem(
              message.toString(),
              compilerOptions.scopeCycleValidationType().diagnosticKind().get(),
              rootComponent,
              getAnnotationMirror(rootComponent, Component.class)
                  .or(getAnnotationMirror(rootComponent, ProductionComponent.class))
                  .get());
        }
        scopedDependencyStack.pop();
      } else {
        // TODO(beder): transitively check scopes of production components too.
        Optional<AnnotationMirror> componentAnnotation =
            getAnnotationMirror(componentType, Component.class);
        if (componentAnnotation.isPresent()) {
          ImmutableSet<TypeElement> scopedDependencies = scopedTypesIn(
              MoreTypes.asTypeElements(getComponentDependencies(componentAnnotation.get())));
          if (scopedDependencies.size() == 1) {
            // empty can be ignored (base-case), and > 1 is a different error reported separately.
            scopeStack.push(scopes);
            scopedDependencyStack.push(componentType);
            validateScopeHierarchy(rootComponent, getOnlyElement(scopedDependencies),
                scopeStack, scopedDependencyStack);
            scopedDependencyStack.pop();
            scopeStack.pop();
          }
        } // else: we skip component dependencies which are not components
      }
    }

    private <T> boolean stackOverlaps(Deque<ImmutableSet<T>> stack, ImmutableSet<T> set) {
      for (ImmutableSet<T> entry : stack) {
        if (!Sets.intersection(entry, set).isEmpty()) {
          return true;
        }
      }
      return false;
    }

    /**
     * Validates that the scope (if any) of this component are compatible with the scopes of the
     * bindings available in this component
     */
    void validateComponentScope() {
      ImmutableMap<BindingKey, ResolvedBindings> resolvedBindings = subject.resolvedBindings();
      ImmutableSet<Scope> componentScopes = subject.componentDescriptor().scopes();
      ImmutableSet.Builder<String> incompatiblyScopedMethodsBuilder = ImmutableSet.builder();
      for (ResolvedBindings bindings : resolvedBindings.values()) {
        for (ContributionBinding contributionBinding : bindings.ownedContributionBindings()) {
          Optional<Scope> bindingScope = contributionBinding.scope();
          if (bindingScope.isPresent() && !componentScopes.contains(bindingScope.get())) {
            // Scoped components cannot reference bindings to @Provides methods or @Inject
            // types decorated by a different scope annotation. Unscoped components cannot
            // reference to scoped @Provides methods or @Inject types decorated by any
            // scope annotation.
            switch (contributionBinding.bindingKind()) {
              case PROVISION:
                ExecutableElement provisionMethod =
                    MoreElements.asExecutable(contributionBinding.bindingElement());
                incompatiblyScopedMethodsBuilder.add(
                    methodSignatureFormatter.format(provisionMethod));
                break;
              case INJECTION:
                incompatiblyScopedMethodsBuilder.add(
                    bindingScope.get().getReadableSource()
                        + " class "
                        + contributionBinding.bindingTypeElement().getQualifiedName());
                break;
              default:
                throw new IllegalStateException();
            }
          }
        }
      }

      ImmutableSet<String> incompatiblyScopedMethods = incompatiblyScopedMethodsBuilder.build();
      if (!incompatiblyScopedMethods.isEmpty()) {
        TypeElement componentType = subject.componentDescriptor().componentDefinitionType();
        StringBuilder message = new StringBuilder(componentType.getQualifiedName());
        if (!componentScopes.isEmpty()) {
          message.append(" scoped with ");
          for (Scope scope : componentScopes) {
            message.append(scope.getReadableSource()).append(' ');
          }
          message.append("may not reference bindings with different scopes:\n");
        } else {
          message.append(" (unscoped) may not reference scoped bindings:\n");
        }
        for (String method : incompatiblyScopedMethods) {
          message.append(ErrorMessages.INDENT).append(method).append("\n");
        }
        reportBuilder.addError(
            message.toString(), componentType, subject.componentDescriptor().componentAnnotation());
      }
    }

    @SuppressWarnings("resource") // Appendable is a StringBuilder.
    private void reportProviderMayNotDependOnProducer(Deque<ResolvedRequest> path) {
      StringBuilder errorMessage = new StringBuilder();
      if (path.size() == 1) {
        new Formatter(errorMessage)
            .format(
                ErrorMessages.PROVIDER_ENTRY_POINT_MAY_NOT_DEPEND_ON_PRODUCER_FORMAT,
                formatRootRequestKey(path));
      } else {
        ImmutableSet<? extends Binding> dependentProvisions =
            provisionsDependingOnLatestRequest(path);
        // TODO(beder): Consider displaying all dependent provisions in the error message. If we do
        // that, should we display all productions that depend on them also?
        new Formatter(errorMessage).format(ErrorMessages.PROVIDER_MAY_NOT_DEPEND_ON_PRODUCER_FORMAT,
            keyFormatter.format(dependentProvisions.iterator().next().key()));
      }
      reportBuilder.addError(errorMessage.toString(), path.getLast().request().requestElement());
    }

    private void reportMissingBinding(Deque<ResolvedRequest> path) {
      Key key = path.peek().request().key();
      BindingKey bindingKey = path.peek().request().bindingKey();
      boolean requiresContributionMethod = !key.isValidImplicitProvisionKey(types);
      boolean requiresProvision = doesPathRequireProvisionOnly(path);
      StringBuilder errorMessage = new StringBuilder();
      String requiresErrorMessageFormat = requiresContributionMethod
          ? requiresProvision
              ? REQUIRES_PROVIDER_FORMAT
              : REQUIRES_PROVIDER_OR_PRODUCER_FORMAT
          : requiresProvision
              ? REQUIRES_AT_INJECT_CONSTRUCTOR_OR_PROVIDER_FORMAT
              : REQUIRES_AT_INJECT_CONSTRUCTOR_OR_PROVIDER_OR_PRODUCER_FORMAT;
      errorMessage.append(String.format(requiresErrorMessageFormat, keyFormatter.format(key)));
      if (key.isValidMembersInjectionKey()) {
        Optional<MembersInjectionBinding> membersInjectionBinding =
            injectBindingRegistry.getOrFindMembersInjectionBinding(key);
        if (membersInjectionBinding.isPresent()
            && !membersInjectionBinding.get().injectionSites().isEmpty()) {
          errorMessage.append(" ").append(ErrorMessages.MEMBERS_INJECTION_DOES_NOT_IMPLY_PROVISION);
        }
      }
      ImmutableList<String> printableDependencyPath =
          FluentIterable.from(path)
              .transform(REQUEST_FROM_RESOLVED_REQUEST)
              .transform(dependencyRequestFormatter)
              .filter(Predicates.not(Predicates.equalTo("")))
              .toList()
              .reverse();
      for (String dependency :
          printableDependencyPath.subList(1, printableDependencyPath.size())) {
        errorMessage.append('\n').append(dependency);
      }
      for (String suggestion : MissingBindingSuggestions.forKey(topLevelGraph(), bindingKey)) {
        errorMessage.append('\n').append(suggestion);
      }
      reportBuilder.addError(errorMessage.toString(), path.getLast().request().requestElement());
    }

    @SuppressWarnings("resource") // Appendable is a StringBuilder.
    private void reportDependsOnProductionExecutor(Deque<ResolvedRequest> path) {
      StringBuilder builder = new StringBuilder();
      new Formatter(builder)
          .format(ErrorMessages.DEPENDS_ON_PRODUCTION_EXECUTOR_FORMAT, formatRootRequestKey(path));
      reportBuilder.addError(builder.toString(), path.getLast().request().requestElement());
    }

    @SuppressWarnings("resource") // Appendable is a StringBuilder.
    private void reportDuplicateBindings(Deque<ResolvedRequest> path) {
      ResolvedBindings resolvedBinding = path.peek().binding();
      if (FluentIterable.from(resolvedBinding.contributionBindings())
          .transform(ContributionBinding.KIND)
          .anyMatch(IS_SYNTHETIC_KIND)) {
        reportMultipleBindingTypes(path);
        return;
      }
      StringBuilder builder = new StringBuilder();
      new Formatter(builder)
          .format(ErrorMessages.DUPLICATE_BINDINGS_FOR_KEY_FORMAT, formatRootRequestKey(path));
      ImmutableSet<ContributionBinding> duplicateBindings =
          inlineSyntheticContributions(resolvedBinding).contributionBindings();
      hasSourceElementFormatter.formatIndentedList(
          builder, duplicateBindings, 1, DUPLICATE_SIZE_LIMIT);
      owningReportBuilder(duplicateBindings)
          .addError(builder.toString(), path.getLast().request().requestElement());
    }

    /**
     * Returns the report builder for the rootmost component that contains any of the duplicate
     * bindings.
     */
    private ValidationReport.Builder<TypeElement> owningReportBuilder(
        Iterable<ContributionBinding> duplicateBindings) {
      ImmutableSet.Builder<ComponentDescriptor> owningComponentsBuilder = ImmutableSet.builder();
      for (ContributionBinding binding : duplicateBindings) {
        BindingKey bindingKey = BindingKey.create(BindingKey.Kind.CONTRIBUTION, binding.key());
        ResolvedBindings resolvedBindings = subject.resolvedBindings().get(bindingKey);
        owningComponentsBuilder.addAll(
            resolvedBindings.allContributionBindings().inverse().get(binding));
      }
      ImmutableSet<ComponentDescriptor> owningComponents = owningComponentsBuilder.build();
      for (Validation validation : validationPath()) {
        if (owningComponents.contains(validation.subject.componentDescriptor())) {
          return validation.reportBuilder;
        }
      }
      throw new AssertionError(
          "cannot find owning component for duplicate bindings: " + duplicateBindings);
    }

    /**
     * The path from the {@link Validation} of the root graph down to this {@link Validation}.
     */
    private ImmutableList<Validation> validationPath() {
      ImmutableList.Builder<Validation> validationPath = ImmutableList.builder();
      for (Optional<Validation> validation = Optional.of(this);
          validation.isPresent();
          validation = validation.get().parent) {
        validationPath.add(validation.get());
      }
      return validationPath.build().reverse();
    }

    @SuppressWarnings("resource") // Appendable is a StringBuilder.
    private void reportMultipleBindingTypes(Deque<ResolvedRequest> path) {
      StringBuilder builder = new StringBuilder();
      new Formatter(builder)
          .format(ErrorMessages.MULTIPLE_BINDING_TYPES_FOR_KEY_FORMAT, formatRootRequestKey(path));
      ResolvedBindings resolvedBinding = path.peek().binding();
      ImmutableListMultimap<ContributionType, HasSourceElement> declarationsByType =
          declarationsByType(resolvedBinding);
      verify(
          declarationsByType.keySet().size() > 1,
          "expected multiple binding types for %s: %s",
          resolvedBinding.bindingKey(),
          declarationsByType);
      for (ContributionType type :
          Ordering.natural().immutableSortedCopy(declarationsByType.keySet())) {
        builder.append(INDENT);
        builder.append(formatContributionType(type));
        builder.append(" bindings and declarations:");
        hasSourceElementFormatter.formatIndentedList(
            builder, declarationsByType.get(type), 2, DUPLICATE_SIZE_LIMIT);
        builder.append('\n');
      }
      reportBuilder.addError(builder.toString(), path.getLast().request().requestElement());
    }

    private void reportDuplicateMapKeys(
        Deque<ResolvedRequest> path, Collection<ContributionBinding> mapBindings) {
      StringBuilder builder = new StringBuilder();
      builder.append(duplicateMapKeysError(formatRootRequestKey(path)));
      hasSourceElementFormatter.formatIndentedList(builder, mapBindings, 1, DUPLICATE_SIZE_LIMIT);
      reportBuilder.addError(builder.toString(), path.getLast().request().requestElement());
    }

    private void reportInconsistentMapKeyAnnotations(
        Deque<ResolvedRequest> path,
        Multimap<Equivalence.Wrapper<DeclaredType>, ContributionBinding>
            mapBindingsByAnnotationType) {
      StringBuilder builder =
          new StringBuilder(inconsistentMapKeyAnnotationsError(formatRootRequestKey(path)));
      for (Map.Entry<Equivalence.Wrapper<DeclaredType>, Collection<ContributionBinding>> entry :
          mapBindingsByAnnotationType.asMap().entrySet()) {
        DeclaredType annotationType = entry.getKey().get();
        Collection<ContributionBinding> bindings = entry.getValue();

        builder
            .append('\n')
            .append(INDENT)
            .append(annotationType)
            .append(':');

        hasSourceElementFormatter.formatIndentedList(builder, bindings, 2, DUPLICATE_SIZE_LIMIT);
      }
      reportBuilder.addError(builder.toString(), path.getLast().request().requestElement());
    }

    /**
     * Reports a cycle in the binding path.
     *
     * @param bindingPath the binding path, starting with the component provision dependency, and
     *     ending with the binding that depends on {@code request}
     * @param request the request that would have been added to the binding path if its
     *     {@linkplain DependencyRequest#bindingKey() binding key} wasn't already in it
     * @param indexOfDuplicatedKey the index of the dependency request in {@code bindingPath} whose
     *     {@linkplain DependencyRequest#bindingKey() binding key} matches {@code request}'s
     */
    private void reportCycle(
        Iterable<ResolvedRequest> bindingPath,
        DependencyRequest request,
        int indexOfDuplicatedKey) {
      ImmutableList<DependencyRequest> requestPath =
          FluentIterable.from(bindingPath)
              .transform(REQUEST_FROM_RESOLVED_REQUEST)
              .append(request)
              .toList();
      Element rootRequestElement = requestPath.get(0).requestElement();
      ImmutableList<DependencyRequest> cycle =
          requestPath.subList(indexOfDuplicatedKey, requestPath.size());
      if (!providersBreakingCycle(cycle).isEmpty()) {
        return;
      }
      // TODO(cgruber): Provide a hint for the start and end of the cycle.
      TypeElement componentType = MoreElements.asType(rootRequestElement.getEnclosingElement());
      reportBuilder.addItem(
          String.format(
              CONTAINS_DEPENDENCY_CYCLE_FORMAT,
              componentType.getQualifiedName(),
              rootRequestElement.getSimpleName(),
              Joiner.on("\n")
                  .join(
                      FluentIterable.from(requestPath)
                          .transform(dependencyRequestFormatter)
                          .filter(not(equalTo("")))
                          .skip(1))),
          ERROR,
          rootRequestElement);
    }

    /**
     * Returns any steps in a dependency cycle that "break" the cycle. These are any
     * {@link Provider}, {@link Lazy}, or {@code Map<K, Provider<V>>} requests after the first
     * request in the cycle.
     *
     * <p>If an implicit {@link Provider} dependency on {@code Map<K, Provider<V>>} is immediately
     * preceded by a dependency on {@code Map<K, V>}, which means that the map's {@link Provider}s'
     * {@link Provider#get() get()} methods are called during provision and so the cycle is not
     * really broken.
     */
    private ImmutableSet<DependencyRequest> providersBreakingCycle(
        ImmutableList<DependencyRequest> cycle) {
      ImmutableSet.Builder<DependencyRequest> providers = ImmutableSet.builder();
      for (int i = 1; i < cycle.size(); i++) {
        DependencyRequest dependencyRequest = cycle.get(i);
        switch (dependencyRequest.kind()) {
          case PROVIDER:
            if (isImplicitProviderMapForValueMap(dependencyRequest, cycle.get(i - 1))) {
              i++; // Skip the Provider requests in the Map<K, Provider<V>> too.
            } else {
              providers.add(dependencyRequest);
            }
            break;

          case LAZY:
            providers.add(dependencyRequest);
            break;

          case INSTANCE:
            TypeMirror type = dependencyRequest.key().type();
            if (MapType.isMap(type) && MapType.from(type).valuesAreTypeOf(Provider.class)) {
              providers.add(dependencyRequest);
            }
            break;

          default:
            break;
        }
      }
      return providers.build();
    }

    /**
     * Returns {@code true} if {@code maybeValueMapRequest}'s key type is {@code Map<K, V>} and
     * {@code maybeProviderMapRequest}'s key type is {@code Map<K, Provider<V>>}, and both keys have
     * the same qualifier.
     */
    private boolean isImplicitProviderMapForValueMap(
        DependencyRequest maybeProviderMapRequest, DependencyRequest maybeValueMapRequest) {
      Optional<Key> implicitProviderMapKey =
          keyFactory.implicitMapProviderKeyFrom(maybeValueMapRequest.key());
      return implicitProviderMapKey.isPresent()
          && implicitProviderMapKey.get().equals(maybeProviderMapRequest.key());
    }
  }

  ValidationReport<TypeElement> validate(BindingGraph subject) {
    Validation validation = new Validation(subject);
    validation.validateSubgraph();
    return validation.buildReport();
  }

  /**
   * Append and format a list of indented component types (with their scope annotations)
   */
  private void appendIndentedComponentsList(StringBuilder message, Iterable<TypeElement> types) {
    for (TypeElement scopedComponent : types) {
      message.append(INDENT);
      for (Scope scope : Scope.scopesOf(scopedComponent)) {
        message.append(scope.getReadableSource()).append(' ');
      }
      message.append(stripCommonTypePrefixes(scopedComponent.getQualifiedName().toString()))
          .append('\n');
    }
  }

  /**
   * Returns a set of type elements containing only those found in the input set that have
   * a scoping annotation.
   */
  private ImmutableSet<TypeElement> scopedTypesIn(Set<TypeElement> types) {
    return FluentIterable.from(types).filter(new Predicate<TypeElement>() {
      @Override public boolean apply(TypeElement input) {
        return !Scope.scopesOf(input).isEmpty();
      }
    }).toSet();
  }

  /**
   * Returns whether the given dependency path would require the most recent request to be resolved
   * by only provision bindings.
   */
  private boolean doesPathRequireProvisionOnly(Deque<ResolvedRequest> path) {
    if (path.size() == 1) {
      // if this is an entry-point, then we check the request
      switch (path.peek().request().kind()) {
        case INSTANCE:
        case PROVIDER:
        case LAZY:
        case MEMBERS_INJECTOR:
          return true;
        case PRODUCER:
        case PRODUCED:
        case FUTURE:
          return false;
        default:
          throw new AssertionError();
      }
    }
    // otherwise, the second-most-recent bindings determine whether the most recent one must be a
    // provision
    return !provisionsDependingOnLatestRequest(path).isEmpty();
  }

  /**
   * Returns any provision bindings resolved for the second-most-recent request in the given path;
   * that is, returns those provision bindings that depend on the latest request in the path.
   */
  private ImmutableSet<? extends Binding> provisionsDependingOnLatestRequest(
      Deque<ResolvedRequest> path) {
    Iterator<ResolvedRequest> iterator = path.iterator();
    final DependencyRequest request = iterator.next().request();
    ResolvedRequest previousResolvedRequest = iterator.next();
    return FluentIterable.from(previousResolvedRequest.binding().bindings())
        .filter(BindingType.isOfType(BindingType.PROVISION))
        .filter(
            new Predicate<Binding>() {
              @Override
              public boolean apply(Binding binding) {
                return binding.implicitDependencies().contains(request);
              }
            })
        .toSet();
  }

  private String formatContributionType(ContributionType type) {
    switch (type) {
      case MAP:
        return "Map";
      case SET:
        return "Set";
      case UNIQUE:
        return "Unique";
      default:
        throw new IllegalStateException("Unknown binding type: " + type);
    }
  }

  private String formatRootRequestKey(Deque<ResolvedRequest> path) {
    return keyFormatter.format(path.peek().request().key());
  }

  @AutoValue
  abstract static class ResolvedRequest {
    abstract DependencyRequest request();
    abstract ResolvedBindings binding();

    static ResolvedRequest create(DependencyRequest request, BindingGraph graph) {
      BindingKey bindingKey = request.bindingKey();
      ResolvedBindings resolvedBindings = graph.resolvedBindings().get(bindingKey);
      return new AutoValue_BindingGraphValidator_ResolvedRequest(
          request,
          resolvedBindings == null
              ? ResolvedBindings.noBindings(bindingKey, graph.componentDescriptor())
              : resolvedBindings);
    }
  }

  private static final Function<ResolvedRequest, DependencyRequest> REQUEST_FROM_RESOLVED_REQUEST =
      new Function<ResolvedRequest, DependencyRequest>() {
        @Override public DependencyRequest apply(ResolvedRequest resolvedRequest) {
          return resolvedRequest.request();
        }
      };
}
