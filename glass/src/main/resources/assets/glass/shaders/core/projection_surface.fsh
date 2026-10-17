#version 150

#moj_import <matrix.glsl>

uniform sampler2D Projection0;
uniform sampler2D Projection1;
uniform sampler2D Projection2;
uniform sampler2D Projection3;
uniform sampler2D PortalSky;
uniform sampler2D PortalLayers;
uniform vec3 ProjectorPosition0;
uniform vec3 ProjectorPosition1;
uniform vec3 ProjectorPosition2;
uniform vec3 ProjectorPosition3;
uniform vec4 StaticAmounts;
uniform vec4 RevealProgress;
uniform vec4 ProjectionChannels;
uniform float LoadingOpacity;
uniform float CrossfadeWidth;
uniform float GameTime;

noperspective in vec2 screenUv;
in vec3 surfacePosition;
flat in vec4 revealDistances;
flat in vec4 projectionMask;

out vec4 fragColor;

vec3 projectionSample(int index) {
    if (index == 0) {
        return texture(Projection0, screenUv).rgb;
    }
    if (index == 1) {
        return texture(Projection1, screenUv).rgb;
    }
    if (index == 2) {
        return texture(Projection2, screenUv).rgb;
    }
    return texture(Projection3, screenUv).rgb;
}

float staticHash(vec2 cell, float frame) {
    uvec3 v = uvec3(ivec3(int(cell.x), int(cell.y), int(frame))) * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    return float(v.x & 65535u) / 65535.0;
}

const int PORTAL_LAYERS = 15;
const vec3[] PORTAL_COLORS = vec3[](
    vec3(0.022087, 0.098399, 0.110818),
    vec3(0.011892, 0.095924, 0.089485),
    vec3(0.027636, 0.101689, 0.100326),
    vec3(0.046564, 0.109883, 0.114838),
    vec3(0.064901, 0.117696, 0.097189),
    vec3(0.063761, 0.086895, 0.123646),
    vec3(0.084817, 0.111994, 0.166380),
    vec3(0.097489, 0.154120, 0.091064),
    vec3(0.106152, 0.131144, 0.195191),
    vec3(0.097721, 0.110188, 0.187229),
    vec3(0.133516, 0.138278, 0.148582),
    vec3(0.070006, 0.243332, 0.235792),
    vec3(0.196766, 0.142899, 0.214696),
    vec3(0.047281, 0.315338, 0.321970),
    vec3(0.204675, 0.390010, 0.302066),
    vec3(0.080955, 0.314821, 0.661491)
);

const mat4 PORTAL_SCALE_TRANSLATE = mat4(
    0.5, 0.0, 0.0, 0.25,
    0.0, 0.5, 0.0, 0.25,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
);

mat4 portalLayer(float layer) {
    mat4 translate = mat4(
        1.0, 0.0, 0.0, 17.0 / layer,
        0.0, 1.0, 0.0, (2.0 + layer / 1.5) * (GameTime * 1.5),
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    );
    mat2 rotate = mat2_rotate_z(radians((layer * layer * 4321.0 + layer * 9.0) * 2.0));
    mat2 scale = mat2((4.5 - layer / 4.0) * 2.0);
    return mat4(scale * rotate) * translate * PORTAL_SCALE_TRANSLATE;
}

vec3 staticNoise() {
    float time = GameTime * 1200.0;
    float frame = floor(time * 24.0);
    vec2 pixel = gl_FragCoord.xy;
    float row = floor(pixel.y / 3.0);
    float tear = staticHash(vec2(0.0, row), frame) > 0.94 ? 0.04 : 0.004;
    vec2 uv = screenUv + vec2((staticHash(vec2(1.0, row), frame) - 0.5) * tear, 0.0);
    vec3 color = texture(PortalSky, uv).rgb * PORTAL_COLORS[0];
    for (int i = 0; i < PORTAL_LAYERS; i++) {
        vec4 layerUv = vec4(uv, 0.0, 1.0) * portalLayer(float(i + 1));
        color += texture(PortalLayers, layerUv.xy / layerUv.w).rgb * PORTAL_COLORS[i];
    }
    float grain = staticHash(floor(pixel / 2.0), frame);
    float scanline = 0.75 + 0.25 * sin(pixel.y * 1.5);
    float band = 1.0 - smoothstep(0.0, 0.08, abs(fract(pixel.y / 720.0 - time * 0.25) - 0.5));
    color = color * scanline * (0.6 + 0.8 * grain) + vec3(0.05, 0.16, 0.18) * (band * 0.6 + grain * 0.08);
    return clamp(color, 0.0, 1.0);
}

vec3 projectionColor(int index) {
    vec3 projection = projectionSample(index);
    float staticAmount = StaticAmounts[index];
    return staticAmount > 0.0 ? mix(projection, staticNoise(), staticAmount) : projection;
}

vec4 projectedColor(vec4 visible) {
    vec3 positions[4] = vec3[4](ProjectorPosition0, ProjectorPosition1, ProjectorPosition2, ProjectorPosition3);
    vec4 selected = visible;
    vec4 whiteOpacity = clamp(vec4(1.0) - (RevealProgress - revealDistances) * 0.5, 0.0, 1.0);
    for (int index = 0; index < 4; index++) {
        if (visible[index] == 0.0) {
            continue;
        }
        float distance = length(positions[index] - surfacePosition);
        for (int other = 0; other < 4; other++) {
            if (other == index || visible[other] == 0.0 || ProjectionChannels[other] != ProjectionChannels[index]) {
                continue;
            }
            whiteOpacity[index] = min(whiteOpacity[index], whiteOpacity[other]);
            float otherDistance = length(positions[other] - surfacePosition);
            if (otherDistance < distance || (otherDistance == distance && other < index)) {
                selected[index] = 0.0;
            }
        }
    }
    vec3 color = vec3(0.0);
    float totalWeight = 0.0;
    for (int index = 0; index < 4; index++) {
        if (selected[index] == 0.0) {
            continue;
        }
        float weight = 1.0;
        for (int other = 0; other < 4; other++) {
            if (other == index || selected[other] == 0.0) {
                continue;
            }
            vec3 separation = positions[other] - positions[index];
            float separationLength = length(separation);
            float midpointDistance = separationLength > 0.0001
                    ? dot((positions[index] + positions[other]) * 0.5 - surfacePosition, separation / separationLength)
                    : 0.0;
            weight *= smoothstep(-CrossfadeWidth * 0.5, CrossfadeWidth * 0.5, midpointDistance);
        }
        if (weight > 0.0) {
            color += mix(projectionColor(index), vec3(1.0), whiteOpacity[index]) * weight;
            totalWeight += weight;
        }
    }
    return vec4(color / max(totalWeight, 0.0001), 1.0);
}

void main() {
    vec4 visible = projectionMask * step(revealDistances, RevealProgress);
    bool anyVisible = dot(visible, vec4(1.0)) > 0.0;
    if (LoadingOpacity >= 0.0) {
        if (anyVisible) {
            discard;
        }
        fragColor = vec4(1.0, 1.0, 1.0, LoadingOpacity);
    } else {
        if (!anyVisible) {
            discard;
        }
        fragColor = projectedColor(visible);
    }
}
