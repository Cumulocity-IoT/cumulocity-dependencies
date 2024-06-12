package org.springframework.core.enums;

import java.util.Map;
import java.util.Set;

/**
 * Interface for looking up <code>LabeledEnum</code> instances.
 *
 * @author Keith Donald
 * @author Juergen Hoeller
 * @since 1.2.2
 */
public interface LabeledEnumResolver {

    /**
     * Return a set of enumerations of a particular type. Each element in the
     * set should be an instance of LabeledEnum.
     * @param type the enum type
     * @return a set of localized enumeration instances for the provided type
     * @throws IllegalArgumentException if the type is not supported
     */
    public Set getLabeledEnumSet(Class type) throws IllegalArgumentException;

    /**
     * Return a map of enumerations of a particular type. Each element in the
     * map should be a key/value pair, where the key is the enum code, and the
     * value is the <code>LabeledEnum</code> instance.
     * @param type the enum type
     * @return a Map of localized enumeration instances,
     * with enum code as key and <code>LabeledEnum</code> instance as value
     * @throws IllegalArgumentException if the type is not supported
     */
    public Map getLabeledEnumMap(Class type) throws IllegalArgumentException;

    /**
     * Resolve a single <code>LabeledEnum</code> by its identifying code.
     * @param type the enum type
     * @param code the enum code
     * @return the enum
     * @throws IllegalArgumentException if the code did not map to a valid instance
     */
    public LabeledEnum getLabeledEnumByCode(Class type, Comparable code) throws IllegalArgumentException;

    /**
     * Resolve a single <code>LabeledEnum</code> by its identifying code.
     * @param type the enum type
     * @param label the enum label
     * @return the enum
     * @throws IllegalArgumentException if the label did not map to a valid instance
     */
    public LabeledEnum getLabeledEnumByLabel(Class type, String label) throws IllegalArgumentException;

}
