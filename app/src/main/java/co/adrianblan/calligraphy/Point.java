/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package co.adrianblan.calligraphy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;

/**
 * A two-dimensional triangle for use as a drawn object in OpenGL ES 2.0.
 */
public class Point {

    private static final String vertexShaderCode =
            // This matrix member variable provides a hook to manipulate
            // the coordinates of the objects that use this vertex shader
            "uniform mat4 uMVPMatrix;" +
            "attribute vec2 a_TexCoordinate;" + // Per-vertex texture coordinate information we will pass in.
            "attribute vec4 vPosition;" +
            "varying vec2 v_TexCoordinate;" +   // This will be passed into the fragment shader.
            "void main() {" +
            // the matrix must be included as a modifier of gl_Position
            // Note that the uMVPMatrix factor *must be first* in order
            // for the matrix multiplication product to be correct.
            "  gl_Position = uMVPMatrix * vPosition;" +
            // Pass through the texture coordinate.
            "  v_TexCoordinate = a_TexCoordinate;" +
            "}";

    private static final String fragmentShaderCode =
            "precision mediump float;" +
            "uniform vec4 vColor;" +
            "uniform sampler2D u_Texture;" +
            "varying vec2 v_TexCoordinate;" +
            "void main() {" +
            //"  gl_FragColor = vColor;" +
            "  vec4 col = vColor * texture2D(u_Texture, v_TexCoordinate);" +
            //"  col.w = vColor.w;" +
            "  gl_FragColor = col;" +
            "}";

    private final FloatBuffer vertexBuffer;
    private final FloatBuffer textureBuffer;
    private static ShortBuffer drawListBuffer;
    private static int sProgram;
    private int mPositionHandle;
    private int mColorHandle;
    private int mMVPMatrixHandle;
    private int mTextureUniformHandle;
    private int mTextureCoordinateHandle;

    private static boolean initialized = false;

    // number of coordinates per vertex in this array
    private static final int COORDS_PER_VERTEX = 3;
    private static float squareCoords[] = {
            -0.05f,  0.05f, 0.0f,   // top left
            -0.05f, -0.05f, 0.0f,   // bottom left
            0.05f, -0.05f, 0.0f,   // bottom right
            0.05f,  0.05f, 0.0f }; // top right


    private static float textureCoordinates[] = {
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
            1.0f, 0.0f };

    private final short drawOrder[] = { 0, 1, 2, 0, 2, 3 }; // order to draw vertices

    private static final int vertexCount = squareCoords.length / COORDS_PER_VERTEX;
    private static final int vertexStride = COORDS_PER_VERTEX * 4; // 4 bytes per vertex
    private static final int textureCount = textureCoordinates.length / 2;
    private static final int textureStride = 2 * 4;

    static float blackColor[] = {0f, 0f, 0f, 1.0f};
    static float blueColor[] = { 0.17647058f, 0.63921568f, 0.90980392f, 1f };
    private float [] mColor;

