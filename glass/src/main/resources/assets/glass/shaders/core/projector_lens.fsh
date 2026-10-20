#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform float GameTime;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in vec2 lensUv;
in vec2 shimmerUv;
in float vertexDistance;

out vec4 fragColor;

void main() {
    vec2 edge = min(lensUv, 1.0 - lensUv);
    vec2 fade = smoothstep(vec2(0.0), vec2(0.3), edge);
    float mask = fade.x * fade.y;
    vec2 center = (lensUv - 0.5) * 2.0;
    float glow = exp(-3.5 * dot(center, center));
    float pulse = 0.9 + 0.1 * sin(GameTime * 1507.964474);
    vec3 shimmer = texture(Sampler0, shimmerUv).rgb;
    float brightness = max(shimmer.r, max(shimmer.g, shimmer.b));
    float alpha = mask * (brightness * 0.3 + glow * pulse * 0.16);
    vec3 color = mix(vec3(0.38, 0.16, 0.72), vec3(0.65, 0.44, 0.95), glow * 0.65);
    fragColor = linear_fog(vec4(color, alpha), vertexDistance, FogStart, FogEnd, FogColor);
}
