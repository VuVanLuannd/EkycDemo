package com.vnd.ekycnavite

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.core.Point
import vn.vndgroup.ekyc.camerax.CameraManager
import vn.vndgroup.ekyc.camerax.CameraMode
import vn.vndgroup.ekyc.camerax.CameraOption
import vn.vndgroup.ekyc.facedetection.AnalyzeResult
import vn.vndgroup.ekyc.facedetection.FaceContourDetectionProcessor
import vn.vndgroup.ekyc.global.Constants
import vn.vndgroup.ekyc.global.Utils.releaseBitmap
import vn.vndgroup.ekyc.global.Utils.writeBitmapToFile
import vn.vndgroup.ekyc.objectdetection.*
import vn.vndgroup.ekycnavite.R
import vn.vndgroup.ekycnavite.VerifyProfileStep
import vn.vndgroup.ekycnavite.databinding.ActivityPreviewBinding
import java.util.*
import kotlin.collections.ArrayList


class CameraPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPreviewBinding
    private lateinit var cameraManager: CameraManager
    private var faceDetectionProcessor: FaceContourDetectionProcessor? = null
    private var cornersDetectionProcessor: CardCornersDetectionProcessor? = null
    private var analyzeTimer: Timer? = null
    private var hasStartedCamera = false
    private lateinit var overlayTintList: ColorStateList
    private var isCaptured = false
    private var mode = MODE_TAKE_SELFIE
    private var borderMarginHorizontal = 0
    private var borderMarginVertical = 0

    companion object {
        const val MODE_TAKE_SELFIE = 169
        const val MODE_CAPTURE_FRONT_ID_CARD = 269
        const val MODE_CAPTURE_BACK_ID_CARD = 369

        private const val REQUEST_CODE_PERMISSIONS = 6969
        private val REQUIRED_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA)
        private const val ANALYZE_PERIOD = 200L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.ivTakePhoto.setOnClickListener {
            if (!isCaptured) {
                isCaptured = true
                cornersDetectionProcessor?.process(binding.previewView.bitmap)
            }
        }

        val step: VerifyProfileStep =
            intent.getSerializableExtra(Constants.KEY_DATA) as VerifyProfileStep
        mode = when (step) {
            VerifyProfileStep.SELFIE -> MODE_TAKE_SELFIE
            VerifyProfileStep.FRONT_ID_CARD -> MODE_CAPTURE_FRONT_ID_CARD
            VerifyProfileStep.BACK_ID_CARD -> MODE_CAPTURE_BACK_ID_CARD
        }
        setupViews(step)
        checkPermissions()
    }

    private fun setupViews(step: VerifyProfileStep) {
        binding.textViewTitle.text =
            when (step) {
                VerifyProfileStep.SELFIE -> "Ảnh chân dung"
                VerifyProfileStep.FRONT_ID_CARD -> "Ảnh mặt trước CMND/CCCD"
                VerifyProfileStep.BACK_ID_CARD -> "Ảnh mặt sau CMND/CCCD"
            }

        val set = ConstraintSet()
        set.clone(binding.root)
        set.setDimensionRatio(
            R.id.previewView,
            if (mode == MODE_TAKE_SELFIE) "3:4" else "4:3"
        )
        set.applyTo(binding.root)
        borderMarginHorizontal = resources.getDimensionPixelSize(
            if (mode == MODE_TAKE_SELFIE)
                R.dimen.selfie_border_margin_horizontal
            else R.dimen.id_card_border_margin_horizontal
        )
        borderMarginVertical = resources.getDimensionPixelSize(
            if (mode == MODE_TAKE_SELFIE)
                R.dimen.selfie_border_margin_vertical
            else R.dimen.id_card_border_margin_vertical
        )

        val params: ConstraintLayout.LayoutParams =
            binding.viewOverlay.layoutParams as ConstraintLayout.LayoutParams
        params.marginStart = borderMarginHorizontal
        params.marginEnd = borderMarginHorizontal
        params.topMargin = borderMarginVertical
        params.bottomMargin = borderMarginVertical
        binding.viewOverlay.layoutParams = params

        binding.viewOverlay.layoutParams = params
        binding.textViewMessage.text =
            if (mode == MODE_TAKE_SELFIE)
                "Định vị chuẩn khuôn mặt của bạn trong khung."
            else "Xin vui lòng đặt giấy tờ nằm vừa khung hình, chụp đủ ánh sáng và rõ nét."
    }

    private fun capture() {
        if (!isCaptured) {
            isCaptured = true
            analyzeTimer?.cancel()
            cameraManager.doAction()
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val cameraExecutor = ContextCompat.getMainExecutor(this)

        cameraManager = CameraManager.Builder()
            .setActivity(this)
            .setCameraPreview(binding.previewView)
            .setCameraMode(CameraMode.MODE_PHOTO)
            .setCameraExecutor(cameraExecutor)
            .setCameraSelectorOption(
                if (mode == MODE_TAKE_SELFIE)
                    CameraOption.LENS_FACING_FRONT else CameraOption.LENS_FACING_BACK
            )
            .setAspectRatio(
                if (mode == MODE_TAKE_SELFIE)
                    Constants.RATIO_3_4 else Constants.RATIO_4_3
            )
            .setCallback(object : CameraManager.CameraCallback {
                override fun onSuccess(filePath: String) {
                    onDetectedFaceSuccessfully(BitmapFactory.decodeFile(filePath), true)
                }

                override fun onError(errorMessage: String?) {
                    setActivityResult(false)
                }
            })
            .build()

        try {
            cameraManager.startCamera()
            startDetectionProcessor()
            hasStartedCamera = true
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể khởi động Camera!", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
        }
    }


    private fun setActivityResult(
        success: Boolean,
        bitmap: Bitmap? = null,
        idCardType: String? = null
    ) {
        if (success && bitmap != null) {
            val filePath = this.writeBitmapToFile(bitmap, "IMG_")
            val intent = Intent()
            intent.putExtra(Constants.KEY_BITMAP, filePath)
            intent.putExtra(Constants.KEY_ID_CARD_TYPE, idCardType)
            setResult(RESULT_OK, intent)
        } else {
            Toast.makeText(this, "Phân tích dữ liệu thất bại!", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private fun startDetectionProcessor() {
        overlayTintList = ContextCompat.getColorStateList(
            this,
            R.color.green
        )!!

        if (mode == MODE_TAKE_SELFIE) {
            faceDetectionProcessor =
                FaceContourDetectionProcessor(
                    this,
                    object : FaceContourDetectionProcessor.Callback {
                        override fun onAnalyzed(bitmap: Bitmap, analyzeResult: AnalyzeResult) {
                            onDetectedFaceSuccessfully(bitmap, analyzeResult == AnalyzeResult.OK)
                        }
                    },
                    borderMarginHorizontal.toFloat(),
                    borderMarginVertical.toFloat()
                )
        } else {
            cornersDetectionProcessor = CardCornersDetectionProcessor(
                this,
                object : CardCornersDetectionProcessor.Callback {
                    override fun onDetectedIdCardBitmap(result: AlignCardResult) {
                        onDetectedIdCardSuccessfully(result)
                    }

                    override fun onAnalyzedBitmap(result: IdCardInfo) {
                        onAnalyzedIdCardImage(result)
                    }

                },
                borderMarginHorizontal.toFloat(),
                borderMarginVertical.toFloat(),
                mode == MODE_CAPTURE_FRONT_ID_CARD
            )
        }
        startAnalyzing()
    }

    private var isValidPhoto = false

    private fun onAnalyzedIdCardImage(result: IdCardInfo) {
        isValidPhoto = if (result.checkHasEnoughInfo()) {
            val listPoint = ArrayList<Point>()
            listPoint.addAll(result.cardCorners)
            listPoint.addAll(result.fingerprintPoints)
            listPoint.add(result.emblem!!)
            var alignIdBack = AlignIdImageHelper.BACK_ID_CARD_POINTS
            var equals: Boolean
            result.type.let {
                equals = it.equals(IdCardType.BACK_CITIZEN_IDENTIFICATION)
            }
            if (equals) {
                alignIdBack = AlignIdImageHelper.BACK_ID_CARD_CCCD_MS_POINTS
            }
            (mode == MODE_CAPTURE_FRONT_ID_CARD && listPoint.size == AlignIdImageHelper.FRONT_ID_CARD_POINTS) ||
                    (mode == MODE_CAPTURE_BACK_ID_CARD && listPoint.size == alignIdBack)
        } else {
            false
        }
        binding.viewOverlay.backgroundTintList =
            if (isValidPhoto) overlayTintList else null
        if (isValidPhoto) {
            if (result == null) {
                Toast.makeText(
                    this@CameraPreviewActivity,
                    "Không thành công, xin thử lại.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                setActivityResult(true, result.bitmap, result.type)
            }
        }
        result.bitmap.releaseBitmap()
    }

    private fun onDetectedFaceSuccessfully(bitmap: Bitmap, result: Boolean) {
        binding.viewOverlay.backgroundTintList = if (result) overlayTintList else null
        if (result && !AlignIdImageHelper.isBlurredImage(bitmap, lowThreshold = true)) {
            faceDetectionProcessor?.stop()
            setActivityResult(true, bitmap)
        }
    }

    private fun onDetectedIdCardSuccessfully(result: AlignCardResult) {
        cornersDetectionProcessor?.close()
        binding.viewOverlay.backgroundTintList = overlayTintList
        setActivityResult(true, result.bitmap, result.cardType)
    }

    private fun startAnalyzing() {
        analyzeTimer?.cancel()

        if (faceDetectionProcessor == null && cornersDetectionProcessor == null) {
            return
        }

        val analyzeTimerTask = object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    faceDetectionProcessor?.analyze(binding.previewView)
                    cornersDetectionProcessor?.process(binding.previewView.bitmap)
                }
            }
        }
        analyzeTimer = Timer()
        analyzeTimer!!.scheduleAtFixedRate(analyzeTimerTask, ANALYZE_PERIOD, ANALYZE_PERIOD)
    }

    override fun onPause() {
        super.onPause()

        analyzeTimer?.cancel()
    }

    override fun onResume() {
        super.onResume()
        if (hasStartedCamera) {
            startAnalyzing()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissions() {
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceDetectionProcessor?.stop()
        cornersDetectionProcessor?.close()
    }

    class Contract : ActivityResultContract<VerifyProfileStep, Intent?>() {
        private var step: VerifyProfileStep = VerifyProfileStep.SELFIE

        override fun createIntent(context: Context, input: VerifyProfileStep): Intent {
            step = input
            val intent = Intent(context, CameraPreviewActivity::class.java)
            intent.putExtra(Constants.KEY_DATA, step)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            return intent
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Intent? {
            return if (resultCode == RESULT_OK) {
                return Intent()
                    .putExtra(Constants.KEY_DATA, step)
                    .putExtra(Constants.KEY_BITMAP, intent?.getStringExtra(Constants.KEY_BITMAP))
                    .putExtra(
                        Constants.KEY_ID_CARD_TYPE,
                        intent?.getStringExtra(Constants.KEY_ID_CARD_TYPE)
                    )
            } else null
        }
    }
}