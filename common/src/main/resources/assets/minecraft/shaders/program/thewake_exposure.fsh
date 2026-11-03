#version 150

// The Wake: an old photograph. Desaturated, warm, grained, vignetted, by Amount (0..1).
// Lives in the minecraft namespace with a prefixed name because vanilla post passes cannot
// load programs from other namespaces.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Amount;
uniform float Seed;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec3 color = texture(DiffuseSampler, texCoord).rgb;
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    vec3 toned = vec3(luma) * vec3(1.06, 0.97, 0.84);
    toned = mix(vec3(0.06, 0.05, 0.04), toned, 0.92);
    float vignette = smoothstep(0.92, 0.28, length(texCoord - 0.5));
    toned *= mix(1.0, vignette, 0.65);
    float grain = rand(floor(texCoord * InSize) + Seed * 61.0) - 0.5;
    toned += grain * 0.07;
    fragColor = vec4(mix(color, toned, clamp(Amount, 0.0, 1.0)), 1.0);
}
