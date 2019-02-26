/*
 * Copyright 2017-2019 original authors
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
package io.micronaut.jackson.modules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.*;
import com.fasterxml.jackson.databind.deser.impl.MethodProperty;
import com.fasterxml.jackson.databind.deser.std.StdValueInstantiator;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.TypeResolutionContext;
import com.fasterxml.jackson.databind.introspect.VirtualAnnotatedMember;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerBuilder;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.impl.PropertySerializerMap;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.jackson.JacksonConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Singleton;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.*;

/**
 * A Jackson module that adds reflection-free bean serialization and deserialization for Micronaut.
 *
 * @author graemerocher
 * @since 1.1
 */
@Internal
@Experimental
@Singleton
@Requires(property = JacksonConfiguration.PROPERTY_USE_BEAN_INTROSPECTION)
public class BeanIntrospectionModule extends SimpleModule {

    private static final Logger LOG = LoggerFactory.getLogger(BeanIntrospectionModule.class);

    /**
     * Default constructor.
     */
    public BeanIntrospectionModule() {
        setDeserializerModifier(new BeanIntrospectionDeserializerModifier());
        setSerializerModifier(new BeanIntrospectionSerializerModifier());
    }

    private JavaType newType(Argument<?> argument, TypeFactory typeFactory) {
        return JacksonConfiguration.constructType(argument, typeFactory);
    }

    private PropertyMetadata newPropertyMetadata(Argument<?> argument, AnnotationMetadata annotationMetadata) {
        final Boolean required = argument.isAnnotationPresent(Nonnull.class) ||
                annotationMetadata.getValue(JsonProperty.class, "required", Boolean.class).orElse(false);

        return PropertyMetadata.construct(
                required,
                annotationMetadata.getValue(JsonPropertyDescription.class, String.class).orElse(null),
                annotationMetadata.getValue(JsonProperty.class, "index", Integer.class).orElse(null),
                annotationMetadata.getValue(JsonProperty.class, "defaultValue", String.class).orElse(null)
        );
    }

