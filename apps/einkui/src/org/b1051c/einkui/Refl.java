package org.b1051c.einkui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;

/** Small cached reflection helpers for SystemUI internals (names from the A16 TrebleDroid SystemUI, not obfuscated). */
final class Refl {
    private static final HashMap<String, Field> FIELDS = new HashMap<>();
    private static final HashMap<String, Method> METHODS = new HashMap<>();

    private Refl() {}

    static Field field(Class<?> c, String name) {
        String key = c.getName() + "#" + name;
        if (FIELDS.containsKey(key)) return FIELDS.get(key);
        Field f = null;
        for (Class<?> k = c; k != null && f == null; k = k.getSuperclass()) {
            try { f = k.getDeclaredField(name); f.setAccessible(true); } catch (NoSuchFieldException ignored) {}
        }
        FIELDS.put(key, f);
        return f;
    }

    static Object get(Object o, String name) {
        Field f = field(o.getClass(), name);
        try { return f == null ? null : f.get(o); } catch (IllegalAccessException e) { return null; }
    }

    static int getInt(Object o, String name, int def) {
        Field f = field(o.getClass(), name);
        try { return f == null ? def : f.getInt(o); } catch (IllegalAccessException e) { return def; }
    }

    static float getFloat(Object o, String name, float def) {
        Field f = field(o.getClass(), name);
        try { return f == null ? def : f.getFloat(o); } catch (IllegalAccessException e) { return def; }
    }

    /** Works for final instance fields too (ART allows it once accessible). */
    static boolean set(Object o, String name, Object v) {
        Field f = field(o.getClass(), name);
        if (f == null) return false;
        try { f.set(o, v); return true; } catch (IllegalAccessException | IllegalArgumentException e) { return false; }
    }

    static Method method(Class<?> c, String name, Class<?>... args) {
        String key = c.getName() + "#" + name + "/" + args.length;
        if (METHODS.containsKey(key)) return METHODS.get(key);
        Method m = null;
        for (Class<?> k = c; k != null && m == null; k = k.getSuperclass()) {
            try { m = k.getDeclaredMethod(name, args); m.setAccessible(true); } catch (NoSuchMethodException ignored) {}
        }
        METHODS.put(key, m);
        return m;
    }

    static boolean call(Object o, String name, Class<?>[] types, Object... args) {
        Method m = method(o.getClass(), name, types);
        if (m == null) return false;
        try { m.invoke(o, args); return true; } catch (ReflectiveOperationException | IllegalArgumentException e) { return false; }
    }
}
