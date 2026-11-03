#version 150

// The Wake: a worn VHS tape. Amount (0..1) is how worn; Tear (0..1) is the damage when it attacks.
// Lives in the minecraft namespace with a prefixed name because vanilla post passes cannot load
// programs from other namespaces.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Amount;
uniform float Tear;
uniform float Time;

in vec2 texCoord;

out vec4 fragColor;

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

vec3 tap(vec2 uv) {
    return texture(DiffuseSampler, clamp(uv, vec2(0.001), vec2(0.999))).rgb;
}

void main() {
    float amount = clamp(Amount, 0.0, 1.0);
    float tear = clamp(Tear, 0.0, 1.0);
    vec2 uv = texCoord;
    float frame = floor(Time * 30.0);

    // The tracking band: a strip of damage rolling slowly down the picture.
    float bandY = 1.0 - fract(Time * 0.045);
    float band = smoothstep(0.045, 0.0, abs(uv.y - bandY)) * amount;

    // Each scanline sits a little off; more in the band; whole blocks of lines shear in a tear.
    float line = floor(uv.y * InSize.y / 2.0);
    float wobble = (rand(vec2(line, frame)) - 0.5) * (0.0012 * amount + 0.016 * band);
    float block = floor(uv.y * 18.0 + rand(vec2(frame, 3.0)) * 4.0);
    float shear = step(0.55, rand(vec2(block, frame))) * (rand(vec2(block, frame + 7.0)) - 0.5) * 0.14 * tear;
    float roll = sin(uv.y * 9.0 + Time * 1.7) * 0.0009 * amount;
    uv.x += wobble + shear + roll;
    // In a tear the whole picture jumps vertically now and then, like a skipping head.
    uv.y += step(0.7, rand(vec2(frame, 11.0))) * (rand(vec2(frame, 13.0)) - 0.5) * 0.06 * tear;

    // Colour bleeds sideways: chroma lags behind luma.
    float shift = (0.0016 + 0.0030 * band) * amount + 0.010 * tear;
    vec3 col;
    col.r = tap(uv + vec2(shift, 0.0)).r;
    col.g = tap(uv).g;
    col.b = tap(uv - vec2(shift * 1.4, 0.0)).b;

    // Soft horizontally: tape has little bandwidth.
    vec2 px = vec2(1.0 / InSize.x, 0.0);
    vec3 soft = (tap(uv + px * 1.5) + tap(uv - px * 1.5) + tap(uv + px * 3.0) + tap(uv - px * 3.0)) * 0.25;
    col = mix(col, soft, 0.45 * amount);

    // Washed out, a little green in the shadows, a little warm in the highlights.
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(col, vec3(luma), 0.28 * amount);
    col = mix(col, col * vec3(0.96, 1.03, 0.97) + vec3(0.012, 0.018, 0.010), amount);
    col = mix(col, smoothstep(vec3(-0.04), vec3(1.02), col), 0.5 * amount);

    // Scanlines.
    float scan = 0.93 + 0.07 * sin((uv.y * InSize.y) * 3.14159);
    col *= mix(1.0, scan, amount);

    // Grain, heavier in the band and the tear; the band itself is half static.
    float grain = rand(texCoord * InSize + vec2(frame * 1.7, frame * 3.1)) - 0.5;
    col += grain * (0.07 * amount + 0.22 * band + 0.35 * tear);
    float snow = rand(vec2(floor(texCoord.x * InSize.x / 2.0), line + frame));
    col = mix(col, vec3(snow), band * 0.45 + tear * step(0.82, rand(vec2(line * 0.37, frame))) * 0.8);

    // Head-switching noise at the very bottom of the frame.
    float bottom = smoothstep(0.035, 0.0, texCoord.y) * amount;
    col = mix(col, vec3(rand(vec2(texCoord.x * 40.0, frame))), bottom * 0.6);

    // Vignette.
    float v = smoothstep(0.95, 0.32, length(texCoord - 0.5));
    col *= mix(1.0, v, 0.45 * amount);

    vec3 original = texture(DiffuseSampler, texCoord).rgb;
    fragColor = vec4(mix(original, col, clamp(amount + tear, 0.0, 1.0)), 1.0);
}
