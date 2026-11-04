package com.liamxsage.shaderapi.client

import com.liamxsage.shaderapi.Constants.logger
import com.liamxsage.shaderapi.ShaderReceivePayload
import com.liamxsage.shaderapi.client.config.ConfigManager
import com.liamxsage.shaderapi.client.config.ShaderPackAcceptState
import com.liamxsage.shaderapi.client.functions.sendShaderStatusResponse
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.network.chat.Component
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.*
import kotlin.reflect.jvm.jvmName

class ShaderReceivePayloadHandler : ClientPlayNetworking.PlayPayloadHandler<ShaderReceivePayload> {
    override fun receive(payload: ShaderReceivePayload, context: ClientPlayNetworking.Context) {
        logger.info("ShaderReceivePayload received")
        val shaderUrl: String = payload.shaderUrl ?: return
        val shaderHash: String = payload.hash ?: return
        val serverGroup: String = payload.serverGroup.ifEmpty { "global" }

        if (testForServerGroupAlwaysAccept(serverGroup)) {
            downloadAndApplyShaderPack(shaderUrl, shaderHash)
            return
        }

        val client = context.client()
        client.execute {
            client.gui.setScreen(ConfirmScreen({ accept: Boolean ->
                if (accept) {
                    downloadAndApplyShaderPack(shaderUrl, shaderHash)
                    context.client().gui.setScreen(null)
                    ConfigManager.addServerGroup(serverGroup, ShaderPackAcceptState.ACCEPT)
                    return@ConfirmScreen
                }

                context.client().gui.setScreen(null)
                ConfigManager.addServerGroup(serverGroup, ShaderPackAcceptState.DENY)
                sendShaderStatusResponse(ShaderStatusResponse.DENIED)

            }, Component.literal("Shader Pack Available"), Component.literal("Do you want to download and apply the shader pack?")))
        }
    }

    private fun testForServerGroupAlwaysAccept(serverGroup: String): Boolean {
        return ConfigManager.getShaderPackAcceptState(serverGroup) == ShaderPackAcceptState.ACCEPT
    }

    private fun testForShaderPack(hash: String): Boolean {
        try {
            val file = File(Minecraft.getInstance().gameDirectory, "downloads/$hash.zip")
            return file.exists()
        } catch (exception: Exception) {
            exception.printStackTrace()
            sendShaderStatusResponse(exception::class.jvmName.uppercase())
        }
        return false
    }

    private fun downloadAndApplyShaderPack(url: String, shaderHash: String) {

        if (testForShaderPack(shaderHash)) {
            applyShaderPack(File(Minecraft.getInstance().gameDirectory, "downloads/$shaderHash.zip"))
            return
        }

        // Download the shader pack
        val shaderPackFile: File = downloadShaderPack(url, shaderHash) ?: run {
            logger.warn("Shader Pack Download failed")
            sendShaderStatusResponse(ShaderStatusResponse.DOWNLOAD_FAILED)
            return
        }


        // Apply the shader pack
        applyShaderPack(shaderPackFile)
    }

    private fun applyShaderPack(shaderPackFile: File) {
        if (!IrisBridge.isPresent()) {
            sendShaderStatusResponse(ShaderStatusResponse.APPLY_FAILED)
            return
        }

        if (!IrisBridge.areShadersEnabled() || !IrisBridge.currentPackName().equals(shaderPackFile.name, true)) {
            try {
                IrisBridge.setShaderPackName("../downloads/${shaderPackFile.name}")
                IrisBridge.setShadersEnabled(true)
                IrisBridge.saveConfig()
                IrisBridge.reload()
                sendShaderStatusResponse(ShaderStatusResponse.SUCCESS)
            } catch (exception: Exception) {
                exception.printStackTrace()
                sendShaderStatusResponse(ShaderStatusResponse.APPLY_FAILED)
            }
        }
    }

    private fun downloadShaderPack(url: String, hash: String): File? {
        // Implementiere den Download des Shaderpacks von der URL und speichere es lokal ab
        // Gib die Datei zurück, wenn der Download erfolgreich war, andernfalls null
        try {
            val minecraftClient = Minecraft.getInstance()
            val tempUUID = UUID.randomUUID()
            val tmpFile = File(minecraftClient.gameDirectory, "downloads/$tempUUID")
            val urlConnection = URI(url).toURL().openConnection() as HttpURLConnection
            urlConnection.connect()
            urlConnection.inputStream.use { inputStream ->
                tmpFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            val renamed = tmpFile.renameTo(File(minecraftClient.gameDirectory, "downloads/$hash.zip"))
            return if (renamed) {
                File(minecraftClient.gameDirectory, "downloads/$hash.zip")
            } else {
                sendShaderStatusResponse(ShaderStatusResponse.IO_EXCEPTION)
                null
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            sendShaderStatusResponse(exception::class.jvmName.uppercase())
        }
        return null
    }
}

private object IrisBridge {
    private val irisClass by lazy { Class.forName("net.irisshaders.iris.Iris") }
    private val getIrisConfigMethod by lazy { irisClass.getMethod("getIrisConfig") }
    private val getCurrentPackNameMethod by lazy { irisClass.getMethod("getCurrentPackName") }
    private val reloadMethod by lazy { irisClass.getMethod("reload") }

    private fun irisConfig(): Any = getIrisConfigMethod.invoke(null)

    fun isPresent(): Boolean = try {
        irisClass
        true
    } catch (_: Exception) {
        false
    }

    fun areShadersEnabled(): Boolean {
        val config = irisConfig()
        return config.javaClass.getMethod("areShadersEnabled").invoke(config) as Boolean
    }

    fun currentPackName(): String {
        return getCurrentPackNameMethod.invoke(null) as? String ?: ""
    }

    fun setShaderPackName(name: String) {
        val config = irisConfig()
        config.javaClass.getMethod("setShaderPackName", String::class.java).invoke(config, name)
    }

    fun setShadersEnabled(enabled: Boolean) {
        val config = irisConfig()
        config.javaClass.getMethod("setShadersEnabled", java.lang.Boolean.TYPE).invoke(config, enabled)
    }

    fun saveConfig() {
        val config = irisConfig()
        config.javaClass.getMethod("save").invoke(config)
    }

    fun reload() {
        reloadMethod.invoke(null)
    }
}