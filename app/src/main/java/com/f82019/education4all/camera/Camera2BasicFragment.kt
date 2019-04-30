/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.f82019.education4all.camera

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.TextView
import com.airbnb.lottie.LottieAnimationView
import com.f82019.education4all.Main3Activity.OCR
import com.f82019.education4all.Main3Activity.state
import com.f82019.education4all.R
import com.f82019.education4all.mobilenet.MobilenetClassifier
import com.f82019.education4all.mobilenet.MobilenetClassifierFloatException
import com.f82019.education4all.mobilenet.ObjectDrawView
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import kotlinx.android.synthetic.main.fragment_camera2_basic.*
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Basic fragment structure for displaying realtime camera preview using Camera2 API
 */
class Camera2BasicFragment : Fragment(), ActivityCompat.OnRequestPermissionsResultCallback {

    private val lock = Any()
    private var checkedPermissions = false

    protected var textureView: AutoFitTextureView? = null
    private var layoutFrame: AutoFitFrameLayout? = null
//    protected var drawView : DrawView? = null

    private var objectDetectionClassifier: MobilenetClassifier? = null
    private var objectDrawView: ObjectDrawView? = null
    private var ltViewDobby: LottieAnimationView? = null

    protected var runObjectDetector = false
    protected var runOcrDetector = false
    private var isFacingFront: Boolean = false

