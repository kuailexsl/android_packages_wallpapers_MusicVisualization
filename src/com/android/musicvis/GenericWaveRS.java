/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.musicvis;

import static android.renderscript.Element.RGB_565;
import static android.renderscript.Sampler.Value.LINEAR;
import static android.renderscript.Sampler.Value.WRAP;

import android.os.Handler;
import android.os.SystemClock;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Primitive;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramVertex;
import android.renderscript.Sampler;
import android.renderscript.ScriptC;
import android.renderscript.Mesh;
import android.renderscript.Type;
import android.renderscript.Element.Builder;
import android.util.Log;

import java.util.TimeZone;

public class GenericWaveRS extends RenderScriptScene {

    private final Handler mHandler = new Handler();
    private final Runnable mDrawCube = new Runnable() {
        public void run() {
            updateWave();
        }
    };
    private boolean mVisible;
    private int mTexId;

    protected static class WorldState {
        public float yRotation;
        public int idle;
        public int waveCounter;
        public int width;
    }
    protected WorldState mWorldState = new WorldState();

    ScriptC_waveform mScript;

    private ScriptField_Vertex mVertexBuffer;

    private Mesh mCubeMesh;

    protected Allocation mPointAlloc;
    // 1024 lines, with 4 points per line (2 space, 2 texture) each consisting of x and y,
    // so 8 floats per line.
    protected float [] mPointData = new float[1024*8];

    private Allocation mLineIdxAlloc;
    // 2 indices per line
    private short [] mIndexData = new short[1024*2];

    private ProgramVertex mPVBackground;
    private ProgramVertex.MatrixAllocation mPVAlloc;

    protected AudioCapture mAudioCapture = null;
    protected int [] mVizData = new int[1024];

    private ProgramFragment mPfBackground;
    private Sampler mSampler;
    private Allocation mTexture;

    private static final int RSID_STATE = 0;
    private static final int RSID_POINTS = 1;
    private static final int RSID_LINES = 2;
    private static final int RSID_PROGRAMVERTEX = 3;

    protected GenericWaveRS(int width, int height, int texid) {
        super(width, height);
        mTexId = texid;
        mWidth = width;
        mHeight = height;
        // the x, s and t coordinates don't change, so set those now
        int outlen = mPointData.length / 8;
        int half = outlen / 2;
        for(int i = 0; i < outlen; i++) {
            mPointData[i*8]   = i - half;          // start point X (Y set later)
            mPointData[i*8+2] = 0;                 // start point S
            mPointData[i*8+3] = 0;                 // start point T
            mPointData[i*8+4]   = i - half;        // end point X (Y set later)
            mPointData[i*8+6] = 1.0f;                 // end point S
            mPointData[i*8+7] = 0f;              // end point T
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mWorldState.width = width;
        if (mPVAlloc != null) {
            mPVAlloc.setupProjectionNormalized(mWidth, mHeight);
        }
    }

    @Override
    protected ScriptC createScript() {

        mScript = new ScriptC_waveform(mRS, mResources, R.raw.waveform);

        // set our java object as the data for the renderscript allocation
        mWorldState.yRotation = 0.0f;
        mWorldState.width = mWidth;
        updateWorldState();

        //  Now put our model in to a form that renderscript can work with:
        //  - create a buffer of floats that are the coordinates for the points that define the cube
        //  - create a buffer of integers that are the indices of the points that form lines
        //  - combine the two in to a mesh

        // First set up the coordinate system and such
        ProgramVertex.Builder pvb = new ProgramVertex.Builder(mRS, null, null);
        mPVBackground = pvb.create();
        mPVAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPVBackground.bindAllocation(mPVAlloc);
        mPVAlloc.setupProjectionNormalized(mWidth, mHeight);

        mScript.set_gPVBackground(mPVBackground);

        mVertexBuffer = new ScriptField_Vertex(mRS, mPointData.length / 4);

        // Start creating the mesh
        final Mesh.AllocationBuilder meshBuilder = new Mesh.AllocationBuilder(mRS);
        meshBuilder.addVertexAllocation(mVertexBuffer.getAllocation());
        // Create the Allocation for the indices
        mLineIdxAlloc = Allocation.createSized(mRS, Element.U16(mRS), mIndexData.length);
        // This will be a line mesh
        meshBuilder.addIndexAllocation(mLineIdxAlloc, Primitive.LINE);

        // Create the Allocation for the vertices
        mCubeMesh = meshBuilder.create();

        mPointAlloc = mVertexBuffer.getAllocation();

        mScript.bind_gPoints(mVertexBuffer);
        mScript.set_gPointBuffer(mPointAlloc);
        mScript.set_gCubeMesh(mCubeMesh);

        //  put the vertex and index data in their respective buffers
        updateWave();
        for(int i = 0; i < mIndexData.length; i ++) {
            mIndexData[i] = (short) i;
        }

        //  upload the vertex and index data
        mPointAlloc.copyFrom(mPointData);
        mLineIdxAlloc.copyFrom(mIndexData);
        mLineIdxAlloc.uploadToBufferObject();

        // load the texture
        mTexture = Allocation.createFromBitmapResource(mRS, mResources, mTexId);

        mScript.set_gTlinetexture(mTexture);

        /*
         * create a program fragment to use the texture
         */
        Sampler.Builder samplerBuilder = new Sampler.Builder(mRS);
        samplerBuilder.setMin(LINEAR);
        samplerBuilder.setMag(LINEAR);
        samplerBuilder.setWrapS(WRAP);
        samplerBuilder.setWrapT(WRAP);
        mSampler = samplerBuilder.create();

        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS);
        builder.setTexture(ProgramFragment.Builder.EnvMode.REPLACE,
                           ProgramFragment.Builder.Format.RGBA, 0);
        mPfBackground = builder.create();
        mPfBackground.bindSampler(mSampler, 0);

        mScript.set_gPFBackground(mPfBackground);

        return mScript;
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mWorldState.yRotation = (xOffset * 4) * 180;
        updateWorldState();
    }

    @Override
    public void start() {
        super.start();
        mVisible = true;
        if (mAudioCapture != null) {
            mAudioCapture.start();
        }
        SystemClock.sleep(200);
        updateWave();
    }

    @Override
    public void stop() {
        super.stop();
        mVisible = false;
        if (mAudioCapture != null) {
            mAudioCapture.stop();
        }
        updateWave();
    }

    public void update() {
    }

    void updateWave() {
        mHandler.removeCallbacks(mDrawCube);
        if (!mVisible) {
            return;
        }
        mHandler.postDelayed(mDrawCube, 20);
        update();
        mWorldState.waveCounter++;
        updateWorldState();
    }

    protected void updateWorldState() {
        mScript.set_gYRotation(mWorldState.yRotation);
        mScript.set_gIdle(mWorldState.idle);
        mScript.set_gWaveCounter(mWorldState.waveCounter);
        mScript.set_gWidth(mWorldState.width);
    }
}
