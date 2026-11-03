#version 150

// The Stopped: everywhere you ever walked, burned into the picture as static, the way a paused tape
// wears through where it has been played too often. Soft-edged patches that merge along your routes.

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

void main() {
    vec2 c = texCoord0 - 0.5;
    float edge = smoothstep(1.0, 0.2, length(c) * 2.0);
    float alpha = vertexColor.a * ColorModulator.a * edge;
    if (alpha <= 0.003) {
        discard;
    }
    // Snow is a property of the screen, not of the ground: it does not move with the world.
    float frame = floor(WakeTime * 24.0);
    float snow = hash(vec3(floor(gl_FragCoord.xy / 2.0), frame));
    float line = hash(vec3(floor(gl_FragCoord.y / 3.0), frame, 7.0));
    float v = mix(snow, line, 0.3) * 0.85 + 0.08;
    // A slow roll of brighter lines through it.
    v += smoothstep(0.92, 1.0, sin(gl_FragCoord.y * 0.05 + WakeTime * 3.0)) * 0.25;
    fragColor = vec4(vec3(v) * vec3(0.93, 0.96, 1.0), clamp(alpha * 0.8, 0.0, 1.0));
}
