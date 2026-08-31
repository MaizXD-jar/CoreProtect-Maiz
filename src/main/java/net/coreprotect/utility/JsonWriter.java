package net.coreprotect.utility;

import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON writer for flat/lightly-nested records (Map/List/String/Number/Boolean/null).
 * org.json.simple is only on the classpath when WorldEdit/FAWE is installed, so it isn't safe to use here.
 */
public final class JsonWriter {

    private JsonWriter() {
        throw new IllegalStateException("Utility class");
    }

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        }
        else if (value instanceof Map) {
            writeObject(builder, (Map<?, ?>) value);
        }
        else if (value instanceof List) {
            writeArray(builder, (List<?>) value);
        }
        else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value.toString());
        }
        else {
            writeString(builder, value.toString());
        }
    }

    private static void writeObject(StringBuilder builder, Map<?, ?> map) {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeString(builder, String.valueOf(entry.getKey()));
            builder.append(':');
            writeValue(builder, entry.getValue());
        }
        builder.append('}');
    }

    private static void writeArray(StringBuilder builder, List<?> list) {
        builder.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeValue(builder, item);
        }
        builder.append(']');
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        builder.append(c);
                    }
            }
        }
        builder.append('"');
    }
}
