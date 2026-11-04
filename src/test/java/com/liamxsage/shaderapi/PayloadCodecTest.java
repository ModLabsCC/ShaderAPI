package com.liamxsage.shaderapi;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayloadCodecTest {
    private RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    @Test
    void requestKeepsTheSingleBooleanWireFormat() {
        var buffer = buffer();
        try {
            ShaderRequestPayload.CODEC.encode(buffer, new ShaderRequestPayload(true));
            assertEquals(1, buffer.readableBytes());
            assertEquals(1, buffer.getUnsignedByte(0));
            assertTrue(ShaderRequestPayload.CODEC.decode(buffer).alwaysTrue());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void receiveKeepsUrlHashAndServerGroupInProtocolOrder() {
        var buffer = buffer();
        try {
            ShaderReceivePayload.CODEC.encode(buffer,
                    new ShaderReceivePayload("https://example.com/shader.zip", "abc123", "lobby"));
            assertEquals("https://example.com/shader.zip", buffer.readUtf());
            assertEquals("abc123", buffer.readUtf());
            assertEquals("lobby", buffer.readUtf());
            assertEquals(0, buffer.readableBytes());
            buffer.readerIndex(0);
            assertEquals(new ShaderReceivePayload("https://example.com/shader.zip", "abc123", "lobby"),
                    ShaderReceivePayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void receiveDecodesEmptyGroupWithoutChangingServerData() {
        var buffer = buffer();
        try {
            buffer.writeUtf("https://example.com/shader.zip");
            buffer.writeUtf("abc123");
            buffer.writeUtf("");
            var payload = ShaderReceivePayload.CODEC.decode(buffer);
            assertEquals("https://example.com/shader.zip", payload.shaderUrl());
            assertEquals("abc123", payload.hash());
            assertEquals("", payload.serverGroup());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void statusResponseKeepsTheUtf8WireFormat() {
        var buffer = buffer();
        try {
            ShaderStatusResponsePayload.CODEC.encode(buffer, new ShaderStatusResponsePayload("APPLY_FAILED"));
            assertEquals("APPLY_FAILED", buffer.readUtf());
            assertEquals(0, buffer.readableBytes());
            buffer.readerIndex(0);
            assertEquals("APPLY_FAILED", ShaderStatusResponsePayload.CODEC.decode(buffer).statusResponse());
        } finally {
            buffer.release();
        }
    }
}
