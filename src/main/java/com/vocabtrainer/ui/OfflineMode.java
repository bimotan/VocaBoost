package com.vocabtrainer.ui;

import com.vocabtrainer.service.SettingsService;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tooltip;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline mode as one switch that several check boxes show, the header's and the Settings tab's.
 * Turning any of them saves the setting and publishes {@link DataChange#SETTINGS}, and the others
 * follow; the dictionary and AI services read the setting at every request, so it applies at once.
 * A setting that cannot be saved is reported and every box goes back to the saved state.
 */
public final class OfflineMode {
    /** What offline mode does, for the boxes' tooltips and the Settings tab. */
    public static final String DESCRIPTION = "No online dictionary lookups and no AI requests: only the local"
        + " dictionaries, cached lookups and the offline mock explanation are used.";

    private final ViewContext context;
    private final SettingsService settingsService;
    private final List<CheckBox> boxes = new ArrayList<>();
    /** Set while the boxes are made to show the saved setting, so that is not saved again. */
    private boolean showingSaved;

    public OfflineMode(ViewContext context, SettingsService settingsService) {
        this.context = context;
        this.settingsService = settingsService;
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.SETTINGS)) {
                showSaved();
            }
        });
    }

    /** A check box with {@code text} and {@code id} that shows offline mode and switches it. */
    public CheckBox checkBox(String id, String text) {
        CheckBox box = new CheckBox(text);
        box.setId(id);
        box.setTooltip(new Tooltip(DESCRIPTION));
        box.setSelected(settingsService.isOfflineMode());
        box.selectedProperty().addListener((observable, wasOffline, offline) -> {
            if (!showingSaved) {
                switchTo(offline);
            }
        });
        boxes.add(box);
        return box;
    }

    private void switchTo(boolean offline) {
        try {
            settingsService.saveOfflineMode(offline);
        } catch (RuntimeException e) {
            context.errors().reportFailure("Switching offline mode failed", e);
            showSaved();
            return;
        }
        showSaved();
        context.errors().guard("Offline mode switched, but refreshing the views failed",
            () -> context.changes().publish(DataChange.SETTINGS));
    }

    private void showSaved() {
        boolean offline = settingsService.isOfflineMode();
        showingSaved = true;
        try {
            boxes.forEach(box -> box.setSelected(offline));
        } finally {
            showingSaved = false;
        }
    }
}
