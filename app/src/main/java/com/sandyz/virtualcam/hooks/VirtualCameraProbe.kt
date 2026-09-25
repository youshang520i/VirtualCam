package com.sandyz.virtualcam.hooks

import android.content.res.XModuleResources
import com.sandyz.virtualcam.utils.xLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * 抖音直播相机路径探测模块（临时）。
 * 纯日志，不改变任何行为。用于定位抖音直播 SDK 的摄像头采集走哪条 API 路径。
 */
class VirtualCameraProbe : IHook {
    override fun getName(): String = "抖音相机路径探测"
    override fun getSupportedPackages() = listOf(
        "com.ss.android.ugc.aweme",
    )

    override fun init(cl: ClassLoader?) {
    }

    override fun registerRes(moduleRes: XModuleResources?) {
    }

    override fun hook(lpparam: LoadPackageParam?) {
        val cl = lpparam?.classLoader ?: return

        // camera2 入口
        hookAll(cl, "android.hardware.camera2.CameraManager",
            listOf("openCamera", "openCameraForUid"))
        // camera2 管道（所有重载）
        hookAll(cl, "android.hardware.camera2.impl.CameraDeviceImpl",
            listOf("createCaptureSession", "createCaptureSessionByOutputConfigurations", "createConstrainedHighSpeedCaptureSession"))
        hookAll(cl, "android.hardware.camera2.CameraDevice",
            listOf("createCaptureSession", "createCaptureSessionByOutputConfigurations"))
        // camera2 请求
        hookAll(cl, "android.hardware.camera2.CaptureRequest.Builder",
            listOf("addTarget", "build"))

        // camera1 入口
        hookAll(cl, "android.hardware.Camera",
            listOf("open", "openLegacy", "startPreview", "setPreviewTexture", "setPreviewDisplay", "setPreviewCallback", "setPreviewCallbackWithBuffer"))

        // 采集帧到达点
        hookAll(cl, "android.media.ImageReader", listOf("newInstance", "acquireLatestImage"))
        hookAll(cl, "android.graphics.SurfaceTexture",
            listOf("setOnFrameAvailableListener", "updateTexImage", "attachToGLContext", "detachFromGLContext"))
    }

    private fun hookAll(cl: ClassLoader, className: String, methods: List<String>) {
        try {
            val clazz = XposedHelpers.findClass(className, cl)
            methods.forEach { m ->
                try {
                    XposedBridge.hookAllMethods(clazz, m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val args = param.args.joinToString(", ") { it?.javaClass?.simpleName ?: "null" }
                            xLog("[探测] $className.$m($args)")
                        }
                    })
                } catch (t: Throwable) {
                    xLog("[探测] hook $className.$m 失败: ${t.message}")
                }
            }
            xLog("[探测] 已挂载 $className")
        } catch (t: Throwable) {
            xLog("[探测] 未找到 $className: ${t.message}")
        }
    }
}
