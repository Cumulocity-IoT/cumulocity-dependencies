package org.springframework.core.enums;

/**
 * Base class for static type-safe labeled enum instances.
 *
 * Usage example:
 *
 * <pre>
 * public class FlowSessionStatus extends StaticLabeledEnum {
 *
 *     // public static final instances!
 *     public static FlowSessionStatus CREATED = new FlowSessionStatus(0, &quot;Created&quot;);
 *     public static FlowSessionStatus ACTIVE = new FlowSessionStatus(1, &quot;Active&quot;);
 *     public static FlowSessionStatus PAUSED = new FlowSessionStatus(2, &quot;Paused&quot;);
 *     public static FlowSessionStatus SUSPENDED = new FlowSessionStatus(3, &quot;Suspended&quot;);
 *     public static FlowSessionStatus ENDED = new FlowSessionStatus(4, &quot;Ended&quot;);
 *
 *     // private constructor!
 *     private FlowSessionStatus(int code, String label) {
 *         super(code, label);
 *     }
 *
 *     // custom behavior
 * }</pre>
 *
 * @author Keith Donald
 * @since 1.2.6
 */
public abstract class StaticLabeledEnum extends AbstractLabeledEnum {

    /**
     * The unique code of the enum.
     */
    private final Short code;

    /**
     * A descriptive label for the enum.
     */
    private final transient String label;


    /**
     * Create a new StaticLabeledEnum instance.
     * @param code the short code
     * @param label the label (can be <code>null</code>)
     */
    protected StaticLabeledEnum(int code, String label) {
        this.code = new Short((short) code);
        if (label != null) {
            this.label = label;
        }
        else {
            this.label = this.code.toString();
        }
    }

    public Comparable getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Return the code of this LabeledEnum instance as a short.
     */
    public short shortValue() {
        return ((Number) getCode()).shortValue();
    }


    //---------------------------------------------------------------------
    // Serialization support
    //---------------------------------------------------------------------

    /**
     * Return the resolved type safe static enum instance.
     */
    protected Object readResolve() {
        return StaticLabeledEnumResolver.instance().getLabeledEnumByCode(getType(), getCode());
    }

}
