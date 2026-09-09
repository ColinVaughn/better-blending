package dev.betterblending.backend;

import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;

/** {@link TerrainProgram} over a pre-1.21.5 {@code ShaderInstance} and its loose uniforms. */
public final class VanillaProgram implements TerrainProgram {
    private final ShaderInstance instance;

    public VanillaProgram(ShaderInstance instance) {
        this.instance = instance;
    }

    /** The wrapped shader, for the mixins that must hand one back to vanilla. */
    public static @Nullable ShaderInstance unwrap(@Nullable TerrainProgram program) {
        return program == null ? null : ((VanillaProgram) program).instance;
    }

    @Override
    public void sampler(String name, TerrainTexture texture) {
        instance.setSampler(name, VanillaTexture.glId(texture));
    }

    @Override
    public void uniform(String name, float... values) {
        var uniform = instance.safeGetUniform(name);
        switch (values.length) {
            case 1 -> uniform.set(values[0]);
            case 2 -> uniform.set(values[0], values[1]);
            case 3 -> uniform.set(values[0], values[1], values[2]);
            case 4 -> uniform.set(values[0], values[1], values[2], values[3]);
            default -> throw new IllegalArgumentException("Unsupported uniform arity " + values.length);
        }
    }

    @Override
    public float @Nullable [] uniformValues(String name) {
        var uniform = instance.getUniform(name);
        if (uniform == null) return null;
        var buffer = uniform.getFloatBuffer();
        var values = new float[uniform.getCount()];
        for (int i = 0; i < values.length; i++) values[i] = buffer.get(i);
        return values;
    }
}
