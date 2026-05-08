package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class RTPer extends Module {

    public enum RTPMode {
        COORDINATES("Coordinates"),
        BIOME("Biome");

        private final String displayName;

        RTPMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum RTPRegion {
        ASIA("asia"),
        EAST("east"),
        EU_CENTRAL("eu central"),
        EU_WEST("eu west"),
        OCEANIA("oceania"),
        WEST("west"),
        NETHER("nether"),
        END("end");

        private final String commandPart;

        RTPRegion(String commandPart) {
            this.commandPart = commandPart;
        }

        public String getCommandPart() {
            return commandPart;
        }
    }

    public enum MinecraftBiome {
        // unchanged (removed for brevity in explanation, same as original)
        PLAINS("Plains", "minecraft:plains"),
        SUNFLOWER_PLAINS("Sunflower Plains", "minecraft:sunflower_plains"),
        SNOWY_PLAINS("Snowy Plains", "minecraft:snowy_plains"),
        FOREST("Forest", "minecraft:forest"),
        FLOWER_FOREST("Flower Forest", "minecraft:flower_forests"),
        // ... keep ALL your biome entries exactly the same
        CHERRY_GROVE("Cherry Grove", "minecraft:cherry_grove");

        private final String displayName;
        private final String id;

        MinecraftBiome(String displayName, String id) {
            this.displayName = displayName;
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getId() {
            return id;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    // CHANGED: GlazedAddon -> EZxAddon reference string (safe rename placeholder)
    public RTPer() {
        super(EZxAddon.CATEGORY, "rtper", "RTP to specific coordinates or find specific biomes.");
    }

    @Override
    public void onActivate() {
        tickTimer = 0;
        isRtping = false;
        rtpAttempts = 0;
        lastRtpPos = null;
        lastReportedDistance = -1;
        biomeFound = false;

        if (rtpMode.get() == RTPMode.COORDINATES) {
            targetDistanceBlocks = parseDistance();
        }

        if (mc.player == null) return;

        if (rtpMode.get() == RTPMode.COORDINATES) {
            double currentDist = getCurrentDistance();
            if (notifications.get()) info("EZx RTPer started - target: (%d, %d)", targetX.get(), targetZ.get());
            if (notifications.get()) info("Distance: %s -> %d blocks", distance.get(), targetDistanceBlocks);
            if (notifications.get()) info("Current: %.1f blocks away", currentDist);

            if (currentDist <= targetDistanceBlocks) {
                if (notifications.get()) info("Already close enough!");
                toggle();
            }
        } else {
            if (notifications.get()) info("EZx RTPer started - Biome Finder mode");
            if (notifications.get()) info("Target biome: %s", targetBiome.get().getDisplayName());
        }
    }

    @Override
    public void onDeactivate() {
        if (rtpMode.get() == RTPMode.COORDINATES) {
            if (notifications.get()) info("Stopped after %d attempts", rtpAttempts);
        } else {
            if (notifications.get()) info("Biome finder stopped after %d attempts", rtpAttempts);
        }
        isRtping = false;
    }

    private void handleBiomeMode() {
        if (biomeFound) {
            if (notifications.get()) info("Target biome found: %s", targetBiome.get().getDisplayName());

            if (webhookEnabled.get()) {
                sendWebhook("Biome Found!",
                    String.format("Found %s biome in %s!\\nAttempts: %d\\nPosition: %d, %d, %d",
                        targetBiome.get().getDisplayName(), rtpRegion.get().getCommandPart(), rtpAttempts,
                        mc.player.getBlockPos().getX(), mc.player.getBlockPos().getY(), mc.player.getBlockPos().getZ()),
                    0x00FF00);
            }

            if (disconnectOnReach.get()) {
                if (notifications.get()) info("Disconnecting...");
                disconnectWithMessage("EZx: found requested biome");
            }

            toggle();
            return;
        }
    }

    private void sendWebhook(String title, String description, int color) {
        if (!webhookEnabled.get() || webhookUrl.get().isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                String serverInfo = mc.getCurrentServerEntry() != null ?
                    mc.getCurrentServerEntry().address : "Unknown Server";

                String messageContent = "";
                if (selfPing.get() && !discordId.get().trim().isEmpty()) {
                    messageContent = String.format("<@%s>", discordId.get().trim());
                }

                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

                String jsonPayload = String.format("""
                    {
                        "content": "%s",
                        "username": "RTPer Webhook",
                        "avatar_url": "https://i.imgur.com/OL2y1cr.png",
                        "embeds": [{
                            "title": "🎯 EZx RTPer Alert",
                            "description": "%s",
                            "color": %d,
                            "fields": [
                                {
                                    "name": "Status",
                                    "value": "%s",
                                    "inline": true
                                },
                                {
                                    "name": "Server",
                                    "value": "%s",
                                    "inline": true
                                },
                                {
                                    "name": "Time",
                                    "value": "<t:%d:R>",
                                    "inline": true
                                }
                            ],
                            "footer": {
                                "text": "RTPer by TT_EZX1 {juan}"
                            },
                            "timestamp": "%sZ"
                        }]
                    }""",
                    messageContent.replace("\"", "\\\""),
                    description.replace("\"", "\\\"").replace("\\n", "\\n"),
                    color,
                    title.replace("\"", "\\\""),
                    serverInfo.replace("\"", "\\\""),
                    System.currentTimeMillis() / 1000,
                    timestamp);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl.get()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .timeout(Duration.ofSeconds(30))
                    .build();

                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            } catch (Exception ignored) {}
        });
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WTable table = theme.table();

        table.add(theme.label("Biome Picker (EZx):"));
        WLabel current = table.add(theme.label(targetBiome.get().getDisplayName())).expandX().widget();
        WButton open = table.add(theme.button("Select")).widget();
        open.action = () -> {
            if (rtpMode.get() == RTPMode.BIOME) mc.setScreen(new BiomePickerScreen(theme, current));
        };
        table.row();

        return table;
    }

    private class BiomePickerScreen extends WindowScreen {
        private final WLabel currentLabel;

        public BiomePickerScreen(GuiTheme theme, WLabel currentLabel) {
            super(theme, "Select Biome (EZx)");
            this.currentLabel = currentLabel;
        }

        @Override
        public void initWidgets() {
            WTextBox searchBox = add(theme.textBox("")).expandX().widget();
            searchBox.setFocused(true);

            WTable listTable = add(theme.table()).expandX().widget();

            for (MinecraftBiome biome : MinecraftBiome.values()) {
                listTable.add(theme.label(biome.getDisplayName())).expandX();
                WButton select = listTable.add(theme.button("Use")).widget();
                select.action = () -> {
                    targetBiome.set(biome);
                    currentLabel.set(biome.getDisplayName());
                    mc.setScreen(null);
                };
                listTable.row();
            }
        }
    }
}
