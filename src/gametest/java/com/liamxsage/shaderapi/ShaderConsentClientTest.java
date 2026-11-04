package com.liamxsage.shaderapi;

import com.liamxsage.shaderapi.client.ShaderReceivePayloadHandler;
import com.liamxsage.shaderapi.client.config.ConfigManager;
import com.liamxsage.shaderapi.client.config.ShaderPackAcceptState;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.gui.screens.ConfirmScreen;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ShaderConsentClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        var status = new AtomicReference<String>();
        var statuses = new CopyOnWriteArrayList<String>();
        ClientPlayNetworking.registerGlobalReceiver(ShaderReceivePayload.ID, new ShaderReceivePayloadHandler());
        ServerPlayNetworking.registerGlobalReceiver(ShaderStatusResponsePayload.ID,
                (payload, ignored) -> { statuses.add(payload.statusResponse()); status.set(payload.statusResponse()); });
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(client -> ConfigManager.INSTANCE.addServerGroup("denied-test", ShaderPackAcceptState.DENY));
            world.getServer().runOnServer(server -> ServerPlayNetworking.send(world.getConnection().getServerPlayer(),
                    new ShaderReceivePayload("https://example.com/pack.zip", "0000000000000000000000000000000000000000", "denied-test")));
            context.waitFor(client -> status.get() != null || client.gui.screen() instanceof ConfirmScreen, 200);
            if (!"DENIED".equals(status.get())) throw new AssertionError("A saved rejection must send DENIED without prompting");
            context.runOnClient(client -> {
                if (client.gui.screen() instanceof ConfirmScreen) throw new AssertionError("Saved rejection prompted again");
            });
            statuses.clear();
            world.getServer().runOnServer(server -> ServerPlayNetworking.send(world.getConnection().getServerPlayer(),
                    new ShaderReceivePayload("https://example.com/pack.zip", "../../unsafe", "denied-test")));
            context.waitFor(client -> !statuses.isEmpty(), 200);
            if (!statuses.equals(List.of("DENIED")))
                throw new AssertionError("A saved rejection must remain DENIED even for an invalid offer: " + statuses);
            statuses.clear();
            context.runOnClient(client -> ConfigManager.INSTANCE.addServerGroup("accepted-test", ShaderPackAcceptState.ACCEPT));
            world.getServer().runOnServer(server -> ServerPlayNetworking.send(world.getConnection().getServerPlayer(),
                    new ShaderReceivePayload("http://127.0.0.1:1/missing.zip", "0000000000000000000000000000000000000000", "accepted-test")));
            context.waitFor(client -> statuses.contains("DOWNLOAD_FAILED"), 200);
            context.waitTicks(20);
            if (!statuses.equals(List.of("DOWNLOAD_FAILED")))
                throw new AssertionError("A failed download must send one DOWNLOAD_FAILED status: " + statuses);

            try {
                var name = "shaderapi-escape-" + UUID.randomUUID();
                var gameDir = context.computeOnClient(client -> client.gameDirectory.toPath());
                var outside = gameDir.resolve("downloads/../../" + name + ".zip").normalize();
                var wrongHash = "1111111111111111111111111111111111111111";
                var cached = gameDir.resolve("downloads/" + wrongHash + ".zip");
                var validHash = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";
                var validCache = gameDir.resolve("downloads/" + validHash + ".zip");
                if (Files.exists(outside) || Files.exists(cached) || Files.exists(validCache))
                    throw new AssertionError("Test destination already exists");
                var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                var requests = new AtomicInteger();
                http.createContext("/pack", exchange -> {
                    requests.incrementAndGet();
                    byte[] data = "test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, data.length);
                    try (var body = exchange.getResponseBody()) { body.write(data); }
                });
                http.start();
                try {
                    statuses.clear();
                    var url = "http://127.0.0.1:" + http.getAddress().getPort() + "/pack";
                    world.getServer().runOnServer(server -> ServerPlayNetworking.send(world.getConnection().getServerPlayer(),
                            new ShaderReceivePayload(url, "../../" + name, "accepted-test")));
                    context.waitFor(client -> !statuses.isEmpty(), 200);
                    context.waitTicks(10);
                    if (Files.exists(outside) || !statuses.equals(List.of("DOWNLOAD_FAILED")))
                        throw new AssertionError("An unsafe hash must not write outside the download directory: " + statuses);

                    statuses.clear();
                    world.getServer().runOnServer(server -> ServerPlayNetworking.send(world.getConnection().getServerPlayer(),
                            new ShaderReceivePayload(url, wrongHash, "accepted-test")));
                    context.waitFor(client -> !statuses.isEmpty(), 200);
                    context.waitTicks(10);
                    if (Files.exists(cached) || !statuses.equals(List.of("DOWNLOAD_FAILED")))
                        throw new AssertionError("A mismatched download must not be cached or applied: " + statuses);

                    statuses.clear();
                    world.getServer().runOnServer(server -> ServerPlayNetworking.send(world.getConnection().getServerPlayer(),
                            new ShaderReceivePayload(url, validHash, "accepted-test")));
                    context.waitFor(client -> !statuses.isEmpty(), 200);
                    if (requests.get() != 2 || !Files.readString(validCache).equals("test") || !statuses.equals(List.of("APPLY_FAILED")))
                        throw new AssertionError("A matching download must be cached before Iris is invoked: " + statuses);

                    Files.writeString(validCache, "corrupt");
                    statuses.clear();
                    world.getServer().runOnServer(server -> ServerPlayNetworking.send(world.getConnection().getServerPlayer(),
                            new ShaderReceivePayload(url, validHash, "accepted-test")));
                    context.waitFor(client -> !statuses.isEmpty(), 200);
                    if (requests.get() != 3 || !Files.readString(validCache).equals("test") || !statuses.equals(List.of("APPLY_FAILED")))
                        throw new AssertionError("A corrupt cached file must be replaced by a verified download: " + statuses);
                } finally {
                    http.stop(0);
                    Files.deleteIfExists(outside);
                    Files.deleteIfExists(cached);
                    Files.deleteIfExists(validCache);
                }
            } catch (java.io.IOException e) {
                throw new AssertionError("Could not run download fixture", e);
            }
        }
    }
}
