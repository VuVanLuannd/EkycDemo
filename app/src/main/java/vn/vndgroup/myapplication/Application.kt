package vn.vndgroup.myapplication

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import vn.vndgroup.ekyc.global.EKYCManager

class Application : Application(), CameraXConfig.Provider {
    companion object{
        var enableAuto: Boolean = true
    }
    override fun onCreate() {
        super.onCreate()
        EKYCManager.initialize()

    }

    override fun getCameraXConfig(): CameraXConfig {
        return Camera2Config.defaultConfig()
    }

}