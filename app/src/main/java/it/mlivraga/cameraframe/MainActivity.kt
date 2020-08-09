package it.mlivraga.cameraframe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import kotlinx.android.synthetic.main.activity_main.*
import timber.log.Timber
import java.io.*
import java.util.*

class MainActivity : AppCompatActivity() {
    private val ORIENTATIONS = SparseIntArray()

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSessions: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var imageDimension: Size? = null
    private var mBackgroundHandler: Handler? = null
    private val REQUEST_CAMERA_PERMISSION = 200
    private lateinit var photoFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree());
        }

        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)

        textureView.surfaceTextureListener = textureListener
        takePictureButton?.setOnClickListener({ takePicture() })
    }

    override fun onResume() {
        super.onResume()
        Timber.d("onResume")

        if (textureView.isAvailable)
            openCamera()
        else
            textureView.surfaceTextureListener = textureListener
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_grant_permission),
                    Toast.LENGTH_LONG
                ).show()

                finish()
            }
        }
    }

    private fun takePicture() {
        Timber.d("takePicture")

        cameraDevice?.let { cameraDevice ->
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val characteristics = manager.getCameraCharacteristics(cameraDevice.id)

            val jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(
                ImageFormat.JPEG)

            var width = 640
            var height = 480
            if(jpegSizes != null && jpegSizes.size > 0){
                width = jpegSizes[0].width
                height = jpegSizes[0].height
            }

            val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)

            val outputSurfaces: MutableList<Surface> = ArrayList(2)
            outputSurfaces.add(reader.surface)
            outputSurfaces.add(Surface(textureView.surfaceTexture))

            val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            val rotation = windowManager.defaultDisplay.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation))

            val filename = "cameraFrame.jpg"
            val readerListener = createImageListener(filename)

            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler)
            val captureListener: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    Toast.makeText(this@MainActivity, "Saved:$filename", Toast.LENGTH_SHORT).show()
                    createCameraPreview()
                    sendMail("prova@prova.it")
                }
            }

            cameraDevice.createCaptureSession(
                outputSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureBuilder.let {
                            session.capture(
                                it.build(),
                                captureListener,
                                mBackgroundHandler)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                mBackgroundHandler
            )
        }
    }

    private fun createImageListener(fileName: String): ImageReader.OnImageAvailableListener {
        val photoPath = Environment.getExternalStorageDirectory().toString().plus("/" ).plus(fileName)
        Timber.d("photoPath $photoPath")
        photoFile = File(photoPath)

        return object : ImageReader.OnImageAvailableListener {
            override fun onImageAvailable(reader: ImageReader) {
                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer[bytes]
                    save(bytes)
                } catch (e: FileNotFoundException) {
                    e.printStackTrace()
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    image?.close()
                }
            }

            @Throws(IOException::class)
            private fun save(bytes: ByteArray) {
                var output: OutputStream? = null
                try {
                    output = FileOutputStream(photoFile)
                    output.write(bytes)
                } finally {
                    output?.close()
                }
            }
        }
    }

    private fun createCameraPreview() {
        Timber.d("createCameraPreview")

        imageDimension?.let {
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(it.width, it.height)
            val surface = Surface(texture)
            captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder?.addTarget(surface)
            cameraDevice?.createCaptureSession(
                Arrays.asList(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(@NonNull cameraCaptureSession: CameraCaptureSession) {
                        // When the session is ready, we start displaying the preview.
                        cameraCaptureSessions = cameraCaptureSession
                        updatePreview()
                    }

                    override fun onConfigureFailed(@NonNull cameraCaptureSession: CameraCaptureSession) {
                        Toast.makeText(
                            this@MainActivity,
                            "Configuration change",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                null
            )
        }
    }

    private fun openCamera() {
        Timber.d( "openCamera")

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        var cameraId = "0" // 0 -> retro camera , 1 selfie camera
        if(manager.cameraIdList.size > 1)
            cameraId = manager.cameraIdList[1]

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        imageDimension = map.getOutputSizes(SurfaceTexture::class.java)[0]

        if (!ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA).equals(
                PackageManager.PERMISSION_GRANTED) &&
            !ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE).equals(
                PackageManager.PERMISSION_GRANTED)) {

            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQUEST_CAMERA_PERMISSION
            )
            return
        }

        manager.openCamera(cameraId, stateCallback, null)

        Timber.d("/ openCamera")
    }

    private fun updatePreview() {
        Timber.d("updatePreview")

        cameraDevice?.let {
            captureRequestBuilder?.set(
                CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_AUTO
            )

            captureRequestBuilder?.let {
                cameraCaptureSessions?.setRepeatingRequest(
                    it.build(),
                    null,
                    mBackgroundHandler
                )
            }
        }
    }

    var textureListener: TextureView.SurfaceTextureListener = object :
        TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }


    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Timber.d("onOpened")
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close()
            cameraDevice = null
        }
    }

    private fun sendMail(address: String){
        val emailIntent = Intent(Intent.ACTION_SEND);
        emailIntent.setType("application/image")
        emailIntent.putExtra(Intent.EXTRA_EMAIL, address)
        emailIntent.putExtra(Intent.EXTRA_SUBJECT,"Test Subject")
        emailIntent.putExtra(Intent.EXTRA_TEXT, "From My App")
        emailIntent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", photoFile))
        startActivity(Intent.createChooser(emailIntent, "Send mail..."))

    }
}