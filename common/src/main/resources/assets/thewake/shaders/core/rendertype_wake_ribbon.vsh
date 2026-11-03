#version 150

// The Wake: positions arrive camera-relative and world-aligned (the camera's rotation lives in
// ModelViewMat), so the view direction to each fragment is just normalize(Position).

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec3 viewPos;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    viewPos = Position;
    texCoord0 = UV0;
}
