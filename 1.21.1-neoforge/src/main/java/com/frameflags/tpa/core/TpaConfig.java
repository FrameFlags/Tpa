package com.frameflags.tpa.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Config for TPA requests. Java 8 source; copied verbatim into every cell. */
public final class TpaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int expirySeconds = 60;
    public int maxOutgoing = 5;

    public static TpaConfig load(Path file) {
        if (!Files.exists(file)) {
            TpaConfig config = new TpaConfig();
            config.save(file);
            return config;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            TpaConfig config = GSON.fromJson(json, TpaConfig.class);
            return config == null ? new TpaConfig() : config;
        } catch (IOException | RuntimeException e) {
            return new TpaConfig();
        }
    }

    public void save(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ignored) {
            // In-memory config still applies for this session.
        }
    }
}
