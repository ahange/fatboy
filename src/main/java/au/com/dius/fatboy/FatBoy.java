package au.com.dius.fatboy;

import au.com.dius.fatboy.factory.ClassFactory;
import au.com.dius.fatboy.factory.GenericClassFactory;
import au.com.dius.fatboy.factory.GenericTypeFactory;
import au.com.dius.fatboy.factory.config.FactoryHint;
import au.com.dius.fatboy.utils.ReflectionUtils;
import com.github.javafaker.Faker;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeResolver;
import org.apache.commons.lang.exception.ExceptionUtils;

import java.lang.ref.SoftReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static au.com.dius.fatboy.utils.LambdaUtils.unchecked;
import static au.com.dius.fatboy.utils.ReflectionUtils.getActualTypeArgs;
import static au.com.dius.fatboy.utils.ReflectionUtils.getRawType;

public class FatBoy {
    public static final Faker FAKER = new Faker();

    private final FactoryRepository factoryRepository;

    public FatBoy() {
        factoryRepository = new FactoryRepository(this);
        factoryRepository.addIgnoredClass(SoftReference.class);
    }

    public <T> FatBoy registerClassFactory(ClassFactory<T> factory) {
        factoryRepository.addFactory(factory);
        return this;
    }

    public <T> FatBoy registerClassFactory(Class<T> clazz, Supplier<T> supplier) {
        factoryRepository.addFactory(clazz, supplier);
        return this;
    }

    public <T> FatBoy setClassConstant(T value) {
        factoryRepository.addFactory((Class<T>) value.getClass(), () -> value);
        return this;
    }

    public <T> FatBoy registerGenericFactory(Class<T> clazz, GenericTypeFactory<T> factory) {
        factoryRepository.addFactory(clazz, factory);
        return this;
    }

    public <T> FatBoy registerGenericFactory(Field field, GenericTypeFactory<T> factory) {
        factoryRepository.addFactory(field, factory);
        return this;
    }

    public <T> FatBoy setFieldConstant(Class clazz, String field, T value) {
        return setFieldConstant(ReflectionUtils.getField(clazz, field), value);
    }

    public <T> FatBoy setFieldConstant(Field field, T value) {
        return registerFieldFactory(field, () -> value);
    }

    public <T> FatBoy registerFieldFactory(Class clazz, String field, Supplier<T> factory) {
        return registerFieldFactory(ReflectionUtils.getField(clazz, field), factory);
    }

    public <T> FatBoy registerFieldFactory(Field field, Supplier<T> factory) {
        factoryRepository.addFactory(field, factory);
        return this;
    }

    public <T> FatBoy registerFatBoyProvidedFactory(Class<T> clazz, FatBoyProvidedFactory<T> factory) {
        factoryRepository.addFactory(clazz, () -> factory.create(this));
        return this;
    }

    public <T extends ClassFactory> ClassFactory findFactory(Class<T> factoryClass) {
        return factoryRepository.findFactory(factoryClass);
    }

    public FatBoy addIgnoredClass(Class clazz) {
        factoryRepository.addIgnoredClass(clazz);
        return this;
    }

    public <T extends ClassFactory> FatBoy hint(Class<T> factoryClass, FactoryHint factoryHint) {
        findFactory(factoryClass).putHint(factoryHint);
        return this;
    }

    public <T> T create(Class<T> clazz) {
        return create(clazz, Maps.newHashMap());
    }

