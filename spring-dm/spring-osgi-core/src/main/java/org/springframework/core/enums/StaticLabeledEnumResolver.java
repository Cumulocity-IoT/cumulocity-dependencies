package org.springframework.core.enums;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.util.Assert;

/**
 * {@link LabeledEnumResolver} that resolves statically defined enumerations.
 * Static implies all enum instances were defined within Java code,
 * implementing the type-safe enum pattern.
 *
 * @author Keith Donald
 * @author Juergen Hoeller
 * @since 1.2.2
 */
public class StaticLabeledEnumResolver extends AbstractCachingLabeledEnumResolver {

    /**
     * Shared <code>StaticLabeledEnumResolver</code> singleton instance.
     */
    private static final StaticLabeledEnumResolver INSTANCE = new StaticLabeledEnumResolver();


    /**
     * Return the shared <code>StaticLabeledEnumResolver</code> singleton instance.
     * Mainly for resolving unique StaticLabeledEnum references on deserialization.
     * @see StaticLabeledEnum
     */
    public static StaticLabeledEnumResolver instance() {
        return INSTANCE;
    }


    protected Set findLabeledEnums(Class type) {
        Set typeEnums = new TreeSet();
        Field[] fields = type.getFields();
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())) {
                if (type.isAssignableFrom(field.getType())) {
                    try {
                        Object value = field.get(null);
                        Assert.isTrue(value instanceof LabeledEnum, "Field value must be a LabeledEnum instance");
                        typeEnums.add(value);
                    }
                    catch (IllegalAccessException ex) {
                        logger.warn("Unable to access field value: " + field, ex);
                    }
                }
            }
        }
        return typeEnums;
    }

}