    /**
     * Modifies bean serialization.
     */
    private class BeanIntrospectionSerializerModifier extends BeanSerializerModifier {
        @Override
        public BeanSerializerBuilder updateBuilder(SerializationConfig config, BeanDescription beanDesc, BeanSerializerBuilder builder) {
            final Class<?> beanClass = beanDesc.getBeanClass();
            final BeanIntrospection<Object> introspection =
                    (BeanIntrospection<Object>) BeanIntrospector.SHARED.findIntrospection(beanClass).orElse(null);
            if (introspection == null) {
                return super.updateBuilder(config, beanDesc, builder);
            } else {
                final BeanSerializerBuilder newBuilder = new BeanSerializerBuilder(beanDesc) {
                    @Override
                    public JsonSerializer<?> build() {
                        setConfig(config);
                        try {
                            return super.build();
                        } catch (RuntimeException e) {
                            if (LOG.isErrorEnabled()) {
                                LOG.error("Error building bean serializer for type [" + beanClass + "]: " + e.getMessage(), e);
                            }
                            throw e;
                        }
                    }
                };
                final List<BeanPropertyWriter> properties = builder.getProperties();
                final Collection<BeanProperty<Object, Object>> beanProperties = introspection.getBeanProperties();
                if (CollectionUtils.isEmpty(properties) && CollectionUtils.isNotEmpty(beanProperties)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Bean {} has no properties, while BeanIntrospection does. Recreating from introspection.", beanClass);
                    }
                    final List<BeanPropertyWriter> newProperties = new ArrayList<>(beanProperties.size());
                    for (BeanProperty<Object, Object> beanProperty : beanProperties) {
                        BeanPropertyWriter writer = new BeanIntrospectionPropertyWriter(
                                beanProperty.getValue(JsonProperty.class, String.class).orElse(beanProperty.getName()),
                                beanProperty,
                                config.getTypeFactory()
                        );

                        newProperties.add(writer);
                    }

                    newBuilder.setProperties(newProperties);
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Updating {} properties with BeanIntrospection data for type: ", properties.size(), beanClass);
                    }

                    final List<BeanPropertyWriter> newProperties = new ArrayList<>(properties);
                    Map<String, BeanProperty> named = new LinkedHashMap<>(properties.size());
                    for (BeanProperty<Object, Object> beanProperty : beanProperties) {
                        final Optional<String> n = beanProperty.getValue(JsonProperty.class, String.class);
                        n.ifPresent(s -> named.put(s, beanProperty));
                    }
                    for (int i = 0; i < properties.size(); i++) {
                        final BeanPropertyWriter existing = properties.get(i);

                        final Optional<BeanProperty<Object, Object>> property;
                        final String existingName = existing.getName();
                        if (named.containsKey(existingName)) {
                            property = Optional.of(named.get(existingName));
                        } else {
                            property = introspection.getProperty(existingName);
                        }
                        if (property.isPresent()) {
                            newProperties.set(i, new BeanIntrospectionPropertyWriter(existing, property.get(), existing.getSerializer(), config.getTypeFactory()));
                        } else {
                            newProperties.set(i, existing);
                        }
                    }
                    newBuilder.setProperties(newProperties);
                }
                return newBuilder;
            }
        }
    }

    /**
     * Modifies bean deserialization.
     */
    private class BeanIntrospectionDeserializerModifier extends BeanDeserializerModifier {

        @Override
        public BeanDeserializerBuilder updateBuilder(
                DeserializationConfig config,
                BeanDescription beanDesc,
                BeanDeserializerBuilder builder) {


            final Class<?> beanClass = beanDesc.getBeanClass();
            final BeanIntrospection<Object> introspection = (BeanIntrospection<Object>) BeanIntrospector.SHARED.findIntrospection(beanClass).orElse(null);
            if (introspection == null) {
                return builder;
            } else {
                final Iterator<SettableBeanProperty> properties = builder.getProperties();
                if (!properties.hasNext() && introspection.getPropertyNames().length > 0) {
                    // mismatch, probably GraalVM reflection not enabled for bean. Try recreate
                    for (BeanProperty<Object, Object> beanProperty : introspection.getBeanProperties()) {
                        builder.addOrReplaceProperty(new VirtualSetter(beanDesc.getClassInfo(), config.getTypeFactory(), beanProperty), true);
                    }
                } else {
                    while (properties.hasNext()) {
                        final SettableBeanProperty settableBeanProperty = properties.next();
                        if (settableBeanProperty instanceof MethodProperty) {
                            MethodProperty methodProperty = (MethodProperty) settableBeanProperty;
                            final Optional<BeanProperty<Object, Object>> beanProperty = introspection.getProperty(settableBeanProperty.getName());

                            if (beanProperty.isPresent()) {
                                SettableBeanProperty newProperty = new BeanIntrospectionSetter(methodProperty, beanProperty.get());
                                builder.addOrReplaceProperty(newProperty, true);
                            }
                        }
                    }
                }

                final Argument<?>[] constructorArguments = introspection.getConstructorArguments();
                final TypeFactory typeFactory = config.getTypeFactory();
                builder.setValueInstantiator(new StdValueInstantiator(config, config.getTypeFactory().constructType(beanClass)) {

                    @Override
                    public SettableBeanProperty[] getFromObjectArguments(DeserializationConfig config) {


                        SettableBeanProperty[] props = new SettableBeanProperty[constructorArguments.length];
                        for (int i = 0; i < constructorArguments.length; i++) {
                            Argument<?> argument = constructorArguments[i];
                            final JavaType javaType = newType(argument, typeFactory);
                            final AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
                            PropertyMetadata propertyMetadata = newPropertyMetadata(argument, annotationMetadata);
                            final String simpleName = annotationMetadata.getValue(JsonProperty.class, String.class).orElse(argument.getName());
                            props[i] = new CreatorProperty(
                                    PropertyName.construct(simpleName),
                                    javaType,
                                    null,
                                    null,
                                    null,
                                    null,
                                    i,
                                    null,
                                    propertyMetadata

                            ) {
                                private final BeanProperty<Object, Object> property = introspection.getProperty(argument.getName()).orElse(null);

                                @Override
                                public <A extends Annotation> A getAnnotation(Class<A> acls) {
                                    return annotationMetadata.synthesize(acls);
                                }

                                @Override
                                public AnnotatedMember getMember() {
                                    return new VirtualAnnotatedMember(
                                            beanDesc.getClassInfo(),
                                            beanClass,
                                            argument.getName(),
                                            javaType
                                    ) {
                                        @Override
                                        public boolean hasOneOf(Class<? extends Annotation>[] annoClasses) {
                                            return Arrays.stream(annoClasses).anyMatch(annotationMetadata::hasAnnotation);
                                        }
                                    };
                                }

                                @Override
                                public void deserializeAndSet(JsonParser p, DeserializationContext ctxt, Object instance) throws IOException {
                                    if (property != null) {
                                        property.set(instance, deserialize(p, ctxt));
                                    }
                                }

                                @Override
                                public Object deserializeSetAndReturn(JsonParser p, DeserializationContext ctxt, Object instance) throws IOException {
                                    if (property != null) {
                                        property.set(instance, deserialize(p, ctxt));
                                    }
                                    return null;
                                }

                                @Override
                                public void set(Object instance, Object value) {
                                    if (property != null) {
                                        property.set(instance, value);
                                    }
                                }

                                @Override
                                public Object setAndReturn(Object instance, Object value) throws IOException {
                                    if (property != null) {
                                        property.set(instance, value);
                                    }
                                    return null;
                                }
                            };
                        }
                        return props;
                    }

                    @Override
                    public boolean canInstantiate() {
                        return true;
                    }

                    @Override
                    public boolean canCreateUsingDefault() {
                        return constructorArguments.length == 0;
                    }

                    @Override
                    public boolean canCreateFromObjectWith() {
                        return constructorArguments.length > 0;
                    }

                    @Override
                    public Object createUsingDefault(DeserializationContext ctxt) throws IOException {
                        return introspection.instantiate();
                    }

                    @Override
                    public Object createFromObjectWith(DeserializationContext ctxt, Object[] args) throws IOException {
                        return introspection.instantiate(args);
                    }
                });
                return builder;
            }
        }
    }

    /**
     * A virtual property setter.
     */
    private class VirtualSetter extends SettableBeanProperty {

        final BeanProperty beanProperty;
        final TypeResolutionContext typeResolutionContext;

        VirtualSetter(TypeResolutionContext typeResolutionContext, TypeFactory typeFactory, BeanProperty beanProperty) {
            super(
                    new PropertyName(beanProperty.getName()),
                    newType(beanProperty.asArgument(), typeFactory),
                    newPropertyMetadata(beanProperty.asArgument(), beanProperty.getAnnotationMetadata()), null);
            this.beanProperty = beanProperty;
            this.typeResolutionContext = typeResolutionContext;
        }

        VirtualSetter(PropertyName propertyName, VirtualSetter src) {
            super(propertyName, src._type, src._metadata, src._valueDeserializer);
            this.beanProperty = src.beanProperty;
            this.typeResolutionContext = src.typeResolutionContext;
        }

        VirtualSetter(NullValueProvider nullValueProvider, VirtualSetter src) {
            super(src, src._valueDeserializer, nullValueProvider);
            this.beanProperty = src.beanProperty;
            this.typeResolutionContext = src.typeResolutionContext;
        }

        VirtualSetter(JsonDeserializer<Object> deser, VirtualSetter src) {
            super(src._propName, src._type, src._metadata, deser);
            this.beanProperty = src.beanProperty;
            this.typeResolutionContext = src.typeResolutionContext;
        }

        @Override
        public SettableBeanProperty withValueDeserializer(JsonDeserializer<?> deser) {
            return new VirtualSetter((JsonDeserializer<Object>) deser, this);
        }

        @Override
        public SettableBeanProperty withName(PropertyName newName) {
            return new VirtualSetter(newName, this);
        }

        @Override
        public SettableBeanProperty withNullProvider(NullValueProvider nva) {
            return new VirtualSetter(nva, this);
        }

        @Override
        public AnnotatedMember getMember() {
            return new VirtualAnnotatedMember(
                    typeResolutionContext,
                    beanProperty.getDeclaringType(),
                    _propName.getSimpleName(),
                    _type
            ) {
                @Override
                public boolean hasOneOf(Class<? extends Annotation>[] annoClasses) {
                    return Arrays.stream(annoClasses).anyMatch(beanProperty::hasAnnotation);
                }
            };
        }

        @Override
        public <A extends Annotation> A getAnnotation(Class<A> acls) {
            return beanProperty.getAnnotationMetadata().synthesize(acls);
        }

        @Override
        public void deserializeAndSet(JsonParser p, DeserializationContext ctxt, Object instance) throws IOException {
            beanProperty.set(instance, deserialize(p, ctxt));
        }

        @Override
        public Object deserializeSetAndReturn(JsonParser p, DeserializationContext ctxt, Object instance) throws IOException {
            beanProperty.set(instance, deserialize(p, ctxt));
            return null;
        }

        @Override
        public void set(Object instance, Object value) throws IOException {
            beanProperty.set(instance, value);
        }

        @Override
        public Object setAndReturn(Object instance, Object value) throws IOException {
            beanProperty.set(instance, value);
            return null;
        }
    }


    /**
     * Introspected property writer.
     */
    private class BeanIntrospectionPropertyWriter extends BeanPropertyWriter {
        final BeanProperty<Object, Object> beanProperty;
        final SerializableString fastName;
        private final JavaType type;

        BeanIntrospectionPropertyWriter(BeanPropertyWriter src,
                                        BeanProperty<Object, Object> introspection,
                                        JsonSerializer<Object> ser,
                                        TypeFactory typeFactory) {
            super(src);
            // either use the passed on serializer or the original one
            _serializer = (ser != null) ? ser : src.getSerializer();
            beanProperty = introspection;
            fastName = src.getSerializedName();
            this.type = JacksonConfiguration.constructType(beanProperty.asArgument(), typeFactory);
        }

        BeanIntrospectionPropertyWriter(String name, BeanProperty<Object, Object> introspection,
                                        TypeFactory typeFactory) {
            beanProperty = introspection;
            fastName = new SerializedString(name);
            this.type = JacksonConfiguration.constructType(beanProperty.asArgument(), typeFactory);
        }

        @Override
        public void fixAccess(SerializationConfig config) {
            // no-op
        }

        @Override
        public JavaType getType() {
            return type;
        }

        @Override
        public final void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            Object value = beanProperty.get(bean);
            // Null (etc) handling; copied from super-class impl
            if (value == null) {
                if (_nullSerializer != null) {
                    gen.writeFieldName(fastName);
                    _nullSerializer.serialize(null, gen, prov);
                } else if (!_suppressNulls) {
                    gen.writeFieldName(fastName);
                    prov.defaultSerializeNull(gen);
                }
                return;
            }
            JsonSerializer<Object> ser = _serializer;
            if (ser == null) {
                Class<?> cls = value.getClass();
                PropertySerializerMap map = _dynamicSerializers;
                ser = map.serializerFor(cls);
                if (ser == null) {
                    ser = _findAndAddDynamic(map, cls, prov);
                }
            }
            if (_suppressableValue != null) {
                if (MARKER_FOR_EMPTY == _suppressableValue) {
                    if (ser.isEmpty(prov, value)) {
                        return;
                    }
                } else if (_suppressableValue.equals(value)) {
                    return;
                }
            }
            if (value == bean) {
                // three choices: exception; handled by call; or pass-through
                if (_handleSelfReference(bean, gen, prov, ser)) {
                    return;
                }
            }
            gen.writeFieldName(fastName);
            if (_typeSerializer == null) {
                ser.serialize(value, gen, prov);
            } else {
                ser.serializeWithType(value, gen, prov, _typeSerializer);
            }
        }

        @Override
        public final void serializeAsElement(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            Object value = beanProperty.get(bean);
            // Null (etc) handling; copied from super-class impl
            if (value == null) {
                if (_nullSerializer != null) {
                    _nullSerializer.serialize(null, gen, prov);
                } else if (_suppressNulls) {
                    serializeAsPlaceholder(bean, gen, prov);
                } else {
                    prov.defaultSerializeNull(gen);
                }
                return;
            }
            JsonSerializer<Object> ser = _serializer;
            if (ser == null) {
                Class<?> cls = value.getClass();
                PropertySerializerMap map = _dynamicSerializers;
                ser = map.serializerFor(cls);
                if (ser == null) {
                    ser = _findAndAddDynamic(map, cls, prov);
                }
            }
            if (_suppressableValue != null) {
                if (MARKER_FOR_EMPTY == _suppressableValue) {
                    if (ser.isEmpty(prov, value)) {
                        serializeAsPlaceholder(bean, gen, prov);
                        return;
                    }
                } else if (_suppressableValue.equals(value)) {
                    serializeAsPlaceholder(bean, gen, prov);
                    return;
                }
            }
            if (value == bean) {
                // three choices: exception; handled by call; or pass-through
                if (_handleSelfReference(bean, gen, prov, ser)) {
                    return;
                }
            }
            if (_typeSerializer == null) {
                ser.serialize(value, gen, prov);
            } else {
                ser.serializeWithType(value, gen, prov, _typeSerializer);
            }
        }

    }

    /**
     * A bean introspection setter.
     */
    private static class BeanIntrospectionSetter extends SettableBeanProperty.Delegating {

        final BeanProperty beanProperty;

        BeanIntrospectionSetter(SettableBeanProperty methodProperty, BeanProperty beanProperty) {
            super(methodProperty);
            this.beanProperty = beanProperty;
        }

        @Override
        protected SettableBeanProperty withDelegate(SettableBeanProperty d) {
            return new BeanIntrospectionSetter(d, beanProperty);
        }

        @Override
        public void deserializeAndSet(JsonParser p, DeserializationContext ctxt, Object instance) throws IOException {
            beanProperty.set(instance, deserialize(p, ctxt));
        }

        @Override
        public Object deserializeSetAndReturn(JsonParser p, DeserializationContext ctxt, Object instance) throws IOException {
            beanProperty.set(instance, deserialize(p, ctxt));
            return null;
        }

        @Override
        public void set(Object instance, Object value) {
            beanProperty.set(instance, value);
        }

        @Override
        public Object setAndReturn(Object instance, Object value) {
            beanProperty.set(instance, value);
            return null;
        }
    }
}
