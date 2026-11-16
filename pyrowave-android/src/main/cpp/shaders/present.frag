#version 450

// Converts the decoded full-range BT.709 YCbCr 4:2:0/4:4:4 planes to RGB for presentation.
layout(set = 0, binding = 0) uniform sampler2D Y;
layout(set = 0, binding = 1) uniform sampler2D Cb;
layout(set = 0, binding = 2) uniform sampler2D Cr;

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 FragColor;

const mat3 yuv2rgb_bt709 = mat3(
    vec3(1.0, 1.0, 1.0),
    vec3(0.0, -0.13397432 / 0.7152, 1.8556),
    vec3(1.5748, -0.33480248 / 0.7152, 0.0));

void main()
{
    float y = textureLod(Y, vUV, 0.0).x;
    float cb = textureLod(Cb, vUV, 0.0).x - 128.0 / 255.0;
    float cr = textureLod(Cr, vUV, 0.0).x - 128.0 / 255.0;
    FragColor = vec4(clamp(yuv2rgb_bt709 * vec3(y, cb, cr), 0.0, 1.0), 1.0);
}
