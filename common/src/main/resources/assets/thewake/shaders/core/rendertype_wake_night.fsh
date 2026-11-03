#version 150

// The Wake's silhouette: nothing but night sky, unlit, unfogged.

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

// Direction -> a cube face and a point on it. Stars live on the faces, so every star sits on the sky.
vec2 cubeFace(vec3 d, out float face) {
    vec3 a = abs(d);
    if (a.x >= a.y && a.x >= a.z) {
        face = d.x > 0.0 ? 0.0 : 1.0;
        return d.yz / a.x;
    }
    if (a.y >= a.z) {
        face = d.y > 0.0 ? 2.0 : 3.0;
        return d.xz / a.y;
    }
    face = d.z > 0.0 ? 4.0 : 5.0;
    return d.xy / a.z;
}

// One layer of stars fixed to world directions: a window onto a night sky behind the world.
// Star size never drops below about a pixel, so the field reads at any resolution.
float starLayer(vec3 dir, float scale, float density, float size, float speed) {
    float face;
    vec2 uv = cubeFace(dir, face) * scale;
    float pixel = clamp(length(fwidth(uv)), 0.0001, 0.45);
    float radius = max(size, pixel * 1.35);
    float dim = clamp(size / radius * 1.6, 0.4, 1.0);
    vec2 cell = floor(uv);
    float total = 0.0;
    for (int ox = -1; ox <= 1; ox++) {
        for (int oy = -1; oy <= 1; oy++) {
            vec2 c = cell + vec2(float(ox), float(oy));
            vec3 key = vec3(c, face * 131.0 + scale);
            float h = hash(key);
            if (h > density) {
                continue;
            }
            vec2 center = c + 0.5 + (vec2(hash(key + 13.1), hash(key + 27.7)) - 0.5) * 0.8;
            float g = smoothstep(radius, 0.0, length(uv - center));
            float twinkle = 0.65 + 0.35 * sin(WakeTime * speed * (0.6 + h * 4.0) + h * 91.0);
            total += g * g * twinkle * mix(0.55, 1.0, hash(key + 5.5)) * dim;
        }
    }
    return total;
}

vec3 nightSky(vec3 dir, float lift) {
    float up = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 sky = mix(vec3(0.014, 0.019, 0.045), vec3(0.040, 0.058, 0.130), up) + vec3(lift * 0.55, lift * 0.75, lift);
    float s = starLayer(dir, 34.0, 0.16, 0.085, 1.1)
            + starLayer(dir, 80.0, 0.12, 0.06, 1.7) * 0.8
            + starLayer(dir, 170.0, 0.09, 0.05, 2.3) * 0.6;
    return sky + vec3(0.92, 0.95, 1.0) * min(s, 1.4);
}

void main() {
    float alpha = vertexColor.a * ColorModulator.a;
    if (alpha <= 0.003) {
        discard;
    }
    fragColor = vec4(nightSky(normalize(viewPos), 0.0), alpha);
}