    public <T> T create(Class<T> clazz, Map<String, Object> overrides) {
        ClassFactory<T> factory = factoryRepository.getFactoryForClass(clazz);

        if(clazz.isEnum()) {

        }

        if (factory != null) {
            return factory.create(null, clazz);
        }

        if (clazz.isPrimitive()) {
            throw new ClassInstantiationException("Primitive has no factory: [" + clazz + "]");
        }

        if (clazz.isInterface()) {
            throw new ClassInstantiationException("Interface has no factory: [" + clazz + "]");
        }

        try {
            return createInstance(clazz, overrides);
        } catch (ClassInstantiationException e) {
            throw (RuntimeException) ExceptionUtils.getRootCause(e);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public Object createGeneric(Type type) {
        return createGeneric(type, null);
    }

    private Object createGeneric(Type type, Field field) {
        ClassFactory fieldFactory = factoryRepository.getFactoryForField(field);

        if(fieldFactory != null) {
            return createTypeUsingFieldFactory(type, field, fieldFactory);
        } else if (type instanceof ParameterizedType) {
            return createGenericType(type);
        } else if (ReflectionUtils.isArrayType(type)) {
            return createArrayType(type);
        } else if (type instanceof Class) {
            return create((Class) type);
        } else {
            throw new ClassInstantiationException("Unknown generic type: [" + type + "]");
        }
    }

    private Object createTypeUsingFieldFactory(Type type, Field field, ClassFactory fieldFactory) {
        if (fieldFactory instanceof GenericClassFactory) {
            Class rawType = getRawType(type);
            Type[] actualTypeArguments = getActualTypeArgs(type);
            return ((GenericClassFactory) fieldFactory).create(rawType, actualTypeArguments);
        } else {
            return fieldFactory.create(field, field.getType());
        }
    }

    private <T> T createInstance(Class<T> rawType, Map<String, Object> overrides, Type... actualTypeArguments) {
        try {
            if (actualTypeArguments.length == 0 && ReflectionUtils.classIsGeneric(rawType)) {
                actualTypeArguments = ReflectionUtils.getClassTypeArguments(rawType);
            }
            Constructor<T> constructor = ReflectionUtils.getDefaultOrFirstConstructor(rawType);
            constructor.setAccessible(true);

            List<Object> args = Arrays.asList(constructor.getGenericParameterTypes()).stream()
                    .map(this::createGeneric)
                    .collect(Collectors.toList());

            T instance = constructor.newInstance(args.toArray());
            return setFields(instance, overrides, actualTypeArguments);
        } catch (Exception e) {
            throw new ClassInstantiationException(e.getMessage(), e);
        }
    }

    private <T> T setFields(final T instance, Map<String, Object> overrides, Type... types) {
        ReflectionUtils.getAllDeclaredFields(instance.getClass()).stream()
                .forEach(unchecked(field -> {
                    field.setAccessible(true);

                    if (overrides.containsKey(field.getName())) {
                        field.set(instance, overrides.get(field.getName()));
                        return;
                    }

                    if (!(field.getGenericType() instanceof Class)) {
                        if (ReflectionUtils.typeIsFullyResolved(field)) {
                            field.set(instance, createGeneric(field.getGenericType(), field));
                        } else {
                            Type resolvedType = resolveType(field, types, field.getDeclaringClass().getTypeParameters());
                            field.set(instance, createGeneric(resolvedType, field));
                        }
                    } else {
                        field.set(instance, createGeneric(field.getType(), field));
                    }
                }));

        return instance;
    }

    private Type resolveType(Field field, Type[] types, Type[] genericTypes) {
        TypeResolver typeResolver = new TypeResolver();
        for (int x = 0; x < types.length; x++) {
            typeResolver = typeResolver.where(genericTypes[x], types[x]);
        }
        return typeResolver.resolveType(field.getGenericType());
    }

    private Object createArrayType(Type type) {
        Class rawType = getRawType(type);
        Type[] actualType = getActualTypeArgs(type);
        return factoryRepository.getFactoryForGenericType(rawType, type).create(rawType, actualType);
    }

    private Object createGenericType(Type type) {
        Class rawType = getRawType(type);
        Type[] actualTypeArguments = getActualTypeArgs(type);
        GenericClassFactory factory = factoryRepository.getFactoryForGenericType(rawType, type);

        if (factory != null) {
            return factory.create(rawType, actualTypeArguments);
        } else {
            return createInstance(rawType, Maps.newHashMap(), actualTypeArguments);
        }
    }

    @FunctionalInterface
    public interface FatBoyProvidedFactory<T> {
        T create(FatBoy fatBoy);
    }
}