/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oculus.camerademo

import android.Manifest
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import com.oculus.camerademo.ui.theme.CameraDemoTheme

class MainActivity : ComponentActivity() {
  companion object {
    private const val FLAG_ENABLED = "enabled"
  }

  private val viewModel by lazy {
    ViewModelProvider.AndroidViewModelFactory(application).create(XrCameraDemoViewModel::class.java)
  }

  private val requestPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
          requestResults ->
        viewModel.onPermissionGranted(requestResults)
      }

  private val permissionManager = PermissionManager()

  private var previewSurface: Surface? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    viewModel.uiState.observe(this) { uiState ->
      setContent {
        CameraDemoTheme {
          Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            CameraDemoUi(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                cameraState = uiState,
                onStartMaxResCamera = { onStartMaxResCamera() },
                onStartLowResCamera = { onStartLowResCamera() },
                onStopCamera = { onStopCamera() },
                onExit = { onExit() })
          }
        }
      }
    }

    viewModel.cameraEvents.observe(this) { event ->
      if (event is CameraEvent.NotificationEvent) {
        Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
        viewModel.onHandleCameraEvent()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.permissionRequestState.observe(this) { permissionState ->
      val permissionsToRequest = mutableListOf<String>()
      if (!permissionState.nativeCameraPermissionGranted) {
        permissionsToRequest.add(PermissionManager.ANDROID_CAMERA_PERMISSION)
      }
      if (!permissionState.vendorCameraPermissionGranted) {
        permissionsToRequest.add(PermissionManager.HZOS_CAMERA_PERMISSION)
      }

      if (permissionsToRequest.isNotEmpty()) {
        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
      } else {
        viewModel.init()
      }
    }

    previewSurface?.let { 
      // Resume with default max resolution
      viewModel.onResume(it, 1280, 960) 
    }
  }

  override fun onPause() {
    super.onPause()
    viewModel.onPause()
  }

  override fun onStop() {
    super.onStop()
    viewModel.shutdown()
  }

  private fun onStartMaxResCamera() {
    checkPermissions()
    val surface = previewSurface
    if (surface == null) {
      Toast.makeText(this, "Something went wrong - surface is not ready", Toast.LENGTH_SHORT).show()
      return
    }
    viewModel.startCameraWithResolution(surface, 1280, 960)
  }

  private fun onStartLowResCamera() {
    checkPermissions()
    val surface = previewSurface
    if (surface == null) {
      Toast.makeText(this, "Something went wrong - surface is not ready", Toast.LENGTH_SHORT).show()
      return
    }
    viewModel.startCameraWithResolution(surface, 320, 240)
  }

  private fun onStopCamera() {
    viewModel.stopCamera()
  }

  private fun onExit() {
    viewModel.shutdown()
    finish()
  }

  private fun checkPermissions() {
    if (!permissionManager.checkPermissions(
        this, Manifest.permission.CAMERA, PermissionManager.HZOS_CAMERA_PERMISSION)) {
      Toast.makeText(this, "Missing required permissions", Toast.LENGTH_SHORT).show()
      return
    }
  }

  private fun updatePreviewTexture(previewSurfaceTexture: SurfaceTexture, width: Int, height: Int) {
    previewSurface = Surface(previewSurfaceTexture)
  }

  @Composable
  fun CameraDemoUi(
      modifier: Modifier = Modifier,
      cameraState: CameraUiState,
      onStartMaxResCamera: () -> Unit,
      onStartLowResCamera: () -> Unit,
      onStopCamera: () -> Unit,
      onExit: () -> Unit
  ) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
      Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Button(onClick = onStartMaxResCamera) { Text("Start Max Res\n(1280x960)") }
        
        Button(onClick = onStartLowResCamera) { Text("Start Low Res\n(320x240)") }
        
        Button(onClick = onStopCamera) { Text("Stop Camera") }
        
        Button(onClick = onExit) { Text("Exit") }
        
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
              Text("Avg Brightness")
              Text(cameraState.cameraBrightness.toString())
            }
      }
      
      AndroidView(
          modifier = Modifier.weight(2f),
          factory = {
            TextureView(this@MainActivity).apply {
              surfaceTextureListener =
                  object : SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                      updatePreviewTexture(surface, width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                      updatePreviewTexture(surface, width, height)
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                      return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                  }
            }
          })
    }
  }
}