    protected var tvResult: TextView? = null
    private var textToSpeech: TextToSpeech? = null
    private var previousElementText: String = ""
    private var blockString: String = ""

    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a [ ].
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture,
                width: Int,
                height: Int
        ) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture,
                width: Int,
                height: Int
        ) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    /**
     * ID of the current [CameraDevice].
     */
    protected var cameraId: String? = null

    /**
     * A [CameraCaptureSession] for camera preview.
     */
    protected var captureSession: CameraCaptureSession? = null

    /**
     * A reference to the opened [CameraDevice].
     */
    protected var cameraDevice: CameraDevice? = null

    /**
     * The [android.util.Size] of camera preview.
     */
    protected var previewSize: Size? = null

    /**
     * [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.
     */
    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(currentCameraDevice: CameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            cameraOpenCloseLock.release()
            cameraDevice = currentCameraDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(currentCameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            currentCameraDevice.close()
            cameraDevice = null
        }

        override fun onError(
                currentCameraDevice: CameraDevice,
                error: Int
        ) {
            cameraOpenCloseLock.release()
            currentCameraDevice.close()
            cameraDevice = null
            val activity = activity
            activity?.finish()
        }
    }

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null

    /**
     * A [Handler] for running tasks in the background.
     */
    private var backgroundHandler: Handler? = null

    /**
     * An [ImageReader] that handles image capture.
     */
    protected var imageReader: ImageReader? = null

    /**
     * [CaptureRequest.Builder] for the camera preview
     */
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    /**
     * [CaptureRequest] generated by [.previewRequestBuilder]
     */
    private var previewRequest: CaptureRequest? = null

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock = Semaphore(1)

    /**
     * A [CameraCaptureSession.CaptureCallback] that handles events related to capture.
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
        }

        override fun onCaptureProgressed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                partialResult: CaptureResult
        ) {
            process(partialResult)
        }

        override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
        ) {
            process(result)
        }
    }

    private val requiredPermissions: Array<String>
        get() {
            val activity = activity
            return try {
                val info = activity
                        .packageManager
                        .getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
                val ps = info.requestedPermissions
                if (ps != null && ps.isNotEmpty()) {
                    ps
                } else {
                    arrayOf()
                }
            } catch (e: Exception) {
                arrayOf()
            }

        }

    /**
     * Takes photos and classify them periodically.
     */
    private val periodicClassify = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (runObjectDetector) {
                    classifyObject()
                }

                if (runOcrDetector) {
                    detectText()
                }
            }
            backgroundHandler!!.postDelayed(this, 300)
        }
    }

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater?.inflate(R.layout.fragment_camera2_basic, container, false)
        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        textureView = view.findViewById(R.id.textureView)
        layoutFrame = view.findViewById(R.id.layoutFrame)
        objectDrawView = view.findViewById(R.id.objectDrawView)
        ltViewDobby = view.findViewById(R.id.ltViewDobby)
        ltViewDobby.apply {
            this?.visibility = View.INVISIBLE
        }
        tvResult = view.findViewById(R.id.tvResult)
        tvResult.apply {
            this?.visibility = View.INVISIBLE
        }

        try {
            objectDetectionClassifier = MobilenetClassifierFloatException.create(activity)
            objectDrawView?.setImgSize(objectDetectionClassifier!!.imageSize, objectDetectionClassifier!!.imageSize)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize an object classifier.", e)
        }

        textToSpeech = TextToSpeech(activity, TextToSpeech.OnInitListener {
            if (it != TextToSpeech.ERROR) {
                textToSpeech?.setLanguage(Locale.ENGLISH)
                textToSpeech?.setPitch(1.0f)
                textToSpeech?.setSpeechRate(1.2f)
            }
        })

        setListener()
    }

    fun setListener() {
        ibRotate.setOnClickListener {
            isFacingFront = !isFacingFront
            closeCamera()
            openCamera(previewSize!!.width, previewSize!!.height)
        }
    }

    override fun onResume() {
        super.onResume()
        if (textureView!!.isAvailable) {
            openCamera(textureView!!.width, textureView!!.height)
        } else {
            textureView!!.surfaceTextureListener = surfaceTextureListener
        }
        startBackgroundThread()
    }

    override fun onPause() {
        stopBackgroundThread()
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        objectDetectionClassifier!!.close()
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private fun setUpCameraOutputs(
            width: Int,
            height: Int
    ) {
        val activity = activity
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                if (isFacingFront) {
                    if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                        continue
                    }
                } else {
                    if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                        continue
                    }
                }

                val map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                                ?: continue

                // // For still image captures, we use the largest available size.
                val largest = Collections.max(
                        Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)), CompareSizesByArea()
                )

                val onImageAvailableListener = object : ImageReader.OnImageAvailableListener {
                    override fun onImageAvailable(reader: ImageReader) {
                        reader.acquireLatestImage()
                    }
                }

                imageReader = ImageReader.newInstance(
                        480, 360, ImageFormat.YUV_420_888, /*maxImages*/ 2)
                        .apply {
                            setOnImageAvailableListener(onImageAvailableListener, null)
                        }

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                val displayRotation = activity.windowManager.defaultDisplay.rotation

                /* Orientation of the camera sensor */
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                var swappedDimensions = false
                when (displayRotation) {
                    Surface.ROTATION_0, Surface.ROTATION_180 -> if (sensorOrientation == 90 || sensorOrientation == 270) {
                        //swappedDimensions = true
                    }
                    Surface.ROTATION_90, Surface.ROTATION_270 -> if (sensorOrientation == 0 || sensorOrientation == 180) {
                        //swappedDimensions = true
                    }
                    else -> Log.e(TAG, "Display rotation is invalid: $displayRotation")
                }

                val displaySize = Point()
                activity.windowManager.defaultDisplay.getSize(displaySize)
                var rotatedPreviewWidth = width
                var rotatedPreviewHeight = height
                var maxPreviewWidth = displaySize.x
                var maxPreviewHeight = displaySize.y

                if (swappedDimensions) {
                    rotatedPreviewWidth = height
                    rotatedPreviewHeight = width
                    maxPreviewWidth = displaySize.y
                    maxPreviewHeight = displaySize.x
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT
                }

                previewSize = chooseOptimalSize(
                        map.getOutputSizes(SurfaceTexture::class.java),
                        rotatedPreviewWidth,
                        rotatedPreviewHeight,
                        maxPreviewWidth,
                        maxPreviewHeight,
                        largest
                )

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                val orientation = resources.configuration.orientation
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    layoutFrame!!.setAspectRatio(previewSize!!.width, previewSize!!.height)
                    textureView!!.setAspectRatio(previewSize!!.width, previewSize!!.height)
                    objectDrawView!!.setAspectRatio(previewSize!!.width, previewSize!!.height)
                } else {
                    layoutFrame!!.setAspectRatio(previewSize!!.height, previewSize!!.width)
                    textureView!!.setAspectRatio(previewSize!!.height, previewSize!!.width)
                    objectDrawView!!.setAspectRatio(previewSize!!.height, previewSize!!.width)
                }
                this.cameraId = cameraId
                return
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to access Camera", e)
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
        }

    }

    /**
     * Opens the camera specified by [Camera2BasicFragment.cameraId].
     */
    @SuppressLint("MissingPermission")
    private fun openCamera(
            width: Int,
            height: Int
    ) {
        if (!checkedPermissions && !allPermissionsGranted()) {
            ActivityCompat.requestPermissions(activity, requiredPermissions, PERMISSIONS_REQUEST_CODE)
            return
        } else {
            checkedPermissions = true
        }
        setUpCameraOutputs(width, height)
        configureTransform(width, height)
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open Camera", e)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(
                            activity, permission
                    ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            if (null != captureSession) {
                captureSession!!.close()
                captureSession = null
            }
            if (null != cameraDevice) {
                cameraDevice!!.close()
                cameraDevice = null
            }
            if (null != imageReader) {
                imageReader!!.close()
                imageReader = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    fun startBackgroundThread() {
        backgroundThread = HandlerThread(HANDLE_THREAD_NAME)
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
        synchronized(lock) {
            runObjectDetector = true
            runOcrDetector = false
            if (state == OCR) {
                runOcrDetector = true
                runObjectDetector = false
            }
        }
        backgroundHandler!!.postDelayed(periodicClassify, 300)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    fun stopBackgroundThread() {
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
            backgroundHandler = null
            synchronized(lock) {
                runObjectDetector = false
                runOcrDetector = false
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted when stopping background thread", e)
        }

    }

    /**
     * Creates a new [CameraCaptureSession] for camera preview.
     */
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView!!.surfaceTexture!!

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                this.addTarget(surface)
            }

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice!!.createCaptureSession(
                    Arrays.asList(surface),
                    object : CameraCaptureSession.StateCallback() {

                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            // The camera is already closed
                            if (null == cameraDevice) {
                                return
                            }

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder!!.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                previewRequestBuilder!!.set(
                                        CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                                        CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
                                )

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder!!.build()
                                captureSession!!.setRepeatingRequest(
                                        previewRequest!!, captureCallback, backgroundHandler
                                )
                            } catch (e: CameraAccessException) {
                                Log.e(TAG, "Failed to set up config to capture Camera", e)
                            }

                        }

                        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        }
                    }, null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to preview Camera", e)
        }

    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`. This
     * method should be called after the camera preview size is determined in setUpCameraOutputs and
     * also the size of `textureView` is fixed.
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private fun configureTransform(
            viewWidth: Int,
            viewHeight: Int
    ) {
        val activity = activity
        if (null == textureView || null == previewSize || null == activity) {
            return
        }
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize!!.height.toFloat(), previewSize!!.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                    viewHeight.toFloat() / previewSize!!.height,
                    viewWidth.toFloat() / previewSize!!.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            //matrix.postRotate(180f, centerX, centerY)
        }
        textureView!!.setTransform(matrix)
    }

    /**
     * Classifies a frame from the preview stream.
     */
    private fun classifyObject() {
        if (objectDetectionClassifier == null || activity == null || cameraDevice == null) {
            Log.e("Mobilenet", "Uninitialized Classifier or invalid context.")
            return
        }
        val bitmap = textureView!!.getBitmap(objectDetectionClassifier!!.imageSize, objectDetectionClassifier!!.imageSize)
        val textToShow = objectDetectionClassifier!!.classifyFrame(bitmap)
        bitmap.recycle()

        activity?.runOnUiThread {

            objectDrawView!!.setDrawPoint(objectDetectionClassifier!!.recognitions)
            objectDrawView?.invalidate()

            if (objectDetectionClassifier!!.recognitions.isNullOrEmpty() == false) {
                ltViewDobby?.apply {
                    val width = objectDetectionClassifier!!.recognitions[0].location.right / objectDrawView!!.mRatioX
                    -objectDetectionClassifier!!.recognitions[0].location.left / objectDrawView!!.mRatioX
                    val height = width * 0.805f
                    this?.visibility = View.VISIBLE
                    this?.x = objectDetectionClassifier!!.recognitions[0].location.left / objectDrawView!!.mRatioX - width * 0.3f
                    this?.y = objectDetectionClassifier!!.recognitions[0].location.top / objectDrawView!!.mRatioY - height * 0.5f
                    this?.requestLayout()
                }
                if (ltViewDobby?.isAnimating != true)
                    ltViewDobby?.playAnimation()
            } else {
                ltViewDobby?.visibility = View.INVISIBLE
            }
        }

    }

    private fun detectText() {
        val txtBitmap = textureView!!.getBitmap(360, 480)
        val detector = FirebaseVision.getInstance()
                .onDeviceTextRecognizer
        if (txtBitmap != null) {
            blockString = ""
            val image = FirebaseVisionImage.fromBitmap(txtBitmap)
            val result = detector.processImage(image)
                    .addOnSuccessListener { firebaseVisionText ->
                        for (block in firebaseVisionText.textBlocks) {
                            for (line in block.lines) {
                                blockString += "\n"
                                for (element in line.elements) {
//                            textAreaDrawView?.setDrawPoint(element.boundingBox!!)
                                    val elementText = element.text.toString()
                                    val elementCornerPoints = element.cornerPoints

                                    blockString += elementText + " "
                                }
                            }
                        }
                        val eleTxt = blockString.replace(" ", "")
                        val prevTxt = previousElementText.replace(" ", "")

                        if ((eleTxt.contains(prevTxt) == false) && ((prevTxt.contains(eleTxt))) == false) {
                            textToSpeech?.speak(blockString, TextToSpeech.SUCCESS, null, null)
                            activity?.runOnUiThread { tvResult?.visibility = View.INVISIBLE }
                        }
                        showResult(blockString)
                        previousElementText = blockString
                    }
        }
    }

    private fun showResult(text: String) {
        val activity = activity
        activity?.runOnUiThread {
            tvResult.apply {
                this?.visibility = View.VISIBLE
                this?.text = text
            }
        }
    }

    /**
     * Compares two `Size`s based on their areas.
     */
    private class CompareSizesByArea : Comparator<Size> {

        override fun compare(
                lhs: Size,
                rhs: Size
        ): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(
                    lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height
            )
        }
    }

    /**
     * Shows an error message dialog.
     */
    class ErrorDialog : DialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle): Dialog {
            val activity = activity
            return AlertDialog.Builder(activity)
                    .setMessage(arguments.getString(ARG_MESSAGE))
                    .setPositiveButton(
                            android.R.string.ok
                    ) { dialogInterface, i -> activity.finish() }
                    .create()
        }

        companion object {

            private val ARG_MESSAGE = "message"

            fun newInstance(message: String): ErrorDialog {
                val dialog = ErrorDialog()
                val args = Bundle()
                args.putString(ARG_MESSAGE, message)
                dialog.arguments = args
                return dialog
            }
        }
    }

    companion object {

        /**
         * Tag for the [Log].
         */
        private const val TAG = "Camera2Basic"

        private const val FRAGMENT_DIALOG = "dialog"

        private const val HANDLE_THREAD_NAME = "CameraBackground"

        private const val PERMISSIONS_REQUEST_CODE = 1

        /**
         * Max preview width that is guaranteed by Camera2 API
         */
        private const val MAX_PREVIEW_WIDTH = 2880

        /**
         * Max preview height that is guaranteed by Camera2 API
         */
        private const val MAX_PREVIEW_HEIGHT = 1440

        fun newInstance(): Camera2BasicFragment {
            return Camera2BasicFragment()
        }

        /**
         * Resizes image.
         *
         *
         * Attempting to use too large a preview size could  exceed the camera bus' bandwidth limitation,
         * resulting in gorgeous previews but the storage of garbage capture data.
         *
         *
         * Given `choices` of `Size`s supported by a camera, choose the smallest one that is
         * at least as large as the respective texture view size, and that is at most as large as the
         * respective max size, and whose aspect ratio matches with the specified value. If such size
         * doesn't exist, choose the largest one that is at most as large as the respective max size, and
         * whose aspect ratio matches with the specified value.
         *
         * @param choices           The list of sizes that the camera supports for the intended output class
         * @param textureViewWidth  The width of the texture view relative to sensor coordinate
         * @param textureViewHeight The height of the texture view relative to sensor coordinate
         * @param maxWidth          The maximum width that can be chosen
         * @param maxHeight         The maximum height that can be chosen
         * @param aspectRatio       The aspect ratio
         * @return The optimal `Size`, or an arbitrary one if none were big enough
         */
        private fun chooseOptimalSize(
                choices: Array<Size>,
                textureViewWidth: Int,
                textureViewHeight: Int,
                maxWidth: Int,
                maxHeight: Int,
                aspectRatio: Size
        ): Size {

            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = ArrayList<Size>()
            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = ArrayList<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth
                        && option.height <= maxHeight
                        && option.height == option.width * h / w
                ) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.
            return when {
                bigEnough.size > 0 -> Collections.min(bigEnough, CompareSizesByArea())
                notBigEnough.size > 0 -> Collections.max(notBigEnough, CompareSizesByArea())
                else -> {
                    Log.e(TAG, "Couldn't find any suitable preview size")
                    choices[0]
                }
            }
        }

    }
}
