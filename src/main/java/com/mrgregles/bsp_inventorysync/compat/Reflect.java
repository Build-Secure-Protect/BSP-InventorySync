package com.mrgregles.bsp_inventorysync.compat;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Small reflection helpers for the optional mods this mod never compiles against. Every miss is a null, never an exception. */
final class Reflect {
    private Reflect() {}

    @Nullable
    static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    @Nullable
    static Method method(@Nullable Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            Method m = owner.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    static Method declared(@Nullable Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    static Object constant(@Nullable Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            Field f = owner.getField(name);
            return f.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Invokes, rethrowing the target's own exception as a RuntimeException so the module isolation logs it. */
    static Object call(Method m, @Nullable Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            throw c instanceof RuntimeException r ? r : new RuntimeException(c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
