package aculix.parkedhere.app

import aculix.parkedhere.app.arcorerenderer.*
import aculix.parkedhere.app.databinding.ActivityMainBinding
import aculix.parkedhere.app.extensions.toast
import aculix.parkedhere.app.extensions.vibrate
import aculix.parkedhere.app.fragment.HelpFragment
import aculix.parkedhere.app.fragment.PrivacyNoticeDialogFragment
import aculix.parkedhere.app.fragment.ResetParkingFragment
import aculix.parkedhere.app.fragment.VpsAvailabilityNoticeDialogFragment
import aculix.parkedhere.app.helpers.*
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.location.Location
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.annotation.GuardedBy
import androidx.appcompat.app.AppCompatActivity
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.DialogFragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.Projection
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.ar.core.*
import com.google.ar.core.Anchor.TerrainAnchorState
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.exceptions.*
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.maps.DirectionsApi
import com.google.maps.GeoApiContext
import com.google.maps.android.SphericalUtil
import com.google.maps.errors.ZeroResultsException
import com.google.maps.model.DirectionsResult
import com.google.maps.model.DirectionsRoute
import com.google.maps.model.TravelMode
import com.skydoves.balloon.*
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity(), SampleRender.Renderer,
    VpsAvailabilityNoticeDialogFragment.NoticeDialogListener,
    PrivacyNoticeDialogFragment.NoticeDialogListener, ResetParkingFragment.ResetParkingListener {

    private lateinit var binding: ActivityMainBinding

    private val privacyNoticeDialog: DialogFragment = PrivacyNoticeDialogFragment.createDialog()
    private lateinit var mapFragment: SupportMapFragment
    private lateinit var googleMap: GoogleMap
    private lateinit var geoApiContext: GeoApiContext

    private val SHARED_PREF_IS_HELP_TOOLTIP_SHOWN = "is_help_tooltip_shown"
    private val SHARED_PREF_SAVED_ANCHOR = "saved_anchor"
    private val SHARED_PREF_IS_GEOSPATIAL_ACCESS_ALLOWED = "is_geo_spatial_access_allowed"

    private val Z_NEAR = 0.1f
    private val Z_FAR = 1000f

    private val DEFAULT_CAMERA_ZOOM_MAP = 18f

    // The thresholds that are required for horizontal and orientation accuracies before entering into
    // the LOCALIZED state. Once the accuracies are equal or less than these values, the app will
    // allow the user to place anchors.
    private val LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS = 10.0 + 50
    private val LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES = 15.0 + 50

    // Once in the LOCALIZED state, if either accuracies degrade beyond these amounts, the app will
    // revert back to the LOCALIZING state.
    private val LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS = 10.0 + 50
    private val LOCALIZED_ORIENTATION_YAW_ACCURACY_HYSTERESIS_DEGREES = 10.0 + 50

    private val LOCALIZING_TIMEOUT_SECONDS = 180
    private val MAXIMUM_ANCHORS = 1
    private val DURATION_FOR_NO_TERRAIN_ANCHOR_RESULT_MS: Long = 10000

    private var installRequested = false
    private var clearedAnchorsAmount: Int? = null

    /** Timer to keep track of how much time has passed since localizing has started.  */
    private var localizingStartTimestamp: Long = 0

    /** Deadline for showing resolving terrain anchors no result yet message.  */
    private var deadlineForMessageMillis: Long = 0

    /** Time after which the navigation path of the map should be updated **/
    private val UPDATE_LOCATION_INTERVAL_SECONDS = 3
    private var lastUpdatedLocationTimeStamp: Long = 0

    /** Time after which the marker showing the current location should be updated **/
    private val UPDATE_CURRENT_LOCATION_MARKER_INTERVAL_SECONDS = 1
    private var lastUpdatedCurrentLocationMarkerTimeStamp: Long = 0

    private var lastAddedNavigationPolyline: Polyline? = null
    private var currentLocationBitmap: Bitmap? = null
    private var currentLocationMarker: Marker? = null

    private var parkedCarBitmap: Bitmap? = null
    private var parkedCarMarker: Marker? = null

    private var lastLocation: LatLng? = null
    private var isMarkerRotating: Boolean = false

    internal enum class State {
        /** The Geospatial API has not yet been initialized.  */
        UNINITIALIZED,

        /** The Geospatial API is not supported.  */
        UNSUPPORTED,

        /** The Geospatial API has encountered an unrecoverable error.  */
        EARTH_STATE_ERROR,

        /** The Session has started, but [Earth] isn't [TrackingState.TRACKING] yet.  */
        PRETRACKING,

        /**
         * [Earth] is [TrackingState.TRACKING], but the desired positioning confidence
         * hasn't been reached yet.
         */
        LOCALIZING,

        /** The desired positioning confidence wasn't reached in time.  */
        LOCALIZING_FAILED,

        /**
         * [Earth] is [TrackingState.TRACKING] and the desired positioning confidence has
         * been reached.
         */
        LOCALIZED
    }

    private var state = State.UNINITIALIZED

    private var session: Session? = null
    private val messageSnackbarHelper: SnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper: TrackingStateHelper = TrackingStateHelper(this)
    private var render: SampleRender? = null
    private var sharedPreferences: SharedPreferences? = null

    private var lastStatusText: String? = null

    private var planeRenderer: PlaneRenderer? = null
    private var backgroundRenderer: BackgroundRenderer? = null
    private var virtualSceneFramebuffer: Framebuffer? = null
    private var hasSetTextureNames = false

    // Set WGS84 anchor or Terrain anchor.
    private var isTerrainAnchorMode = false

    // Virtual object (ARCore geospatial)
    private var virtualObjectMesh: Mesh? = null
    private var geospatialAnchorVirtualObjectShader: Shader? = null

    // Virtual object (ARCore geospatial terrain)
    private var terrainAnchorVirtualObjectShader: Shader? = null

    private val anchors: List<Anchor> = ArrayList()

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16) // view x model

    private val modelViewProjectionMatrix = FloatArray(16) // projection x view x model


    // Locks needed for synchronization
    private val singleTapLock = Any()

    @GuardedBy("singleTapLock")
    private var queuedSingleTap: MotionEvent? = null

    // Tap handling and UI.
    private var gestureDetector: GestureDetector? = null

    // Point Cloud
    private var pointCloudVertexBuffer: VertexBuffer? = null
    private var pointCloudMesh: Mesh? = null
    private var pointCloudShader: Shader? = null

    // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
    // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
    private var lastPointCloudTimestamp: Long = 0

    // Provides device location.
    private var fusedLocationClient: FusedLocationProviderClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        displayRotationHelper = DisplayRotationHelper( /*activity=*/this)
        render = SampleRender(binding.surfaceview, this, assets)

        mapFragment =
            supportFragmentManager.findFragmentById(R.id.maps_fragment) as SupportMapFragment

        geoApiContext = GeoApiContext.Builder().apiKey(getString(R.string.gc_api_key)).build()

        sharedPreferences = getPreferences(MODE_PRIVATE)

        installRequested = false
        clearedAnchorsAmount = null

        // Set up touch listener.
        gestureDetector = GestureDetector(
            this,
            object : SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    synchronized(singleTapLock) { queuedSingleTap = e }
                    return true
                }

                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }
            })

        binding.surfaceview.setOnTouchListener(OnTouchListener { v: View?, event: MotionEvent? ->
            gestureDetector!!.onTouchEvent(event!!)
        })

        fusedLocationClient = LocationServices.getFusedLocationProviderClient( /*context=*/this)
        currentLocationBitmap = BitmapCache(this, 1).getBitmap(R.drawable.ic_navigation)
        parkedCarBitmap = BitmapCache(this, 1).getBitmap(R.drawable.ic_parked_car)

        setupMap()

        showBalloonHelpTip()
        onHelpClick()
        onResetParkingClick()
    }

    override fun onResume() {
        super.onResume()

        if (sharedPreferences!!.getBoolean(SHARED_PREF_IS_GEOSPATIAL_ACCESS_ALLOWED, false)
        ) {
            createSession()
        } else {
            showPrivacyNoticeDialog()
        }

        binding.surfaceview.onResume()
        displayRotationHelper!!.onResume()
    }

    override fun onPause() {
        super.onPause()

        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper!!.onPause()
            binding.surfaceview.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        if (session != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            session?.close()
            session = null
        }

        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(
                this,
                "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()

            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }

            finish()
        }

        // Check if this result pertains to the location permission.
        if (LocationPermissionHelper.hasFineLocationPermissionsResponseInResult(permissions)
            && !LocationPermissionHelper.hasFineLocationPermission(this)
        ) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(
                this,
                "Precise location permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()

            if (!LocationPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                LocationPermissionHelper.launchPermissionSettings(this)
            }

            finish()
        } else if (LocationPermissionHelper.hasFineLocationPermission(this)) {
            setupCurrentLocationMarkerAndCameraZoom()
        }
    }

    override fun onSurfaceCreated(render: SampleRender?) {
        // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
        // an IOException.
        try {
            planeRenderer = PlaneRenderer(render)
            backgroundRenderer = BackgroundRenderer(render)
            virtualSceneFramebuffer = Framebuffer(render,  /*width=*/1,  /*height=*/1)

            // Virtual object to render (ARCore geospatial)
            val virtualObjectTexture = Texture.createFromAsset(
                render,
                "models/location_marker_color.png",
                Texture.WrapMode.CLAMP_TO_EDGE,
                Texture.ColorFormat.SRGB
            )

            virtualObjectMesh = Mesh.createFromAsset(render, "models/location_marker.obj")
            geospatialAnchorVirtualObjectShader = Shader.createFromAssets(
                render,
                "shaders/ar_unlit_object.vert",
                "shaders/ar_unlit_object.frag",  /*defines=*/
                null
            )
                .setTexture("u_Texture", virtualObjectTexture)

            // Virtual object to render (Terrain anchor marker)
            val terrainAnchorVirtualObjectTexture = Texture.createFromAsset(
                render,
                "models/location_marker_color.png",
                Texture.WrapMode.CLAMP_TO_EDGE,
                Texture.ColorFormat.SRGB
            )

            terrainAnchorVirtualObjectShader = Shader.createFromAssets(
                render,
                "shaders/ar_unlit_object.vert",
                "shaders/ar_unlit_object.frag",  /*defines=*/
                null
            )
                .setTexture("u_Texture", terrainAnchorVirtualObjectTexture)

            backgroundRenderer?.setUseDepthVisualization(render, false)
            backgroundRenderer?.setUseOcclusion(render, false)

            // Point cloud
            pointCloudShader = Shader.createFromAssets(
                render, "shaders/point_cloud.vert", "shaders/point_cloud.frag",  /*defines=*/null
            )
                .setVec4(
                    "u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f)
                )
                .setFloat("u_PointSize", 5.0f)

            // four entries per vertex: X, Y, Z, confidence
            pointCloudVertexBuffer =
                VertexBuffer(render,  /*numberOfEntriesPerVertex=*/4,  /*entries=*/null)
            val pointCloudVertexBuffers = arrayOf<VertexBuffer>(pointCloudVertexBuffer!!)
            pointCloudMesh = Mesh(
                render, Mesh.PrimitiveMode.POINTS,  /*indexBuffer=*/null, pointCloudVertexBuffers
            )
        } catch (e: IOException) {
            messageSnackbarHelper.showError(this, "Failed to read a required asset file: $e")
            Timber.e("Failed to read a required asset file: $e")
        }
    }

    override fun onSurfaceChanged(render: SampleRender?, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        virtualSceneFramebuffer!!.resize(width, height)
    }

    override fun onDrawFrame(render: SampleRender) {
        if (session == null) {
            return
        }

        // Texture names should only be set once on a GL thread unless they change. This is done during
        // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
        // initialized during the execution of onSurfaceCreated.
        if (!hasSetTextureNames) {
            session?.setCameraTextureNames(intArrayOf(backgroundRenderer!!.cameraColorTexture.textureId))
            hasSetTextureNames = true
        }

        // -- Update per-frame state
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session)

        // Obtain the current frame from ARSession. When the configuration is set to
        // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
        // camera framerate.
        val frame: Frame? = try {
            session?.update()
        } catch (e: CameraNotAvailableException) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            Timber.e("Camera not available during onDrawFrame: $e")
            return
        }

        val camera = frame?.camera

        // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
        // used to draw the background camera image.
        backgroundRenderer!!.updateDisplayGeometry(frame)

        // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
        trackingStateHelper.updateKeepScreenOnFlag(camera?.trackingState)
        val earth = session?.earth
        earth?.let { updateGeospatialState(it) }

        // Show a message based on whether tracking has failed, if planes are detected, and if the user
        // has placed any objects.
        var message: String? = null
        when (state) {
            State.UNINITIALIZED -> {}
            State.UNSUPPORTED -> message =
                resources.getString(R.string.status_unsupported)
            State.PRETRACKING -> message =
                resources.getString(R.string.status_pretracking)
            State.EARTH_STATE_ERROR -> message =
                resources.getString(R.string.status_earth_state_error)
            State.LOCALIZING -> message =
                resources.getString(R.string.status_localize_hint)
            State.LOCALIZING_FAILED -> message =
                resources.getString(R.string.status_localize_timeout)
            State.LOCALIZED -> if (lastStatusText == resources.getString(R.string.status_localize_hint)) {
                message = resources.getString(R.string.status_localize_complete)
            }
        }

        if (message != null && lastStatusText != message) {
            lastStatusText = message

            runOnUiThread {
                binding.tvSessionStatus.setVisibility(View.VISIBLE)
                binding.tvSessionStatus.setText(lastStatusText)
            }
        } else {
            runOnUiThread {
                binding.tvSessionStatus.visibility = View.GONE
            }
        }

        // Handle user input.
        handleTap(frame, camera?.trackingState)

        // -- Draw background
        if (frame?.timestamp != 0L) {
            // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
            // drawing possible leftover data from previous sessions if the texture is reused.
            backgroundRenderer!!.drawBackground(render)
        }

        // If not tracking, don't draw 3D objects.
        if (camera?.trackingState != TrackingState.TRACKING || state != State.LOCALIZED) {
            return
        }

        // -- Draw virtual objects

        // Get projection matrix.
        camera.getProjectionMatrix(
            projectionMatrix,
            0,
            Z_NEAR,
            Z_FAR
        )

        // Get camera matrix and draw.
        camera.getViewMatrix(viewMatrix, 0)
        frame.acquirePointCloud().use { pointCloud ->
            if (pointCloud.timestamp > lastPointCloudTimestamp) {
                pointCloudVertexBuffer!!.set(pointCloud.points)
                lastPointCloudTimestamp = pointCloud.timestamp
            }

            Matrix.multiplyMM(
                modelViewProjectionMatrix,
                0,
                projectionMatrix,
                0,
                viewMatrix,
                0
            )

            pointCloudShader!!.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            render.draw(pointCloudMesh, pointCloudShader)
        }

        // Visualize planes.
        planeRenderer!!.drawPlanes(
            render,
            session?.getAllTrackables(Plane::class.java),
            camera.displayOrientedPose,
            projectionMatrix
        )

        // Visualize anchors created by touch.
        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)

        for (anchor in anchors) {
            // Get the current pose of an Anchor in world space. The Anchor pose is updated
            // during calls to session.update() as ARCore refines its estimate of the world.

            // Only render resolved Terrain anchors and Geospatial anchors.
            if (anchor.terrainAnchorState != TerrainAnchorState.SUCCESS
                && anchor.terrainAnchorState != TerrainAnchorState.NONE
            ) {
                continue
            }

            anchor.pose.toMatrix(modelMatrix, 0)

            // Rotate the virtual object 180 degrees around the Y axis to make the object face the GL
            // camera -Z axis, since camera Z axis faces toward users.
            val rotationMatrix = FloatArray(16)
            Matrix.setRotateM(rotationMatrix, 0, 180f, 0.0f, 1.0f, 0.0f)
            val rotationModelMatrix = FloatArray(16)
            Matrix.multiplyMM(rotationModelMatrix, 0, modelMatrix, 0, rotationMatrix, 0)

            // Calculate model/view/projection matrices
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, rotationModelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

            // Update shader properties and draw
            if (anchor.terrainAnchorState == TerrainAnchorState.SUCCESS) {
                terrainAnchorVirtualObjectShader!!.setMat4(
                    "u_ModelViewProjection", modelViewProjectionMatrix
                )
                render.draw(
                    virtualObjectMesh,
                    terrainAnchorVirtualObjectShader,
                    virtualSceneFramebuffer
                )
            } else {
                geospatialAnchorVirtualObjectShader!!.setMat4(
                    "u_ModelViewProjection", modelViewProjectionMatrix
                )
                render.draw(
                    virtualObjectMesh, geospatialAnchorVirtualObjectShader, virtualSceneFramebuffer
                )
            }
        }

        // Compose the virtual scene with the background.
        backgroundRenderer!!.drawVirtualScene(
            render,
            virtualSceneFramebuffer,
            Z_NEAR,
            Z_FAR
        )
    }

    private fun setupMap() {
        mapFragment.getMapAsync {
            googleMap = it

            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                setupCurrentLocationMarkerAndCameraZoom()
            }
        }
    }

    private fun setupCurrentLocationMarkerAndCameraZoom() {
        getCurrentLocation { currentLocation ->
            lastLocation = LatLng(
                currentLocation.latitude, currentLocation.longitude
            )

            currentLocationMarker = googleMap.addMarker(
                MarkerOptions().position(
                    LatLng(
                        currentLocation.latitude,
                        currentLocation.longitude
                    )
                )
                    .icon(BitmapDescriptorFactory.fromBitmap(currentLocationBitmap!!))
                    .flat(true)
            )

            googleMap.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(currentLocation.latitude, currentLocation.longitude),
                    DEFAULT_CAMERA_ZOOM_MAP
                )
            )
        }

        lastUpdatedLocationTimeStamp = System.currentTimeMillis()
        lastUpdatedCurrentLocationMarkerTimeStamp = System.currentTimeMillis()
    }

    private fun showPrivacyNoticeDialog() {
        // Don't show dialog if it's already shown
        if (!privacyNoticeDialog.isAdded) {
            privacyNoticeDialog.show(
                supportFragmentManager,
                PrivacyNoticeDialogFragment::class.java.name
            )
        }
    }

    private fun createSession() {
        var exception: Exception? = null
        var message: String? = null

        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    InstallStatus.INSTALLED -> {}
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }

                if (!LocationPermissionHelper.hasFineLocationPermission(this)) {
                    LocationPermissionHelper.requestFineLocationPermission(this)
                    return
                }

                // Create the session.
                // Plane finding mode is default on, which will help the dynamic alignment of terrain
                // anchors on ground.
                session = Session( /* context= */this)
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
                exception = e
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "This device does not support AR"
                exception = e
            } catch (e: Exception) {
                message = "Failed to create AR session"
                exception = e
            }
            if (message != null) {
                messageSnackbarHelper.showError(this, message)
                Timber.e("Exception creating session: $exception")
                return
            }
        }

        // Check VPS availability before configure and resume session.
        if (session != null) {
            getLastLocation()
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            configureSession()
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            message = "Camera not available. Try restarting the app."
            exception = e
        } catch (e: GooglePlayServicesLocationLibraryNotLinkedException) {
            message =
                "Google Play Services location library not linked or obfuscated with Proguard."
            exception = e
        } catch (e: FineLocationPermissionNotGrantedException) {
            message = "The Android permission ACCESS_FINE_LOCATION was not granted."
            exception = e
        } catch (e: UnsupportedConfigurationException) {
            message = "This device does not support GeospatialMode.ENABLED."
            exception = e
        } catch (e: SecurityException) {
            message = "Camera failure or the internet permission has not been granted."
            exception = e
        }
        if (message != null) {
            session = null
            messageSnackbarHelper.showError(this, message)
            Timber.e("Exception configuring and resuming the session: $exception")
            return
        }
    }

    private fun getLastLocation() {
        try {
            fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                var latitude = 0.0
                var longitude = 0.0

                if (location != null) {
                    latitude = location.latitude
                    longitude = location.longitude
                } else {
                    Timber.e("Error location is null")
                }

                checkVpsAvailability(latitude, longitude)
            }
        } catch (e: SecurityException) {
            Timber.e("No location permissions granted by User!")
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation(onSuccess: (location: Location) -> Unit) {
        fusedLocationClient?.lastLocation?.addOnSuccessListener {
            onSuccess(it)
        }
    }

    private fun showNavigationPathOnMap(
        currentLocationLatLng: com.google.maps.model.LatLng,
        parkedCarLatLng: com.google.maps.model.LatLng
    ) {
        try {
            val directionsResult: DirectionsResult = DirectionsApi.newRequest(geoApiContext)
                .mode(TravelMode.WALKING)
                .origin(
                    com.google.maps.model.LatLng(
                        currentLocationLatLng.lat,
                        currentLocationLatLng.lng
                    )
                )
                .destination(parkedCarLatLng)
                .await()


            val routes: Array<DirectionsRoute> = directionsResult.routes
            val path = ArrayList<LatLng>()

            //Loop through legs and steps to get encoded polylines of each step
            if (routes.isNotEmpty()) {
                val route: DirectionsRoute = routes[0]
                if (route.legs != null) {
                    for (i in route.legs.indices) {
                        val leg = route.legs[i]
                        if (leg.steps != null) {
                            for (j in leg.steps.indices) {
                                val step = leg.steps[j]
                                if (step.steps != null && step.steps.isNotEmpty()) {
                                    for (k in step.steps.indices) {
                                        val step1 = step.steps[k]
                                        val points1 = step1.polyline
                                        if (points1 != null) {
                                            //Decode polyline and add points to list of route coordinates
                                            val coords1 = points1.decodePath()
                                            for (coord1 in coords1) {
                                                path.add(LatLng(coord1.lat, coord1.lng))
                                            }
                                        }
                                    }
                                } else {
                                    val points = step.polyline
                                    if (points != null) {
                                        //Decode polyline and add points to list of route coordinates
                                        val coords = points.decodePath()
                                        for (coord in coords) {
                                            path.add(LatLng(coord.lat, coord.lng))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (path.isNotEmpty()) {
                val polylineOptions: PolylineOptions = PolylineOptions().addAll(path)
                    .color(ContextCompat.getColor(this, R.color.colorPrimary)).width(10f)

                runOnUiThread {
                    lastAddedNavigationPolyline?.remove()
                    lastAddedNavigationPolyline = googleMap.addPolyline(polylineOptions)

//                    googleMap.moveCamera(
//                        CameraUpdateFactory.newLatLngZoom(
//                            LatLng(currentLocationLatLng.lat, currentLocationLatLng.lng),
//                            DEFAULT_CAMERA_ZOOM_MAP
//                        )
//                    )
                }
            }
        } catch (zeroResultsException: ZeroResultsException) {
            Timber.e("Unable to find path: $zeroResultsException")
        }
    }

    private fun moveCurrentLocationMarker(
        marker: Marker,
        toPosition: LatLng,
        hideMarker: Boolean
    ) {
        val handler = Handler()
        val start = SystemClock.uptimeMillis()
        val proj: Projection = googleMap.projection
        val startPoint: android.graphics.Point = proj.toScreenLocation(marker.position)
        val startLatLng = proj.fromScreenLocation(startPoint)
        val duration: Long = 500
        val interpolator: Interpolator = LinearInterpolator()

        handler.post(object : Runnable {
            override fun run() {
                val elapsed = SystemClock.uptimeMillis() - start
                val t = interpolator.getInterpolation(elapsed.toFloat() / duration)
                val lng = t * toPosition.longitude + (1 - t) * startLatLng.longitude
                val lat = t * toPosition.latitude + (1 - t) * startLatLng.latitude
                marker.position = LatLng(lat, lng)
                if (t < 1.0) {
                    // Post again 16ms later.
                    handler.postDelayed(this, 16)
                } else {
                    marker.isVisible = !hideMarker
                }
            }
        })
    }

    private fun rotateCurrentLocationMarker(
        marker: Marker,
        currentLocationLatLng: com.google.maps.model.LatLng
    ) {
        val bearing: Float = SphericalUtil.computeHeading(
            LatLng(lastLocation!!.latitude, lastLocation!!.longitude),
            LatLng(currentLocationLatLng.lat, currentLocationLatLng.lng)
        ).toFloat()

        if (!isMarkerRotating) {
            val handler = Handler()
            val start = SystemClock.uptimeMillis()
            val startRotation = marker.rotation
            val duration: Long = 2000
            val interpolator: Interpolator = LinearInterpolator()

            handler.post(object : Runnable {
                override fun run() {
                    isMarkerRotating = true
                    val elapsed = SystemClock.uptimeMillis() - start
                    val t: Float = interpolator.getInterpolation(elapsed.toFloat() / duration)
                    val rot = t * bearing + (1 - t) * startRotation
                    val bearing = if (-rot > 180) rot / 2 else rot
                    marker.rotation = bearing
                    if (t < 1.0) {
                        // Post again 16ms later.
                        handler.postDelayed(this, 16)
                    } else {
                        isMarkerRotating = false
                    }
                }
            })
        }
    }

    private fun rotateMap(currentLocationLatLng: com.google.maps.model.LatLng) {
        val bearing: Float = SphericalUtil.computeHeading(
            LatLng(lastLocation!!.latitude, lastLocation!!.longitude),
            LatLng(currentLocationLatLng.lat, currentLocationLatLng.lng)
        ).toFloat()

        val cameraPosition =
            CameraPosition.Builder()
                .target(LatLng(currentLocationLatLng.lat, currentLocationLatLng.lng))
                .bearing(bearing).zoom(googleMap.cameraPosition.zoom).build()
        googleMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }

    private fun checkVpsAvailability(latitude: Double, longitude: Double) {
        val availabilityFuture = checkVpsAvailabilityFuture(latitude, longitude)

        Futures.addCallback(
            availabilityFuture,
            object : FutureCallback<VpsAvailability?> {
                override fun onSuccess(result: VpsAvailability?) {
                    if (result != VpsAvailability.AVAILABLE) {
                        showVpsNotAvailabilityNoticeDialog()
                    }
                }

                override fun onFailure(t: Throwable?) {
                    Timber.e("Error checking VPS availability")
                }
            },
            mainExecutor
        )
    }

    private fun showVpsNotAvailabilityNoticeDialog() {
        val dialog: DialogFragment = VpsAvailabilityNoticeDialogFragment.createDialog()
        dialog.show(supportFragmentManager, VpsAvailabilityNoticeDialogFragment::class.java.name)
    }

    /** Configures the session with feature settings.  */
    private fun configureSession() {
        // Earth mode may not be supported on this device due to insufficient sensor quality.
        if (!session!!.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
            state = State.UNSUPPORTED
            return
        }

        val config = session!!.config
        config.geospatialMode = Config.GeospatialMode.ENABLED
        session!!.configure(config)
        state = State.PRETRACKING

        localizingStartTimestamp = System.currentTimeMillis()
    }

    /** Change behavior depending on the current [State] of the application.  */
    private fun updateGeospatialState(earth: Earth) {
        if (earth.earthState != Earth.EarthState.ENABLED) {
            state = State.EARTH_STATE_ERROR
            return
        }

        if (earth.trackingState != TrackingState.TRACKING) {
            state = State.PRETRACKING
            return
        }

        if (state == State.PRETRACKING) {
            updatePretrackingState(earth)
        } else if (state == State.LOCALIZING) {
            updateLocalizingState(earth)
        } else if (state == State.LOCALIZED) {
            updateLocalizedState(earth)
        }
    }

    /**
     * Handles the updating for [State.PRETRACKING]. In this state, wait for [Earth] to
     * have [TrackingState.TRACKING]. If it hasn't been enabled by now, then we've encountered
     * an unrecoverable [State.EARTH_STATE_ERROR].
     */
    private fun updatePretrackingState(earth: Earth) {
        if (earth.trackingState == TrackingState.TRACKING) {
            state = State.LOCALIZING
            return
        }
    }

    /**
     * Handles the updating for [State.LOCALIZING]. In this state, wait for the horizontal and
     * orientation threshold to improve until it reaches your threshold.
     *
     *
     * If it takes too long for the threshold to be reached, this could mean that GPS data isn't
     * accurate enough, or that the user is in an area that can't be localized with StreetView.
     */
    private fun updateLocalizingState(earth: Earth) {
        val geospatialPose = earth.cameraGeospatialPose

        if (geospatialPose.horizontalAccuracy <= LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS
            && geospatialPose.orientationYawAccuracy <= LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES
        ) {
            state = State.LOCALIZED

            if (anchors.isEmpty()) {
                createAnchorFromSharedPreferences(earth)
            }

            if (anchors.size < MAXIMUM_ANCHORS) {
                runOnUiThread {
                    if (anchors.isNotEmpty()) {
                        binding.ivResetParking.visibility = View.VISIBLE
                    }
                }
            }

            return
        }

        if (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - localizingStartTimestamp)
            > LOCALIZING_TIMEOUT_SECONDS
        ) {
            state = State.LOCALIZING_FAILED
            return
        }
    }

    /**
     * Handles the updating for [State.LOCALIZED]. In this state, check the accuracy for
     * degradation and return to [State.LOCALIZING] if the position accuracies have dropped too
     * low.
     */
    private fun updateLocalizedState(earth: Earth) {
        val geospatialPose = earth.cameraGeospatialPose

        // Check if either accuracy has degraded to the point we should enter back into the LOCALIZING
        // state.
        if (geospatialPose.horizontalAccuracy
            > LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS
            + LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS
            || geospatialPose.orientationYawAccuracy
            > LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES
            + LOCALIZED_ORIENTATION_YAW_ACCURACY_HYSTERESIS_DEGREES
        ) {
            // Accuracies have degenerated, return to the localizing state.
            state = State.LOCALIZING
            localizingStartTimestamp = System.currentTimeMillis()

            runOnUiThread {
                binding.ivResetParking.visibility = View.INVISIBLE
            }

            return
        }

        // Check if the current location marker needs to be moved
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            if (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - lastUpdatedCurrentLocationMarkerTimeStamp)
                > UPDATE_CURRENT_LOCATION_MARKER_INTERVAL_SECONDS
            ) {
                runOnUiThread {
                    moveCurrentLocationMarker(
                        marker = currentLocationMarker!!,
                        toPosition = LatLng(geospatialPose.latitude, geospatialPose.longitude),
                        hideMarker = false
                    )

                    lastUpdatedCurrentLocationMarkerTimeStamp = System.currentTimeMillis()
                }
            }
        }

        // Check if the navigation path needs to be redrawn
        if (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - lastUpdatedLocationTimeStamp)
            > UPDATE_LOCATION_INTERVAL_SECONDS
        ) {
            runOnUiThread {
                rotateCurrentLocationMarker(
                    marker = currentLocationMarker!!,
                    currentLocationLatLng = com.google.maps.model.LatLng(
                        geospatialPose.latitude,
                        geospatialPose.longitude
                    )
                )

                getParkedCarLatLngFromSharedPref()?.let { parkedCarLatLng ->
                    showNavigationPathOnMap(
                        currentLocationLatLng = com.google.maps.model.LatLng(
                            geospatialPose.latitude,
                            geospatialPose.longitude
                        ),
                        parkedCarLatLng = parkedCarLatLng
                    )

                    rotateMap(
                        currentLocationLatLng = com.google.maps.model.LatLng(
                            geospatialPose.latitude,
                            geospatialPose.longitude
                        )
                    )
                }

                // Make current location as the last location
                lastLocation = LatLng(geospatialPose.latitude, geospatialPose.longitude)
            }

            lastUpdatedLocationTimeStamp = System.currentTimeMillis()
        }
    }

    private fun showBalloonHelpTip() {
        val isHelpTooltipShown =
            sharedPreferences!!.getBoolean(SHARED_PREF_IS_HELP_TOOLTIP_SHOWN, false)

        if (!isHelpTooltipShown) {
            val balloon = Balloon.Builder(this)
                .setWidth(BalloonSizeSpec.WRAP)
                .setHeight(BalloonSizeSpec.WRAP)
                .setText(getString(R.string.main_text_help_tooltip))
                .setTextColorResource(R.color.black)
                .setTextSize(15f)
                .setArrowPositionRules(ArrowPositionRules.ALIGN_ANCHOR)
                .setArrowSize(10)
                .setArrowPosition(0.5f)
                .setPadding(12)
                .setCornerRadius(8f)
                .setBackgroundColorResource(R.color.white)
                .setBalloonAnimation(BalloonAnimation.ELASTIC)
                .setLifecycleOwner(this)
                .build()

            binding.ivHelp.showAlignBottom(balloon)

            // Update in shared preferences
            val editor = sharedPreferences!!.edit()
            editor.putBoolean(SHARED_PREF_IS_HELP_TOOLTIP_SHOWN, true)
            editor.commit()
        }
    }

    private fun onHelpClick() {
        binding.ivHelp.setOnClickListener {
            val bottomSheetDialogFragment = HelpFragment.createBottomSheetDialog()
            bottomSheetDialogFragment.show(supportFragmentManager, HelpFragment::class.java.name)
        }
    }

    private fun onResetParkingClick() {
        binding.ivResetParking.setOnClickListener {
            val bottomSheetDialogFragment = ResetParkingFragment.createBottomSheetDialog()
            bottomSheetDialogFragment.show(
                supportFragmentManager,
                ResetParkingFragment::class.java.name
            )
        }
    }

//    /**
//     * Handles the button that creates an anchor.
//     *
//     *
//     * Ensure Earth is in the proper state, then create the anchor. Persist the parameters used to
//     * create the anchors so that the anchors will be loaded next time the app is launched.
//     */
//    private fun handleSetAnchorButton() {
//        val earth = session!!.earth
//        if (earth == null || earth.trackingState != TrackingState.TRACKING) {
//            return
//        }
//        val geospatialPose = earth.cameraGeospatialPose
//        createAnchorWithGeospatialPose(earth, geospatialPose)
//    }

    /** Creates anchor with the provided GeospatialPose, either from camera or HitResult.  */
    private fun createAnchorWithGeospatialPose(earth: Earth, geospatialPose: GeospatialPose) {
        val latitude = geospatialPose.latitude
        val longitude = geospatialPose.longitude
        val altitude = geospatialPose.altitude
        val quaternion = geospatialPose.eastUpSouthQuaternion

        createAnchor(earth, latitude, longitude, altitude, quaternion)

        runOnUiThread {
            vibrate()
            toast(getString(R.string.main_text_parking_location_marked))
        }

        showNavigationPathOnMap(
            currentLocationLatLng = com.google.maps.model.LatLng(latitude, longitude),
            parkedCarLatLng = com.google.maps.model.LatLng(latitude, longitude)
        )

        storeAnchorParameters(latitude, longitude, altitude, quaternion)

        val message = resources
            .getQuantityString(R.plurals.status_anchors_set, anchors.size, anchors.size)


        runOnUiThread { binding.ivResetParking.visibility = View.VISIBLE }

        if (clearedAnchorsAmount != null) {
            clearedAnchorsAmount = null
        }
    }

    /** Create an anchor at a specific geodetic location using a EUS quaternion.  */
    private fun createAnchor(
        earth: Earth, latitude: Double, longitude: Double, altitude: Double, quaternion: FloatArray
    ) {
        val anchor = earth.createAnchor(
            latitude,
            longitude,
            altitude,
            quaternion[0],
            quaternion[1],
            quaternion[2],
            quaternion[3]
        )

        (anchors as ArrayList<Anchor>).add(anchor)

        // Add parked car marker
        runOnUiThread {
            parkedCarMarker = googleMap.addMarker(
                MarkerOptions().position(
                    LatLng(
                        latitude,
                        longitude
                    )
                )
                    .icon(BitmapDescriptorFactory.fromBitmap(parkedCarBitmap!!))
            )
        }
    }

    /**
     * Helper function to store the parameters used in anchor creation in [SharedPreferences].
     */
    private fun storeAnchorParameters(
        latitude: Double, longitude: Double, altitude: Double, quaternion: FloatArray
    ) {
        val anchorParameterSet = sharedPreferences!!.getStringSet(
            SHARED_PREF_SAVED_ANCHOR,
            HashSet()
        )

        val newAnchorParameterSet = HashSet(anchorParameterSet)
        val editor = sharedPreferences!!.edit()
        val terrain = if (isTerrainAnchorMode) "Terrain" else ""

        newAnchorParameterSet.add(
            String.format(
                "$terrain%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                latitude,
                longitude,
                altitude,
                quaternion[0],
                quaternion[1],
                quaternion[2],
                quaternion[3]
            )
        )

        editor.putStringSet(
            SHARED_PREF_SAVED_ANCHOR,
            newAnchorParameterSet
        )

        editor.commit()
    }

    private fun clearAnchorsFromSharedPreferences() {
        val editor = sharedPreferences!!.edit()

        editor.putStringSet(
            SHARED_PREF_SAVED_ANCHOR,
            null
        )

        editor.commit()
    }

    private fun getParkedCarLatLngFromSharedPref(): com.google.maps.model.LatLng? {
        var parkedCarLatLng: com.google.maps.model.LatLng? = null

        val anchorParameterSet = sharedPreferences!!.getStringSet(
            SHARED_PREF_SAVED_ANCHOR,
            null
        )

        if (anchorParameterSet != null) {
            for (anchorParameters in anchorParameterSet) {
                val isTerrain = anchorParameters.contains("Terrain")
                var formattedAnchorParameter = anchorParameters

                if (isTerrain) {
                    formattedAnchorParameter = formattedAnchorParameter.replace("Terrain", "")
                }
                val parameters = formattedAnchorParameter.split(",").toTypedArray()
                if (parameters.size != 7) {
                    Timber.e("Invalid number of anchor parameters. Expected four, found: ${parameters.size}")
                    continue
                }

                parkedCarLatLng =
                    com.google.maps.model.LatLng(parameters[0].toDouble(), parameters[1].toDouble())
            }
        }

        return parkedCarLatLng
    }

    /** Creates all anchors that were stored in the [SharedPreferences].  */
    private fun createAnchorFromSharedPreferences(earth: Earth) {
        val anchorParameterSet = sharedPreferences!!.getStringSet(
            SHARED_PREF_SAVED_ANCHOR,
            null
        )
            ?: return

        for (anchorParameters in anchorParameterSet) {
            val isTerrain = anchorParameters.contains("Terrain")
            var formattedAnchorParameter = anchorParameters
            if (isTerrain) {
                formattedAnchorParameter = formattedAnchorParameter.replace("Terrain", "")
            }
            val parameters = formattedAnchorParameter.split(",").toTypedArray()
            if (parameters.size != 7) {
                Timber.e("Invalid number of anchor parameters. Expected four, found ${parameters.size}")
                continue
            }
            val latitude = parameters[0].toDouble()
            val longitude = parameters[1].toDouble()
            val altitude = parameters[2].toDouble()
            val quaternion = floatArrayOf(
                parameters[3].toFloat(),
                parameters[4].toFloat(),
                parameters[5].toFloat(),
                parameters[6].toFloat()
            )

            createAnchor(earth, latitude, longitude, altitude, quaternion)
            getCurrentLocation { currentLocation ->
                showNavigationPathOnMap(
                    currentLocationLatLng = com.google.maps.model.LatLng(
                        currentLocation.latitude,
                        currentLocation.longitude
                    ),
                    parkedCarLatLng = com.google.maps.model.LatLng(
                        latitude,
                        longitude
                    )
                )
            }
        }

        runOnUiThread { binding.ivResetParking.visibility = View.VISIBLE }
    }


    override fun onDialogPositiveClick(dialog: DialogFragment?) {
        if (!sharedPreferences!!.edit().putBoolean(
                SHARED_PREF_IS_GEOSPATIAL_ACCESS_ALLOWED,
                true
            ).commit()
        ) {
            throw AssertionError("Could not save the user preference to SharedPreferences!")
        }

        Timber.e("Create session from onDialogPositiveClick")
        createSession()
    }

    override fun onDialogContinueClick(dialog: DialogFragment?) {
        dialog?.dismiss()
    }

    /**
     * Called when the Confirm button of the ResetParkingFragment is clicked.
     */
    override fun onConfirmResetParking(bottomSheetDialogFragment: BottomSheetDialogFragment) {
        clearedAnchorsAmount = anchors.size

        for (anchor in anchors) {
            anchor.detach()
        }

        (anchors as ArrayList<Anchor>).clear()
        clearAnchorsFromSharedPreferences()

        // Remove marker
        parkedCarMarker?.remove()

        // Remove navigation polyline
        lastAddedNavigationPolyline?.remove()

        binding.ivResetParking.visibility = View.INVISIBLE
    }

    /**
     * Handles the most recent user tap.
     *
     *
     * We only ever handle one tap at a time, since this app only allows for a single anchor.
     *
     * @param frame the current AR frame
     * @param cameraTrackingState the current camera tracking state
     */
    private fun handleTap(frame: Frame?, cameraTrackingState: TrackingState?) {
        // Handle taps. Handling only one tap per frame, as taps are usually low frequency
        // compared to frame rate.
        Timber.e("Anchors size: ${anchors.size}")
        synchronized(singleTapLock) {
            if (queuedSingleTap == null || anchors.size > MAXIMUM_ANCHORS || cameraTrackingState != TrackingState.TRACKING
            ) {
                queuedSingleTap = null
                return
            }

            val earth = session!!.earth
            if (earth == null || earth.trackingState != TrackingState.TRACKING) {
                queuedSingleTap = null
                return
            }

            if (frame != null && anchors.size < MAXIMUM_ANCHORS) {
                for (hit in frame.hitTest(queuedSingleTap)) {
                    if (shouldCreateAnchorWithHit(hit)) {
                        val hitPose = hit.hitPose
                        val geospatialPose = earth.getGeospatialPose(hitPose)
                        createAnchorWithGeospatialPose(earth, geospatialPose)
                        break // Only handle the first valid hit.
                    }
                }
            }

            queuedSingleTap = null
        }
    }

    /** Returns `true` if and only if the hit can be used to create an Anchor reliably.  */
    private fun shouldCreateAnchorWithHit(hit: HitResult): Boolean {
        val trackable = hit.trackable
        if (trackable is Plane) {
            // Check if the hit was within the plane's polygon.
            return trackable.isPoseInPolygon(hit.hitPose)
        } else if (trackable is Point) {
            // Check if the hit was against an oriented point.
            return trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        }

        return false
    }

    // Wrapper for checkVpsAvailability. Do not block on this future on the Main thread; deadlock will
    // result.
    private fun checkVpsAvailabilityFuture(
        latitude: Double, longitude: Double
    ): ListenableFuture<VpsAvailability> {
        return CallbackToFutureAdapter.getFuture { completer: CallbackToFutureAdapter.Completer<VpsAvailability> ->
            val future = session!!.checkVpsAvailabilityAsync(
                latitude,
                longitude
            ) { availability: VpsAvailability ->
                completer.set(
                    availability
                )
            }

            completer.addCancellationListener(
                { val cancel = future.cancel() }
            ) { obj: Runnable -> obj.run() }
            "checkVpsAvailabilityFuture"
        }
    }
}
