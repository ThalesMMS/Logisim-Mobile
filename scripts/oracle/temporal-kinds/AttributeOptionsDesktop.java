package com.cburch.logisim.data;

import com.cburch.logisim.std.Builtin;
import com.cburch.logisim.tools.AddTool;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Prints the finite attribute values of every built-in desktop component as TSV. */
public final class AttributeOptionsDesktop {
    private static final int[] WIDTHS = {1, 2, 3, 8, 16, 31, 32, 64};

    public static void main(String[] args) throws Exception {
        for (var library : new Builtin().getLibraries()) {
            for (var tool : library.getTools()) {
                if (!(tool instanceof AddTool add)) continue;
                var factory = add.getFactory();
                var attrs = factory.createAttributeSet();
                for (var attribute : attrs.getAttributes()) {
                    var values = values(attribute);
                    if (values.isEmpty()) continue;
                    var standard = new ArrayList<String>();
                    for (var value : values) standard.add(((Attribute<Object>) attribute).toStandardString(value));
                    System.out.println("#" + library.getName() + "\t" + factory.getName() + "\t" + attribute.getName()
                            + "\t" + String.join("|", standard));
                }
            }
        }
    }

    private static List<Object> values(Attribute<?> attribute) throws Exception {
        var result = new ArrayList<Object>();
        Class<?> type = attribute.getClass();
        if (type.getSimpleName().equals("OptionAttribute") || type.getSuperclass().getSimpleName().equals("OptionAttribute")) {
            Class<?> option = type.getSimpleName().equals("OptionAttribute") ? type : type.getSuperclass();
            Field field = option.getDeclaredField("vals");
            field.setAccessible(true);
            for (var value : (Object[]) field.get(attribute)) result.add(value);
        } else if (attribute instanceof BitWidth.Attribute) {
            int min = intField(BitWidth.Attribute.class, attribute, "min");
            int max = intField(BitWidth.Attribute.class, attribute, "max");
            for (int width : WIDTHS) if (width >= min && width <= max) result.add(BitWidth.create(width));
        } else if (type.getSimpleName().equals("IntegerRangeAttribute")) {
            int start = intField(type, attribute, "start");
            int end = intField(type, attribute, "end");
            for (int value : new int[] {start, start + 1, (start + end) / 2, end}) if (!result.contains(value)) result.add(value);
        }
        return result;
    }

    private static int intField(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }
}
