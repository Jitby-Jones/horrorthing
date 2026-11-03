#version 150

// Ribbons of night lying on the ground in the Stopped: soft-edged patches that merge where you walked.

uniform vec4 ColorModulator;
uniform float WakeTime;

in vec4 vertexColor;
in vec3 viewPos;
in vec2 texCoord0;

out vec4 fragColor;

float hash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

// One layer of stars fixed to world directions: a window onto a night sky behind the world.
float starLayer(vec3 dir, float scale, float density, float size, float speed) {
    vec3 p = dir * scale;
    vec3 cell = floor(p);
    vec3 local = fract(p) - 0.5;
    float h = hash(cell);
    if (h > density) {
        return 0.0;
    }
    vec3 jitter = vec3(hash(cell + 13.1), hash(cell + 27.7), hash(cell + 41.3)) - 0.5;
    float d = length(local - jitter * 0.7);
    float twinkle = 0.7 + 0.3 * sin(WakeTime * speed * (0.6 + h * 4.0) + h * 91.0);
    return smoothstep(size, size * 0.15, d) * twinkle;
}

vec3 nightSky(vec3 dir, float lift) {
    float up = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 sky = mix(vec3(0.010, 0.013, 0.030), vec3(0.030, 0.045, 0.100), up) + vec3(lift * 0.6, lift * 0.8, lift);
    float s = starLayer(dir, 70.0, 0.08, 0.20, 1.1)
            + starLayer(dir, 150.0, 0.06, 0.16, 1.7) * 0.75
            + starLayer(dir, 320.0, 0.05, 0.14, 2.3) * 0.5;
    return sky + vec3(0.90, 0.93, 1.0) * s;
}

void main() {
    vec2 c = texCoord0 - 0.5;
    float edge = smoothstep(1.0, 0.2, length(c) * 2.0);
    float shimmer = 0.86 + 0.14 * sin(WakeTime * 0.9 + (viewPos.x + viewPos.z) * 0.35);
    float alpha = vertexColor.a * ColorModulator.a * edge * shimmer;
    if (alpha <= 0.003) {
        discard;
    }
    fragColor = vec4(nightSky(normalize(viewPos), 0.012), alpha);
}
