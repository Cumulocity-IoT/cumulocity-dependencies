package org.springframework.core.enums;

import java.io.Serializable;
import java.util.Comparator;

import org.springframework.util.comparator.CompoundComparator;
import org.springframework.util.comparator.NullSafeComparator;

/**
 * An interface for objects that represent a labeled enumeration.
 * Each such enum instance has the following characteristics:
 *
 * <ul>
 * <li>A type that identifies the enum's class.
 * For example: <code>com.mycompany.util.FileFormat</code>.</li>
 *
 * <li>A code that uniquely identifies the enum within the context of its type.
 * For example: &quot;CSV&quot;. Different classes of codes are possible
 * (e.g., Character, Integer, String).</li>
 *
 * <li>A descriptive label. For example: "the CSV File Format".</li>
 * </ul>
 *
 * @author Keith Donald
 * @since 1.2.2
 */
public interface LabeledEnum extends Comparable, Serializable {

    /**
     * Return this enumeration's type.
     */
    Class getType();

    /**
     * Return this enumeration's code.
     * <p>Each code should be unique within enumerations of the same type.
     */
    Comparable getCode();

    /**
     * Return a descriptive, optional label.
     */
    String getLabel();


    // Constants for standard enum ordering (Comparator implementations)

    /**
     * Shared Comparator instance that sorts enumerations by <code>CODE_ORDER</code>.
     */
    Comparator CODE_ORDER = new Comparator() {
        public int compare(Object o1, Object o2) {
            Comparable c1 = ((LabeledEnum) o1).getCode();
            Comparable c2 = ((LabeledEnum) o2).getCode();
            return c1.compareTo(c2);
        }
    };

    /**
     * Shared Comparator instance that sorts enumerations by <code>LABEL_ORDER</code>.
     */
    Comparator LABEL_ORDER = new Comparator() {
        public int compare(Object o1, Object o2) {
            LabeledEnum e1 = (LabeledEnum) o1;
            LabeledEnum e2 = (LabeledEnum) o2;
            Comparator comp = new NullSafeComparator(String.CASE_INSENSITIVE_ORDER, true);
            return comp.compare(e1.getLabel(), e2.getLabel());
        }
    };

    /**
     * Shared Comparator instance that sorts enumerations by <code>LABEL_ORDER</code>,
     * then <code>CODE_ORDER</code>.
     */
    Comparator DEFAULT_ORDER =
            new CompoundComparator(new Comparator[] { LABEL_ORDER, CODE_ORDER });

}
