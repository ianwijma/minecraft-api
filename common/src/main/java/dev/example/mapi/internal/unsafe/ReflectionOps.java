package dev.example.mapi.internal.unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trusted developer execution (spec §4.3): class introspection and static
 * no-arg invocation via reflection. This runs with the privileges of the
 * Minecraft process — there is no sandbox and none is claimed. Gated by the
 * {@code unsafe.execute} scope and the {@code reflection.enabled} switch;
 * every invocation is audited as an event.
 */
public final class ReflectionOps {

    /** Maximum reported members per class. */
    public static final int MAX_MEMBERS = 500;

    /** Maximum serialization depth for invocation results. */
    private static final int MAX_RESULT_DEPTH = 8;

    private ReflectionOps() {
    }

    /**
     * Introspects a class: declared methods, fields, and constructors.
     *
     * @param className binary class name
     * @return the description payload
     * @throws ClassNotFoundException when the class is not loaded/loadable
     */
    public static Map<String, Object> describe(String className) throws ClassNotFoundException {
        Class<?> clazz = Class.forName(className, false, ReflectionOps.class.getClassLoader());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("className", clazz.getName());
        out.put("abstract", Modifier.isAbstract(clazz.getModifiers()));
        out.put("interface", clazz.isInterface());
        out.put("enum", clazz.isEnum());
        List<Map<String, Object>> methods = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (methods.size() >= MAX_MEMBERS) {
                break;
            }
            List<String> params = new ArrayList<>();
            for (Class<?> parameter : method.getParameterTypes()) {
                params.add(parameter.getName());
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", method.getName());
            entry.put("static", Modifier.isStatic(method.getModifiers()));
            entry.put("returnType", method.getReturnType().getName());
            entry.put("params", params);
            methods.add(entry);
        }
        out.put("methods", methods);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (fields.size() >= MAX_MEMBERS) {
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", field.getName());
            entry.put("static", Modifier.isStatic(field.getModifiers()));
            entry.put("type", field.getType().getName());
            fields.add(entry);
        }
        out.put("fields", fields);
        List<Map<String, Object>> constructors = new ArrayList<>();
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (constructors.size() >= MAX_MEMBERS) {
                break;
            }
            List<String> params = new ArrayList<>();
            for (Class<?> parameter : constructor.getParameterTypes()) {
                params.add(parameter.getName());
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("params", params);
            constructors.add(entry);
        }
        out.put("constructors", constructors);
        return out;
    }

    /**
     * Invokes a static, no-arg method reflectively. The invocation itself
     * must run on the owning thread when the method touches game state; this
     * helper only performs the reflective call and serializes the result.
     *
     * @param className binary class name
     * @param methodName method name (first static no-arg match is used)
     * @return the serialized result payload
     * @throws ReflectiveOperationException on lookup/invocation failures
     * @throws IllegalArgumentException when the method is not static or takes
     *                                  arguments
     */
    public static Map<String, Object> invokeStatic(String className, String methodName)
            throws ReflectiveOperationException {
        Class<?> clazz = Class.forName(className, false, ReflectionOps.class.getClassLoader());
        Method target = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == 0
                    && Modifier.isStatic(method.getModifiers())) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("no static no-arg method '" + methodName + "' on " + className);
        }
        Object result = target.invoke(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("className", className);
        out.put("method", methodName);
        out.put("resultType", result == null ? "null" : result.getClass().getName());
        out.put("result", serialize(result, 0));
        return out;
    }

    private static Object serialize(Object value, int depth) {
        if (value == null || depth > MAX_RESULT_DEPTH) {
            return depth > MAX_RESULT_DEPTH ? "[depth-limit]" : null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof java.util.List<?> list) {
            List<Object> items = new ArrayList<>();
            for (Object item : list) {
                items.add(serialize(item, depth + 1));
            }
            return items;
        }
        if (value instanceof java.util.Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), serialize(entry.getValue(), depth + 1));
            }
            return out;
        }
        return String.valueOf(value);
    }
}
