package com.operit.fullaod.ui;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * AGSL Liquid Glass Refraction, Dispersion & Rim Highlight Shaders
 * Adapted from Kyant0 / AndroidLiquidGlass (Apache-2.0 License).
 */
public final class LiquidGlassShaders {

    private static final String ROUNDED_RECT_SDF =
            "float2 safeNormalize(float2 v) {\n" +
            "    float len = length(v);\n" +
            "    return len > 0.0001 ? (v / len) : float2(0.0, 0.0);\n" +
            "}\n" +
            "float radiusAt(float2 centeredCoord, float4 radii) {\n" +
            "    if (centeredCoord.x >= 0.0) {\n" +
            "        if (centeredCoord.y <= 0.0) return radii.y;\n" +
            "        else return radii.z;\n" +
            "    } else {\n" +
            "        if (centeredCoord.y <= 0.0) return radii.x;\n" +
            "        else return radii.w;\n" +
            "    }\n" +
            "}\n" +
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    float outside = length(max(cornerCoord, 0.0)) - radius;\n" +
            "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n" +
            "    return outside + inside;\n" +
            "}\n" +
            "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    if (cornerCoord.x > 0.0 || cornerCoord.y > 0.0) {\n" +
            "        return sign(coord) * safeNormalize(max(cornerCoord, 0.0));\n" +
            "    } else {\n" +
            "        float gradX = step(cornerCoord.y, cornerCoord.x);\n" +
            "        return sign(coord) * float2(gradX, 1.0 - gradX);\n" +
            "    }\n" +
            "}\n";

    public static final String REFRACTION_WITH_DISPERSION_AGSL =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "uniform float chromaticAberration;\n" +
            ROUNDED_RECT_SDF +
            "float circleMap(float x) {\n" +
            "    float cx = clamp(x, 0.0, 1.0);\n" +
            "    return 1.0 - sqrt(max(0.0, 1.0 - cx * cx));\n" +
            "}\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = max(size * 0.5, float2(1.0, 1.0));\n" +
            "    float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "    float radius = radiusAt(centeredCoord, cornerRadii);\n" +
            "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "    float safeHeight = max(refractionHeight, 1.0);\n" +
            "    if (-sd >= safeHeight) {\n" +
            "        return content.eval(coord);\n" +
            "    }\n" +
            "    sd = min(sd, 0.0);\n" +
            "    float d = circleMap(1.0 - -sd / safeHeight) * refractionAmount;\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = safeNormalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * safeNormalize(centeredCoord));\n" +
            "    float2 refractedCoord = coord + d * grad;\n" +
            "    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n" +
            "    float2 dispersedCoord = d * grad * dispersionIntensity;\n" +
            "    half4 color = half4(0.0);\n" +
            "    half4 red = content.eval(refractedCoord + dispersedCoord);\n" +
            "    color.r += red.r / 3.5;\n" +
            "    color.a += red.a / 7.0;\n" +
            "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n" +
            "    color.r += orange.r / 3.5;\n" +
            "    color.g += orange.g / 7.0;\n" +
            "    color.a += orange.a / 7.0;\n" +
            "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n" +
            "    color.r += yellow.r / 3.5;\n" +
            "    color.g += yellow.g / 3.5;\n" +
            "    color.a += yellow.a / 7.0;\n" +
            "    half4 green = content.eval(refractedCoord);\n" +
            "    color.g += green.g / 3.5;\n" +
            "    color.a += green.a / 7.0;\n" +
            "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n" +
            "    color.g += cyan.g / 3.5;\n" +
            "    color.b += cyan.b / 3.0;\n" +
            "    color.a += cyan.a / 7.0;\n" +
            "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n" +
            "    color.b += blue.b / 3.0;\n" +
            "    color.a += blue.a / 7.0;\n" +
            "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n" +
            "    color.r += purple.r / 7.0;\n" +
            "    color.b += purple.b / 3.0;\n" +
            "    color.a += purple.a / 7.0;\n" +
            "    return color;\n" +
            "}\n";

    public static final String HIGHLIGHT_RIM_AGSL =
            "uniform float2 size;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float4 highlightColor;\n" +
            "uniform float angle;\n" +
            "uniform float falloff;\n" +
            ROUNDED_RECT_SDF +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = max(size * 0.5, float2(1.0, 1.0));\n" +
            "    float2 centeredCoord = coord - halfSize;\n" +
            "    float radius = radiusAt(centeredCoord, cornerRadii);\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n" +
            "    float2 normal = float2(cos(angle), sin(angle));\n" +
            "    float d = dot(grad, normal);\n" +
            "    float intensity = pow(clamp(abs(d), 0.0, 1.0), falloff);\n" +
            "    return half4(highlightColor.rgb * intensity, highlightColor.a * intensity);\n" +
            "}\n";

    private LiquidGlassShaders() {
    }

    public static RenderEffect createLiquidGlassEffect(
            RuntimeShader shader,
            float width,
            float height,
            float cornerRadius,
            float refractionHeight,
            float refractionAmount,
            boolean enableBlur,
            float blurRadius,
            boolean enableLiquidRefraction) {
        if (Build.VERSION.SDK_INT < 31 || width <= 0 || height <= 0) {
            return null;
        }
        try {
            RenderEffect baseEffect = null;
            if (enableBlur && blurRadius > 0.5f) {
                baseEffect = RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP);
            }

            // Vibrancy saturation boost (Kyant0 Backdrop vibrancy)
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(enableLiquidRefraction ? 1.42f : 1.20f);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(cm);
            if (baseEffect != null) {
                baseEffect = RenderEffect.createColorFilterEffect(filter, baseEffect);
            } else {
                baseEffect = RenderEffect.createColorFilterEffect(filter);
            }

            if (enableLiquidRefraction && Build.VERSION.SDK_INT >= 33 && shader != null) {
                float r = Math.min(cornerRadius, Math.min(width, height) * 0.5f);
                shader.setFloatUniform("size", width, height);
                shader.setFloatUniform("offset", 0f, 0f);
                shader.setFloatUniform("cornerRadii", r, r, r, r);
                shader.setFloatUniform("refractionHeight", refractionHeight);
                shader.setFloatUniform("refractionAmount", -refractionAmount);
                shader.setFloatUniform("depthEffect", 0.35f);
                shader.setFloatUniform("chromaticAberration", 0.90f);

                RenderEffect lensEffect = RenderEffect.createRuntimeShaderEffect(shader, "content");
                return RenderEffect.createChainEffect(lensEffect, baseEffect);
            }
            return baseEffect;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void updateHighlightShader(
            RuntimeShader highlightShader,
            float width,
            float height,
            float cornerRadius,
            boolean isDark) {
        if (Build.VERSION.SDK_INT < 33 || highlightShader == null || width <= 0 || height <= 0) {
            return;
        }
        try {
            float r = Math.min(cornerRadius, Math.min(width, height) * 0.5f);
            highlightShader.setFloatUniform("size", width, height);
            highlightShader.setFloatUniform("cornerRadii", r, r, r, r);
            if (isDark) {
                highlightShader.setFloatUniform("highlightColor", 1.0f, 1.0f, 1.0f, 0.58f);
            } else {
                highlightShader.setFloatUniform("highlightColor", 1.0f, 1.0f, 1.0f, 0.82f);
            }
            highlightShader.setFloatUniform("angle", (float) (42.0 * Math.PI / 180.0));
            highlightShader.setFloatUniform("falloff", 1.25f);
        } catch (Throwable ignored) {
        }
    }
}
