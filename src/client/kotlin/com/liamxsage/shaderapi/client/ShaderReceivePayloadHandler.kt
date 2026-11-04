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
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.*

class ShaderReceivePayloadHandler : ClientPlayNetworking.PlayPayloadHandler<ShaderReceivePayload> {
    override fun receive(payload: ShaderReceivePayload, context: ClientPlayNetworking.Context) {
        logger.info("ShaderReceivePayload received")
        val shaderUrl: String = payload.shaderUrl ?: return
        val shaderHash: String = payload.hash ?: return
        val serverGroup: String = payload.serverGroup.ifEmpty { "global" }
        val decision = ConfigManager.getShaderPackAcceptState(serverGroup)

        if (decision == ShaderPackAcceptState.DENY) {
            sendShaderStatusResponse(ShaderStatusResponse.DENIED)
            return
        }

        if (!shaderHash.matches(Regex("[0-9a-fA-F]{40}"))) {
            logger.warn("Shader pack hash is not a SHA-1 digest")
            sendShaderStatusResponse(ShaderStatusResponse.DOWNLOAD_FAILED)
            return
        }

        if (decision == ShaderPackAcceptState.ACCEPT) {
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

    private fun testForShaderPack(hash: String): Boolean {
        try {
            val file = File(Minecraft.getInstance().gameDirectory, "downloads/$hash.zip")
            if (!file.isFile) return false
            if (hashMatches(file, hash)) return true
            logger.warn("Cached shader pack has an incorrect SHA-1 digest")
            file.delete()
        } catch (exception: Exception) {
            logger.warn("Could not validate cached shader pack", exception)
        }
        return false
    }

    private fun hashMatches(file: File, hash: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-1")
        DigestInputStream(file.inputStream(), digest).use { it.copyTo(OutputStream.nullOutputStream()) }
        return HexFormat.of().formatHex(digest.digest()).equals(hash, ignoreCase = true)
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
        var tmpFile: File? = null
        var connection: HttpURLConnection? = null
        try {
            val minecraftClient = Minecraft.getInstance()
            val directory = File(minecraftClient.gameDirectory, "downloads")
            if (!directory.isDirectory && !directory.mkdirs()) return null
            tmpFile = File.createTempFile("shaderapi-", ".tmp", directory)
            connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.inputStream.use { inputStream ->
                tmpFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (!hashMatches(tmpFile, hash)) {
                logger.warn("Downloaded shader pack has an incorrect SHA-1 digest")
                return null
            }
            val destination = File(directory, "$hash.zip")
            return if (tmpFile.renameTo(destination)) destination else null
        } catch (exception: Exception) {
            logger.warn("Could not download shader pack", exception)
        } finally {
            connection?.disconnect()
            tmpFile?.delete()
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