    private TouchData touchData;
    /**
     * Sets up the drawing object data for use in an OpenGL ES context.
     */
    public Point(TouchData touchData) {

        this.touchData = touchData;

        // initialize vertex byte buffer for shape coordinates
        ByteBuffer bb = ByteBuffer.allocateDirect(
                // (number of coordinate values * 4 bytes per float)
                squareCoords.length * 4);
        // use the device hardware's native byte order
        bb.order(ByteOrder.nativeOrder());
        // create a floating point buffer from the ByteBuffer
        vertexBuffer = bb.asFloatBuffer();

        float [] vertexCoords = getTranslatedVertexCoords(touchData.getPosition(),
                touchData.getNormalizedTouchSize() * 3.5f + 0.5f);

        // add the coordinates to the FloatBuffer
        vertexBuffer.put(vertexCoords);
        // set the buffer to read the first coordinate
        vertexBuffer.position(0);

        ByteBuffer bb2 = ByteBuffer.allocateDirect(textureCoordinates.length * 4);
        bb2.order(ByteOrder.nativeOrder());
        textureBuffer = bb2.asFloatBuffer();
        textureBuffer.put(textureCoordinates);
        textureBuffer.position(0);

        mColor = blackColor.clone();
        mColor[3] = getAlpha(touchData.getNormalizedTouchSize());

        // Ugly static hack to only initialize common variables once
        // TODO proper initialization
        if(!initialized) {

            // initialize byte buffer for the draw list
            ByteBuffer dlb = ByteBuffer.allocateDirect(
                    // (# of coordinate values * 2 bytes per short)
                    drawOrder.length * 2);
            dlb.order(ByteOrder.nativeOrder());
            drawListBuffer = dlb.asShortBuffer();
            drawListBuffer.put(drawOrder);
            drawListBuffer.position(0);

            // prepare shaders and OpenGL program
            int vertexShader = MyGLRenderer.loadShader(
                    GLES20.GL_VERTEX_SHADER, vertexShaderCode);
            int fragmentShader = MyGLRenderer.loadShader(
                    GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

            sProgram = GLES20.glCreateProgram();             // create empty OpenGL Program
            GLES20.glAttachShader(sProgram, vertexShader);   // add the vertex shader to program
            GLES20.glAttachShader(sProgram, fragmentShader); // add the fragment shader to program
            GLES20.glLinkProgram(sProgram);                  // create OpenGL program executables

            initialized = true;
        }
    }

    /**
     * Encapsulates the OpenGL ES instructions for drawing this shape.
     *
     * @param mvpMatrix - The Model View Project matrix in which to draw
     * this shape.
     */
    public void draw(float[] mvpMatrix, int texture) {
        // Add program to OpenGL environment
        GLES20.glUseProgram(sProgram);

        // get handle to vertex shader's vPosition member
        mPositionHandle = GLES20.glGetAttribLocation(sProgram, "vPosition");
        MyGLRenderer.checkGlError("glGetAttribLocation");

        // Enable a handle to the triangle vertices
        GLES20.glEnableVertexAttribArray(mPositionHandle);

        // Prepare the triangle coordinate data
        GLES20.glVertexAttribPointer(
                mPositionHandle, COORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false,
                vertexStride, vertexBuffer);


        // get handle to fragment shader's vColor member
        mColorHandle = GLES20.glGetUniformLocation(sProgram, "vColor");
        MyGLRenderer.checkGlError("glGetUniformLocation");

        // Set color for drawing the triangle
        GLES20.glUniform4fv(mColorHandle, 1, mColor, 0);

        // get handle to shape's transformation matrix
        mMVPMatrixHandle = GLES20.glGetUniformLocation(sProgram, "uMVPMatrix");
        MyGLRenderer.checkGlError("glGetUniformLocation");

        // Apply the projection and view transformation
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0);
        MyGLRenderer.checkGlError("glUniformMatrix4fv");


        // Textures
        mTextureUniformHandle = GLES20.glGetUniformLocation(sProgram, "u_Texture");
        mTextureCoordinateHandle = GLES20.glGetAttribLocation(sProgram, "a_TexCoordinate");

        // Enable a handle to the triangle vertices
        GLES20.glEnableVertexAttribArray(mTextureCoordinateHandle);

        // Prepare the triangle coordinate data
        GLES20.glVertexAttribPointer(
                mTextureCoordinateHandle, 2,
                GLES20.GL_FLOAT, false,
                textureStride, textureBuffer);


        // Set the active texture unit to texture unit 0.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        // Bind the texture to this unit.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);

        // Tell the texture uniform sampler to use this texture in the shader by binding to texture unit 0.
        GLES20.glUniform1i(mTextureUniformHandle, 0);

        // Draw the square
        GLES20.glDrawElements(
                GLES20.GL_TRIANGLES, drawOrder.length,
                GLES20.GL_UNSIGNED_SHORT, drawListBuffer);

        // Disable vertex array
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTextureCoordinateHandle);
    }

    /** Returns a matrix of vertex coords that has been translated in x and y axis */
    private float[] getTranslatedVertexCoords(Vector2 position, float vertexScale) {
        float [] coords = squareCoords.clone();

        for(int vertexOffset = 0; vertexOffset < coords.length; vertexOffset += COORDS_PER_VERTEX) {
            coords[vertexOffset + 0] *= vertexScale;
            coords[vertexOffset + 1] *= vertexScale;
            coords[vertexOffset + 2] *= vertexScale;

            coords[vertexOffset + 0] += position.getX();
            coords[vertexOffset + 1] += position.getY();
        }

        return coords;
    }

    /** Takes pressure, and returns the alpha for that pressure */
    public static float getAlpha(float normalizedSize) {

        float ALPHA_BASE = 0.40f; // The base alpha level
        float ALPHA_DELTA_MIN = -0.35f; // The maximum negative change treshold in alpha
        float ALPHA_DELTA_MAX = 0.35f; // The maximum positive change treshold in alpha

        return Utils.clamp(ALPHA_BASE * (1f - normalizedSize) * 2.0f, ALPHA_BASE + ALPHA_DELTA_MIN,
                ALPHA_BASE + ALPHA_DELTA_MAX);
    }

    public TouchData getTouchData() {
        return touchData;
    }
}