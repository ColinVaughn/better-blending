package dev.betterblending.compat;

import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.declaration.FunctionParameter;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import java.util.Set;
import java.util.regex.Pattern;

/**
 Blend atlas samples before the pack applies its own lighting and writes its G-buffer.
 Shared by every era: Iris names Sodium's vertex decoding the same way throughout, and
 the glsl-transformer calls used here are unchanged from 2.0 to 3.0.
 */
public final class IrisShaders {
    private static final Pattern REGION_OFFSET = Pattern.compile("\\biris_RegionOffset\\b");

    private IrisShaders() {}

    public static String vertex(String source) {
        // Iris 1.11.6 moved Sodium's region offset into a block of its own, under its own name.
        String regionOffset = REGION_OFFSET.matcher(source).find() ? "iris_RegionOffset" : "u_RegionOffset";
        var parser = new ASTParser();
        var tree = parser.parseTranslationUnit(RootSupplier.DEFAULT, source);
        tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "uniform vec3 bb_BiomeOffset;", "out vec3 bb_biomePosition;");
        // Wrap main so early returns cannot leave our varying undefined. Use the original,
        // unpacked block position; waving foliage must not move the material lookup.
        tree.getRoot().rename("main", "bb_pack_main");
        tree.parseAndInjectNode(parser, ASTInjectionPoint.END, """
                void main() {
                    bb_pack_main();
                    bb_biomePosition = _vert_position + _get_draw_translation(_draw_id)
                            + round(%s + bb_BiomeOffset);
                }
                """.formatted(regionOffset));
        return ASTPrinter.printSimple(tree);
    }

    public static String fragment(String source) {
        var parser = new ASTParser();
        var tree = parser.parseTranslationUnit(RootSupplier.DEFAULT, source);
        var root = tree.getRoot();
        boolean changed = false;
        for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
            if (call.getFunctionName() == null || call.getParameters().size() < 2
                    || !(call.getParameters().get(0) instanceof ReferenceExpression sampler)
                    || !Set.of("gtexture", "tex").contains(sampler.getIdentifier().getName())) continue;
            var scope = call.getAncestor(FunctionDefinition.class);
            // A generic sampling helper's parameter named "tex" can receive normal or
            // shadow maps too. Only atlas uniforms are safe to intercept here.
            if (root.nodeIndex.getStream(FunctionParameter.class).anyMatch(parameter -> parameter.getName() != null
                    && parameter.getName().getName().equals(sampler.getIdentifier().getName())
                    && parameter.getAncestor(FunctionDefinition.class) == scope)) continue;
            String function = call.getFunctionName().getName();
            if (!Set.of("texture", "textureLod", "textureGrad").contains(function)) continue;
            String uv = ASTPrinter.printSimple(call.getParameters().get(1));
            String sample = ASTPrinter.printSimple(call);
            call.replaceByAndDelete(parser.parseExpression(root, "bb_blend(" + uv + ", " + sample + ")"));
            changed = true;
        }
        if (!changed) return source;
        var module = parser.parseTranslationUnit(RootSupplier.DEFAULT, albedoModule());
        tree.injectNodes(ASTInjectionPoint.BEFORE_DECLARATIONS,
                module.getChildren().stream().map(node -> node.cloneInto(root)));
        return ASTPrinter.printSimple(tree);
    }

    static String albedoModule() {
        // The shared core declares only what blending owns, so supply the host bindings
        // it expects. The namespacing pass below turns these into bb_Sampler0 and
        // bb_AlphaCutoff, which is exactly what IrisSamplersMixin binds and sets.
        String prologue = """
                uniform sampler2D Sampler0;
                uniform float AlphaCutoff;
                vec3 faceNormal;
                """;
        String terrain = prologue + ShaderSources.uniforms() + ShaderSources.core()
                // Iris links pack programs itself, so biomePosition needs no location; the
                // parser would drop the directives that choose one and keep both declarations.
                .replace("""
                        #ifdef BB_BIOME_POSITION_LOCATION
                        layout(location = BB_BIOME_POSITION_LOCATION) in vec3 biomePosition;
                        #else
                        in vec3 biomePosition;
                        #endif
                        """, "in vec3 biomePosition;\n")
                // The same goes for the host sampling defaults: packs sample the atlas plainly.
                .replace("""
                        #ifndef BB_RGSS
                        #define BB_RGSS false
                        #endif
                        #ifndef BB_LOD_BIAS
                        #define BB_LOD_BIAS 0.0
                        #endif
                        """, "")
                .replace("BB_RGSS", "false").replace("BB_LOD_BIAS", "0.0")
                .replace("void bb_terrain_main() {\n    vec4 source = BB_SAMPLE_BASE(texCoord0);",
                        "vec4 blend(vec2 texCoord0, vec4 source) {")
                .replace("    if (source.a * vertexColor.a * ColorModulator.a < AlphaCutoff) discard;", "")
                .replace("base.a * vertexColor.a * ColorModulator.a >= max(AlphaCutoff, 0.0001)", "base.a >= max(AlphaCutoff, 0.0001)");
        terrain = terrain.substring(0, terrain.indexOf("    vec4 color = albedo * vertexColor")) + "    return albedo;\n}\n";
        terrain = terrain.replace("vec4 blend(vec2 texCoord0, vec4 source) {", """
                uniform int Enabled;
                vec4 blend(vec2 texCoord0, vec4 source) {
                    vec3 normal = normalize(cross(dFdx(biomePosition), dFdy(biomePosition)));
                    if (Enabled == 0 || max(abs(normal.x), max(abs(normal.y), abs(normal.z))) < 0.9999) return source;
                    faceNormal = round(normal);
                """);
        // Namespace our own top-level declarations, including helper functions. Locals
        // remain scoped to those functions; pack identifiers are never rewritten.
        var declarations = Pattern.compile("(?m)^(?:(?:uniform|in|out|flat) )*(?:void|float|int|bool|[biu]?vec[234]|sampler2D) (\\w+)").matcher(terrain);
        var names = new java.util.LinkedHashSet<String>();
        while (declarations.find()) names.add(declarations.group(1));
        for (String name : names) terrain = terrain.replaceAll("\\b" + name + "\\b", "bb_" + name);
        return terrain;
    }
}
