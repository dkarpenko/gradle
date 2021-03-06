/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.model.internal.typeregistration;

import com.google.common.base.Joiner;
import com.google.common.collect.*;
import org.gradle.api.Nullable;
import org.gradle.internal.Cast;
import org.gradle.model.Managed;
import org.gradle.model.internal.core.MutableModelNode;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;
import org.gradle.model.internal.manage.schema.extract.ModelSchemaUtils;
import org.gradle.model.internal.type.ModelType;
import org.gradle.model.internal.type.ModelTypes;

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class BaseInstanceFactory<PUBLIC, BASEIMPL> implements InstanceFactory<PUBLIC> {
    private final String displayName;
    private final ModelType<PUBLIC> baseInterface;
    private final ModelType<BASEIMPL> baseImplementation;
    private final Map<ModelType<? extends PUBLIC>, TypeRegistration<? extends PUBLIC>> registrations = Maps.newLinkedHashMap();

    public BaseInstanceFactory(String displayName, Class<PUBLIC> baseInterface, Class<BASEIMPL> baseImplementation) {
        this.displayName = displayName;
        this.baseInterface = ModelType.of(baseInterface);
        this.baseImplementation = ModelType.of(baseImplementation);
    }

    @Override
    public ModelType<PUBLIC> getBaseInterface() {
        return baseInterface;
    }

    protected abstract <S extends PUBLIC> InstanceFactory.ImplementationFactory<S> forType(ModelType<S> publicType, ModelType<? extends BASEIMPL> implementation);

    public <S extends PUBLIC> void register(ModelType<S> publicType, Set<Class<?>> internalViews, @Nullable ModelType<? extends BASEIMPL> implementationType, ModelRuleDescriptor definedBy) {
        InstanceFactory.TypeRegistrationBuilder<S> registration = register(publicType, definedBy);
        if (implementationType != null) {
            registration.withImplementation(implementationType, forType(publicType, implementationType));
        }
        for (Class<?> internalView : internalViews) {
            registration.withInternalView(ModelType.of(internalView));
        }
    }

    @Override
    public <S extends PUBLIC> TypeRegistrationBuilder<S> register(ModelType<S> publicType, ModelRuleDescriptor source) {
        TypeRegistration<S> registration = Cast.uncheckedCast(registrations.get(publicType));
        if (registration == null) {
            registration = new TypeRegistration<S>(publicType);
            registrations.put(publicType, registration);
        }
        return new TypeRegistrationBuilderImpl<S>(source, registration);
    }

    @Override
    public <S extends PUBLIC> ImplementationInfo<PUBLIC> getImplementationInfo(final ModelType<S> publicType) {
        ImplementationRegistration<S> implementationRegistration = getImplementationRegistration(publicType);
        return new ImplementationInfoImpl<PUBLIC>(publicType, implementationRegistration);
    }

    @Override
    public <S extends PUBLIC> ImplementationInfo<PUBLIC> getManagedSubtypeImplementationInfo(final ModelType<S> publicType) {
        if (!isManaged(publicType)) {
            throw new IllegalArgumentException(String.format("Type '%s' is not managed", publicType));
        }
        final List<ImplementationInfo<PUBLIC>> implementationInfos = Lists.newArrayListWithCapacity(1);
        ModelSchemaUtils.walkTypeHierarchy(publicType.getConcreteClass(), new RegistrationHierarchyVisitor<S>() {
            @Override
            protected void visitRegistration(TypeRegistration<? extends PUBLIC> registration) {
                if (registration != null && registration.implementationRegistration != null) {
                    implementationInfos.add(new ImplementationInfoImpl<PUBLIC>(publicType, registration.implementationRegistration));
                }
            }
        });

        if (implementationInfos.isEmpty()) {
            throw new IllegalStateException(String.format("Factory registration for '%s' is invalid because it doesn't extend an interface with a default implementation", publicType));
        }

        return implementationInfos.get(0);
    }

    @Override
    public <S extends PUBLIC> Set<ModelType<?>> getInternalViews(ModelType<S> publicType) {
        final ImmutableSet.Builder<ModelType<?>> builder = ImmutableSet.builder();
        ModelSchemaUtils.walkTypeHierarchy(publicType.getConcreteClass(), new RegistrationHierarchyVisitor<S>() {
            @Override
            protected void visitRegistration(TypeRegistration<? extends PUBLIC> registration) {
                for (InternalViewRegistration<?> internalViewRegistration : registration.internalViewRegistrations) {
                    builder.add(internalViewRegistration.getInternalView());
                }
            }
        });
        return builder.build();
    }

    @Override
    public Set<ModelType<? extends PUBLIC>> getSupportedTypes() {
        ImmutableSortedSet.Builder<ModelType<? extends PUBLIC>> supportedTypes = ImmutableSortedSet.orderedBy(ModelTypes.<PUBLIC>displayOrder());
        for (TypeRegistration<?> registration : registrations.values()) {
            if (registration.isConstructible()) {
                supportedTypes.add(registration.publicType);
            }
        }
        return supportedTypes.build();
    }

    private String getConstructibleTypeNames() {
        Set<ModelType<? extends PUBLIC>> constructibleTypes = getConstructibleTypes();
        if (constructibleTypes.isEmpty()) {
            return "(None)";
        }
        return Joiner.on(", ").join(constructibleTypes);
    }

    private Set<ModelType<? extends PUBLIC>> getConstructibleTypes() {
        return Sets.difference(getSupportedTypes(), Collections.singleton(baseInterface));
    }

    private <S extends PUBLIC> TypeRegistration<S> getRegistration(ModelType<S> type) {
        return Cast.uncheckedCast(registrations.get(type));
    }

    private <S extends PUBLIC> ImplementationRegistration<S> getImplementationRegistration(ModelType<S> type) {
        TypeRegistration<S> registration = getRegistration(type);
        if (registration == null) {
            throw new IllegalArgumentException(
                String.format("Cannot create a '%s' because this type is not known to %s. Known types are: %s", type, displayName, getConstructibleTypeNames()));
        }
        if (registration.implementationRegistration == null) {
            throw new IllegalArgumentException(
                String.format("Cannot create a '%s' because this type does not have an implementation registered.", type));
        }
        return registration.implementationRegistration;
    }

    @Override
    public void validateRegistrations() {
        for (TypeRegistration<? extends PUBLIC> registration : registrations.values()) {
            registration.validate();
        }
    }

    @Override
    public String toString() {
        return "[" + getConstructibleTypeNames() + "]";
    }

    private static boolean isManaged(ModelType<?> type) {
        return type.isAnnotationPresent(Managed.class);
    }

    private class TypeRegistrationBuilderImpl<S extends PUBLIC> implements TypeRegistrationBuilder<S> {
        private final ModelRuleDescriptor source;
        private final TypeRegistration<S> registration;

        public TypeRegistrationBuilderImpl(ModelRuleDescriptor source, TypeRegistration<S> registration) {
            this.source = source;
            this.registration = registration;
        }

        @Override
        public TypeRegistrationBuilder<S> withImplementation(ModelType<?> implementationType, ImplementationFactory<S> factory) {
            registration.setImplementation(implementationType, source, factory);
            return this;
        }

        @Override
        public TypeRegistrationBuilder<S> withInternalView(ModelType<?> internalView) {
            registration.addInternalView(internalView, source);
            return this;
        }
    }

    private class TypeRegistration<S extends PUBLIC> {
        private final ModelType<S> publicType;
        private final boolean managedPublicType;
        private ImplementationRegistration<S> implementationRegistration;
        private final List<InternalViewRegistration<?>> internalViewRegistrations = Lists.newArrayList();

        public TypeRegistration(ModelType<S> publicType) {
            this.publicType = publicType;
            this.managedPublicType = isManaged(publicType);
        }

        public void setImplementation(ModelType<?> implementationType, ModelRuleDescriptor source, ImplementationFactory<S> factory) {
            if (implementationRegistration != null) {
                throw new IllegalStateException(String.format("Cannot register implementation for type '%s' because an implementation for this type was already registered by %s",
                    publicType, implementationRegistration.getSource()));
            }
            if (managedPublicType) {
                throw new IllegalArgumentException(String.format("Cannot specify default implementation for managed type '%s'", publicType));
            }
            if (!baseInterface.isAssignableFrom(implementationType)) {
                throw new IllegalArgumentException(String.format("Implementation type '%s' registered for '%s' must extend '%s'", implementationType, publicType, baseImplementation));
            }
            if (Modifier.isAbstract(implementationType.getConcreteClass().getModifiers())) {
                throw new IllegalArgumentException(String.format("Implementation type '%s' registered for '%s' must not be abstract", implementationType, publicType));
            }
            try {
                implementationType.getConcreteClass().getConstructor();
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException(String.format("Implementation type '%s' registered for '%s' must have a public default constructor", implementationType, publicType));
            }
            this.implementationRegistration = new ImplementationRegistration<S>(source, implementationType, factory);
        }

        public <V> void addInternalView(ModelType<V> internalView, ModelRuleDescriptor source) {
            if (!internalView.getConcreteClass().isInterface()) {
                throw new IllegalArgumentException(String.format("Internal view '%s' registered for '%s' must be an interface", internalView, publicType));
            }

            if (managedPublicType && !isManaged(internalView)) {
                throw new IllegalArgumentException(String.format("Internal view '%s' registered for managed type '%s' must be managed", internalView, publicType));
            }
            internalViewRegistrations.add(new InternalViewRegistration<V>(source, internalView));
        }

        public void validate() {
            if (managedPublicType) {
                validateManaged();
            } else {
                validateUnmanaged();
            }
        }

        public boolean isConstructible() {
            return managedPublicType || implementationRegistration != null;
        }

        private void validateManaged() {
            ImplementationInfo<? extends PUBLIC> implementationInfo = getManagedSubtypeImplementationInfo(publicType);
            ModelType<? extends PUBLIC> delegateType = implementationInfo.getDelegateType();
            for (InternalViewRegistration<?> internalViewRegistration : internalViewRegistrations) {
                validateManagedInternalView(internalViewRegistration, delegateType);
            }
        }

        private <V> void validateManagedInternalView(final InternalViewRegistration<V> internalViewRegistration, final ModelType<? extends PUBLIC> delegateType) {
            ModelSchemaUtils.walkTypeHierarchy(internalViewRegistration.getInternalView().getConcreteClass(), new ModelSchemaUtils.TypeVisitor<V>() {
                @Override
                public void visitType(Class<? super V> type) {
                    if (!type.isAnnotationPresent(Managed.class)
                        && !type.isAssignableFrom(delegateType.getConcreteClass())) {
                        throw new IllegalStateException(String.format("Factory registration for '%s' is invalid because the default implementation type '%s' does not implement unmanaged internal view '%s', "
                                + "internal view was registered by %s",
                            publicType, delegateType, type.getName(),
                            internalViewRegistration.getSource()));
                    }
                }
            });
        }

        private void validateUnmanaged() {
            if (implementationRegistration == null) {
                return;
            }

            ModelType<?> implementationType = implementationRegistration.getImplementationType();
            for (InternalViewRegistration<?> internalViewRegistration : internalViewRegistrations) {
                ModelType<?> internalView = internalViewRegistration.getInternalView();
                // Managed internal views are allowed not to be implemented by the default implementation
                if (isManaged(internalView)) {
                    continue;
                }
                if (!internalView.isAssignableFrom(implementationType)) {
                    throw new IllegalStateException(String.format(
                        "Factory registration for '%s' is invalid because the implementation type '%s' does not implement internal view '%s', "
                            + "implementation type was registered by %s, "
                            + "internal view was registered by %s",
                        publicType, implementationType, internalView,
                        implementationRegistration.getSource(),
                        internalViewRegistration.getSource()));
                }
            }
        }
    }

    private static class ImplementationRegistration<S> {
        private final ModelRuleDescriptor source;
        private final ModelType<?> implementationType;
        private final ImplementationFactory<S> factory;

        private ImplementationRegistration(ModelRuleDescriptor source, ModelType<?> implementationType, ImplementationFactory<S> factory) {
            this.source = source;
            this.implementationType = implementationType;
            this.factory = factory;
        }

        public ModelRuleDescriptor getSource() {
            return source;
        }

        public ModelType<?> getImplementationType() {
            return implementationType;
        }

        public ImplementationFactory<? extends S> getFactory() {
            return factory;
        }
    }

    private static class InternalViewRegistration<T> {
        private final ModelRuleDescriptor source;
        private final ModelType<T> internalView;

        private InternalViewRegistration(ModelRuleDescriptor source, ModelType<T> internalView) {
            this.source = source;
            this.internalView = internalView;
        }

        public ModelRuleDescriptor getSource() {
            return source;
        }

        public ModelType<T> getInternalView() {
            return internalView;
        }
    }

    private static class ImplementationInfoImpl<T> implements ImplementationInfo<T> {
        private final ModelType<? extends T> publicType;
        private final ImplementationRegistration<T> implementationRegistration;

        public ImplementationInfoImpl(ModelType<? extends T> publicType, ImplementationRegistration<? extends T> implementationRegistration) {
            this.publicType = publicType;
            this.implementationRegistration = Cast.uncheckedCast(implementationRegistration);
        }

        @Override
        public T create(MutableModelNode modelNode) {
            return implementationRegistration.factory.create(publicType, modelNode.getPath().getName(), modelNode);
        }

        public ModelType<? extends T> getDelegateType() {
            return Cast.uncheckedCast(implementationRegistration.implementationType);
        }

        @Override
        public String toString() {
            return String.valueOf(publicType);
        }
    }

    private abstract class RegistrationHierarchyVisitor<S> implements ModelSchemaUtils.TypeVisitor<S> {
        @Override
        public void visitType(Class<? super S> type) {
            if (!baseInterface.getConcreteClass().isAssignableFrom(type)) {
                return;
            }
            Class<? extends PUBLIC> superTypeClassAsBaseType = type.asSubclass(baseInterface.getConcreteClass());
            ModelType<? extends PUBLIC> superTypeAsBaseType = ModelType.of(superTypeClassAsBaseType);

            TypeRegistration<? extends PUBLIC> registration = getRegistration(superTypeAsBaseType);
            if (registration != null) {
                visitRegistration(registration);
            }
        }

        protected abstract void visitRegistration(TypeRegistration<? extends PUBLIC> registration);
    }
}
