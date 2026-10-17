#version 150

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 TextureMat;

out vec2 lensUv;
out vec2 shimmerUv;
out float vertexDistance;

void main() {
    vec4 position = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * position;
    vertexDistance = length(position.xyz);
    lensUv = UV0;
    shimmerUv = (TextureMat * vec4(UV0 / 64.0, 0.0, 1.0)).xy;
}
