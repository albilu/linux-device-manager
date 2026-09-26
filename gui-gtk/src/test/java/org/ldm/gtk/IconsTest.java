package org.ldm.gtk;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.gnome.gdk.Display;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.IconTheme;
import org.gnome.gtk.TextDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.ldm.core.model.*;

class IconsTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void symbolicOnlyCategoriesAndGenericFallbackResolveToRealFiles(boolean xappNames, @TempDir Path root) throws Exception {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");
        Path themeRoot = Files.createDirectories(root.resolve("Sparse"));
        Files.writeString(themeRoot.resolve("index.theme"), """
                [Icon Theme]
                Name=Sparse
                Directories=scalable/devices
                [scalable/devices]
                Size=16
                Type=Scalable
                MinSize=1
                MaxSize=256
                Context=Devices
                """);
        Path icons = Files.createDirectories(themeRoot.resolve("scalable/devices"));
        String prefix = xappNames ? "xsi-" : "";
        for (String name : new String[] {prefix + "cpu-symbolic", prefix + "bluetooth-symbolic", "computer"}) {
            Files.writeString(icons.resolve(name + ".svg"), """
                    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16">
                      <rect x="2" y="2" width="12" height="12" fill="#555"/>
                    </svg>
                    """);
        }
        IconTheme theme = new IconTheme();
        theme.setSearchPath(new String[] {root.toString()});
        theme.setResourcePath(new String[0]);
        theme.setThemeName("Sparse");
        assertFalse(theme.hasIcon("cpu"));
        assertFalse(theme.hasIcon("bluetooth"));
        for (var entry : Map.of(DeviceCategory.PROCESSOR, prefix + "cpu-symbolic",
                DeviceCategory.BLUETOOTH, prefix + "bluetooth-symbolic", DeviceCategory.DISPLAY, "computer").entrySet()) {
            String name = Icons.categoryIcon(entry.getKey(), theme);
            assertEquals(entry.getValue(), name);
            var icon = theme.lookupIcon(name, null, 16, 1, TextDirection.LTR);
            assertNotNull(icon.getFile());
            assertEquals(icons.resolve(name + ".svg").toUri().toString(), icon.getFile().getUri());
        }
    }

    @Test
    void categoryAndStateIconsExistInTheActiveTheme() {
        assumeTrue(Gtk.initCheck(), "no display available for GTK");
        IconTheme theme = IconTheme.getForDisplay(Display.getDefault());
        for (DeviceCategory category : DeviceCategory.values()) {
            assertTrue(theme.hasIcon(Icons.categoryIcon(category)), category.toString());
            for (DeviceState state : DeviceState.values()) {
                Device device = new Device("/sys/fixture", "/sys/fixture", "fixture", Bus.OTHER,
                        "Fixture", "", "", category, state, Optional.empty(), Map.of());
                String name = Icons.deviceIcon(device);
                assertNotEquals("image-missing", name, category + " " + state);
                assertTrue(theme.hasIcon(name), category + " " + state);
            }
        }
    }
}
