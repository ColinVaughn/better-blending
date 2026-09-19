/*
 Copyright (c) 2026 Colin Vaughn

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.
 */

package dev.betterblending;

import org.lwjgl.opengl.GL33C;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

import static org.lwjgl.opengl.GL32C.*;

/* Opt-in GPU timers, excluding CPU submission/readback and VSync. */
final class ShaderBenchmark {
    private ShaderBenchmark() { }

    static double[] measure(String name, Runnable baseline, Runnable enabled) throws Exception {
        return measure(name, baseline, enabled, 200);
    }

    /* Returns the baseline and enabled medians in milliseconds, the median paired ratio and the median paired difference. */
    static double[] measure(String name, Runnable baseline, Runnable enabled, int samples) throws Exception {
        int query = glGenQueries();
        for (int i = 0; i < samples / 2; i++) { baseline.run(); enabled.run(); }
        glFinish();
        double[][] times = new double[2][samples];
        for (int i = 0; i < samples; i++) {
            for (int j = 0; j < 2; j++) {
                int mode = (i + j) & 1;
                glBeginQuery(GL33C.GL_TIME_ELAPSED, query);
                (mode == 0 ? baseline : enabled).run();
                glEndQuery(GL33C.GL_TIME_ELAPSED);
                times[mode][i] = GL33C.glGetQueryObjectui64(query, GL_QUERY_RESULT) / 1_000_000.0;
            }
        }
        glDeleteQueries(query);
        // Each pair runs back to back, so per-pair ratios and differences cancel clock drift
        // and thermal throttling that shift both medians between runs.
        double[] ratios = new double[samples], differences = new double[samples];
        for (int i = 0; i < samples; i++) {
            ratios[i] = times[1][i] / times[0][i];
            differences[i] = times[1][i] - times[0][i];
        }
        Arrays.sort(ratios);
        Arrays.sort(differences);
        for (var series : times) Arrays.sort(series);
        String row = String.format(Locale.ROOT, "%s,%.4f,%.4f,%.4f,%.4f,%.4f%n", name,
                times[0][samples / 2], times[0][samples * 95 / 100], times[1][samples / 2], times[1][samples * 95 / 100], times[1][samples / 2] - times[0][samples / 2]);
        Path path = Path.of("build/shader-review/benchmark.csv");
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) Files.writeString(path, "# GPU: " + glGetString(GL_RENDERER) + "; OpenGL: " + glGetString(GL_VERSION)
                + "\ncase,baseline_median_ms,baseline_p95_ms,enabled_median_ms,enabled_p95_ms,median_delta_ms\n");
        Files.writeString(path, row, StandardOpenOption.APPEND);
        System.out.print(row);
        return new double[]{times[0][samples / 2], times[1][samples / 2], ratios[samples / 2], differences[samples / 2]};
    }
}
