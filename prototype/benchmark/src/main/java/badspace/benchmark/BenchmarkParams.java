package badspace.benchmark;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.openjdk.jmh.annotations.Param;

/**
 * The parameters of the benchmarks, found in the {@code @Param} fields of the
 * benchmark states. It checks names and values before a run, because JMH
 * skips an unknown name without errors and finds a bad value only when the
 * benchmark that uses it starts, which can be hours later.
 */
final class BenchmarkParams {

    /** For each parameter name, the type of its field. */
    private static final Map<String, Class<?>> TYPES = types();

    private BenchmarkParams() {
    }

    /** Fails with IllegalArgumentException if the parameter does not exist or a value is not valid. */
    static void check(String name, List<String> values) {
        Class<?> type = TYPES.get(name);
        if (type == null) {
            throw new IllegalArgumentException("Unknown parameter: " + name + " (parameters: "
                    + String.join(", ", TYPES.keySet()) + ")");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("No values for parameter: " + name);
        }
        for (String value : values) {
            try {
                checkValue(name, type, value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Bad value for parameter " + name + ": " + value, e);
            }
        }
    }

    private static void checkValue(String name, Class<?> type, String value) {
        if (name.equals("index")) {
            IndexNames.parse(value);
        } else if (name.equals("step")) {
            double step = Double.parseDouble(value);
            if (!(step > 0 && Double.isFinite(step))) {
                throw new IllegalArgumentException("The step must be a positive number");
            }
        } else if (type == int.class) {
            Integer.parseInt(value);
        } else if (type == double.class) {
            Double.parseDouble(value);
        } else if (type.isEnum()) {
            Enum.valueOf(type.asSubclass(Enum.class), value);
        }
    }

    private static Map<String, Class<?>> types() {
        List<Class<?>> states = new ArrayList<>(List.of(BuildBenchmark.class.getDeclaredClasses()));
        states.addAll(List.of(PartitionBenchmark.class.getDeclaredClasses()));
        Map<String, Class<?>> types = new TreeMap<>();
        for (Class<?> state : states) {
            for (Class<?> c = state; c != null; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Param.class)) {
                        types.put(field.getName(), field.getType());
                    }
                }
            }
        }
        return types;
    }
}
