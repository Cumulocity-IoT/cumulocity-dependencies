package org.springframework.osgi.config.internal.util;

import java.lang.reflect.Method;

/**
 * Internal class that deals with Method handling. The main intent for this
 * class is to support bridge methods without requiring a JDK 5 to compile
 * (since maven will use the same VM for tests which is not what we want as we
 * do integration testing).
 *
 * @author Costin Leau
 *
 */
public abstract class MethodUtils {

    private static final int BRIDGE = 0x00000040;

    /**
     * Override default implementation skipping JDK5+ availability check.
     */
    public static boolean isBridge(Method method) {
        return (method.getModifiers() & BRIDGE) != 0;
    }
}
