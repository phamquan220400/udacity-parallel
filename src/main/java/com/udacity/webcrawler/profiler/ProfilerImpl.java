package com.udacity.webcrawler.profiler;

import javax.inject.Inject;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;

/**
 * Concrete implementation of the {@link Profiler}.
 */
final class ProfilerImpl implements Profiler {

    private final Clock clock;
    private final ProfilingState state = new ProfilingState();
    private final ZonedDateTime startTime;

    @Inject
    ProfilerImpl(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        this.startTime = ZonedDateTime.now(clock);
    }

    private Boolean profiledClass(Class<?> classInstance) {
        List<Method> classMethodsList = new ArrayList<>(Arrays.asList(classInstance.getDeclaredMethods()));
        boolean result = false;
        if (classMethodsList.isEmpty()) {
            return false;
        }
        for (Method method : classMethodsList) {
            if (method.getAnnotation(Profiled.class) != null) {
                result = true;
                break;
            }
        }
        return result;
    }

    @Override
    public <T> T wrap(Class<T> classInstance, T delegate) {
        Objects.requireNonNull(classInstance);
        if (!profiledClass(classInstance)) {
            throw new IllegalArgumentException("Profiled method does not exist in " + classInstance.getName());
        }
        ProfilingMethodInterceptor interceptor = new ProfilingMethodInterceptor(clock, delegate, state, startTime);

        Object proxy = Proxy.newProxyInstance(
                ProfilerImpl.class.getClassLoader(),
                new Class[]{classInstance},
                interceptor
        );

        return (T) proxy;
    }

    @Override
    public void writeData(Path path) {
        Objects.requireNonNull(path);
        Writer writer = null;
        try {
            writer = Files.newBufferedWriter(path);
            writeData(writer);
            writer.flush();
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    @Override
    public void writeData(Writer writer) throws IOException {
        writer.write("Run at " + RFC_1123_DATE_TIME.format(startTime));
        writer.write(System.lineSeparator());
        state.write(writer);
        writer.write(System.lineSeparator());
    }
}
