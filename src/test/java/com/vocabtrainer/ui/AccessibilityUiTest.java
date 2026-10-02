package com.vocabtrainer.ui;

import javafx.scene.Node;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screen readers can name every input, and the form labels' Alt shortcuts (mnemonics) are unique
 * among the labels shown together: the header and one tab, or one dialog.
 */
@Tag("ui")
class AccessibilityUiTest extends MainWindowUiTest {
    private static final List<String> TABS = List.of("dashboardTab", "decksTab", "reviewTab", "addImportTab",
        "statisticsTab", "wordListTab", "settingsTab");

    @Test
    void everyInputInTheWindowHasALabelOrAnAccessibleName() {
        List<String> unnamed = Fx.call(() -> unnamedInputs(windowNodes()));
        assertTrue(unnamed.isEmpty(), "inputs without a label or accessible text: " + unnamed);
    }

    @Test
    void mnemonicsAreUniqueOnEachTabTogetherWithTheHeader() {
        Fx.run(() -> {
            List<Node> header = new ArrayList<>(windowNodes());
            TABS.forEach(tab -> header.removeAll(tabNodes(tab)));
            for (String tab : TABS) {
                List<Node> shownTogether = new ArrayList<>(header);
                shownTogether.addAll(tabNodes(tab));
                assertUniqueMnemonics(tab, shownTogether);
            }
        });
        // The forms that have labels use them.
        assertTrue(Fx.call(() -> mnemonics(tabNodes("addImportTab")).keySet())
            .containsAll(Set.of("k", "e", "c", "p", "o", "t", "x", "n")));
        assertTrue(Fx.call(() -> mnemonics(tabNodes("settingsTab")).keySet())
            .containsAll(Set.of("r", "s", "w", "x", "p", "b", "k", "m", "t")));
        assertEquals(Set.of("m", "s", "n"), Fx.call(() -> mnemonics(tabNodes("reviewTab")).keySet()));
    }

    @Test
    void theEditWordAndGoalsFormsLabelEveryField() {
        selectTab("wordListTab");
        Fx.run(() -> this.<Object>table("wordTable").getSelectionModel().select(0));
        dialogs.submitForm(form -> checkForm("Edit word", form));
        click("editWordButton");

        selectTab("dashboardTab");
        dialogs.submitForm(form -> checkForm("Edit goals", form));
        click("editGoalsButton");
    }

    private static void checkForm(String title, Node form) {
        List<Node> nodes = new ArrayList<>(form.lookupAll("*"));
        List<String> unnamed = unnamedInputs(nodes);
        assertTrue(unnamed.isEmpty(), title + ": " + unnamed);
        assertUniqueMnemonics(title, nodes);
    }

    private static List<String> unnamedInputs(List<Node> nodes) {
        Set<Node> labelled = nodes.stream()
            .filter(node -> node instanceof Label label && label.getLabelFor() != null)
            .map(node -> ((Label) node).getLabelFor())
            .collect(Collectors.toSet());
        List<String> unnamed = new ArrayList<>();
        for (Node node : nodes) {
            boolean input = node instanceof TextInputControl || node instanceof ComboBoxBase<?>
                || node instanceof Spinner<?> || node instanceof Slider || node instanceof ListView<?>
                || node instanceof TableView<?>;
            // A spinner's or combo box's own editor belongs to it, not to the form.
            boolean part = node.getParent() != null && node.getParent().getStyleClass().contains("spinner");
            String name = node.getAccessibleText();
            if (input && !part && !labelled.contains(node) && (name == null || name.isBlank())) {
                unnamed.add(node.getId() == null ? node.toString() : "#" + node.getId());
            }
        }
        return unnamed;
    }

    private static void assertUniqueMnemonics(String where, List<Node> nodes) {
        Map<String, List<String>> byKey = new HashMap<>();
        for (Node node : nodes) {
            if (node instanceof Label label && label.isMnemonicParsing()) {
                mnemonic(label.getText()).ifPresent(key -> byKey.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(label.getText()));
            }
        }
        byKey.forEach((key, labels) -> assertEquals(1, labels.size(), where + ": Alt+" + key + " is used by " + labels));
    }

    private static Map<String, String> mnemonics(List<Node> nodes) {
        Map<String, String> byKey = new HashMap<>();
        for (Node node : nodes) {
            if (node instanceof Label label && label.isMnemonicParsing()) {
                mnemonic(label.getText()).ifPresent(key -> byKey.put(key, label.getText()));
            }
        }
        return byKey;
    }

    private static java.util.Optional<String> mnemonic(String text) {
        int underscore = text == null ? -1 : text.indexOf('_');
        return underscore < 0 || underscore + 1 >= text.length() ? java.util.Optional.empty()
            : java.util.Optional.of(text.substring(underscore + 1, underscore + 2).toLowerCase(Locale.ROOT));
    }
}
