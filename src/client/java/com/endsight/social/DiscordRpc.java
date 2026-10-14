package com.endsight.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal local Discord IPC connection. It never authenticates a bot or calls Discord's web API. */
final class DiscordRpc implements AutoCloseable {
    private static final int HANDSHAKE = 0, FRAME = 1, CLOSE = 2, PING = 3, PONG = 4;
    private static final int MAX_FRAME = 1_048_576;
    private final RandomAccessFile pipe;
    private volatile boolean open = true;

    private DiscordRpc(RandomAccessFile pipe, String applicationId) throws IOException {
        this.pipe = pipe;
        JsonObject hello = new JsonObject();
        hello.addProperty("v", 1);
        hello.addProperty("client_id", applicationId);
        write(HANDSHAKE, hello.toString().getBytes(StandardCharsets.UTF_8));
        Packet ready = read();
        if (ready.opcode != FRAME || !"READY".equals(event(ready.payload)))
            throw new IOException("Discord did not accept the Rich Presence handshake");
    }

    static DiscordRpc connect(String applicationId) throws IOException {
        IOException last = null;
        for (int i = 0; i < 10; i++) {
            try {
                RandomAccessFile file = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
                try {
                    return new DiscordRpc(file, applicationId);
                } catch (IOException | RuntimeException e) {
                    file.close();
                    throw e;
                }
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("Discord desktop IPC is unavailable", last);
    }

    boolean isOpen() { return open; }

    void activity(String details, String state, long since, String logoUrl, String inviteUrl) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("cmd", "SET_ACTIVITY");
        String nonce = UUID.randomUUID().toString();
        request.addProperty("nonce", nonce);
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (details != null) {
            JsonObject activity = new JsonObject();
            // The game stays in the member list while details identify the player.
            activity.addProperty("name", "Dragsim");
            activity.addProperty("type", 0);
            activity.addProperty("status_display_type", 0);
            activity.addProperty("details", details);
            activity.addProperty("state", state);
            JsonObject timestamps = new JsonObject();
            timestamps.addProperty("start", since);
            activity.add("timestamps", timestamps);
            JsonObject assets = new JsonObject();
            assets.addProperty("large_image", logoUrl);
            assets.addProperty("large_text", "Powered by Endsight");
            assets.addProperty("large_url", inviteUrl);
            activity.add("assets", assets);
            JsonObject join = new JsonObject();
            join.addProperty("label", "Join Endsight Discord");
            join.addProperty("url", inviteUrl);
            JsonArray buttons = new JsonArray();
            buttons.add(join);
            activity.add("buttons", buttons);
            args.add("activity", activity);
        }
        request.add("args", args);
        write(FRAME, request.toString().getBytes(StandardCharsets.UTF_8));
        // Windows' synchronous pipe handle cannot read and write concurrently.
        // Consume this request's reply before sending the next update.
        while (open) {
            Packet packet = read();
            if (packet.opcode == PING) {
                write(PONG, packet.payload);
                continue;
            }
            if (packet.opcode == CLOSE) throw new IOException("Discord closed the IPC connection");
            if (packet.opcode != FRAME) continue;
            JsonObject response;
            try {
                response = JsonParser.parseString(new String(packet.payload, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                continue;
            }
            if (!response.has("nonce") || !nonce.equals(response.get("nonce").getAsString())) continue;
            if ("ERROR".equals(event(packet.payload)))
                throw new IOException("Discord rejected the Rich Presence update: " + response);
            return;
        }
        throw new IOException("Discord IPC closed");
    }

    private static String event(byte[] payload) {
        try {
            JsonObject message = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
            return message.has("evt") && !message.get("evt").isJsonNull() ? message.get("evt").getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private record Packet(int opcode, byte[] payload) { }

    private Packet read() throws IOException {
        int opcode = Integer.reverseBytes(pipe.readInt());
        int length = Integer.reverseBytes(pipe.readInt());
        if (length < 0 || length > MAX_FRAME) throw new IOException("Invalid Discord IPC frame length");
        byte[] payload = new byte[length];
        pipe.readFully(payload);
        return new Packet(opcode, payload);
    }

    private void write(int opcode, byte[] payload) throws IOException {
        if (!open) throw new IOException("Discord IPC closed");
        // Discord's native client sends each frame as one pipe write.
        byte[] frame = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(opcode).putInt(payload.length).put(payload).array();
        pipe.write(frame);
    }

    @Override public void close() {
        open = false;
        try { pipe.close(); } catch (IOException ignored) { }
    }
}
