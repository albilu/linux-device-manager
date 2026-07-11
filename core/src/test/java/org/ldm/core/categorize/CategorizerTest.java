package org.ldm.core.categorize;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ldm.core.model.Bus;
import org.ldm.core.model.DeviceCategory;
import org.ldm.core.model.SysfsDevice;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CategorizerTest {

    private final Categorizer categorizer = new Categorizer();

    private SysfsDevice pci(int classCode) {
        return new SysfsDevice("/sys/x", "x", Bus.PCI, "0", "0", classCode,
                Optional.empty(), Optional.empty(), Map.of());
    }

    private SysfsDevice usb(int ifaceClass) {
        return new SysfsDevice("/sys/x", "x", Bus.USB, "0", "0", ifaceClass,
                Optional.empty(), Optional.empty(), Map.of());
    }

    @Test
    void pciDisplayController() {
        assertEquals(DeviceCategory.DISPLAY, categorizer.categorize(pci(0x030000)));
    }

    @Test
    void pciNetworkController() {
        assertEquals(DeviceCategory.NETWORK, categorizer.categorize(pci(0x028000)));
    }

    @Test
    void pciMassStorageIsStorage() {
        assertEquals(DeviceCategory.STORAGE, categorizer.categorize(pci(0x010802)));
    }

    @Test
    void pciUsbControllerIsUsbNotSerialBus() {
        assertEquals(DeviceCategory.USB, categorizer.categorize(pci(0x0c0330)));
    }

    @Test
    void pciSerialBusNonUsb() {
        assertEquals(DeviceCategory.SERIAL_BUS, categorizer.categorize(pci(0x0c0500)));
    }

    @Test
    void pciBluetoothWireless() {
        assertEquals(DeviceCategory.BLUETOOTH, categorizer.categorize(pci(0x0d1100)));
    }

    @Test
    void pciOtherWirelessIsNetwork() {
        assertEquals(DeviceCategory.NETWORK, categorizer.categorize(pci(0x0d8000)));
    }

    @Test
    void pciUnknownClassIsOther() {
        assertEquals(DeviceCategory.OTHER, categorizer.categorize(pci(0xff0000)));
    }

    @Test
    void usbHidIsInput() {
        assertEquals(DeviceCategory.INPUT, categorizer.categorize(usb(0x03)));
    }

    @Test
    void usbVideoIsImaging() {
        assertEquals(DeviceCategory.IMAGING, categorizer.categorize(usb(0x0e)));
    }

    @Test
    void usbAudioIsMultimedia() {
        assertEquals(DeviceCategory.MULTIMEDIA, categorizer.categorize(usb(0x01)));
    }

    @Test
    void usbHubIsUsb() {
        assertEquals(DeviceCategory.USB, categorizer.categorize(usb(0x09)));
    }

    @Test
    void usbVendorSpecificIsOther() {
        assertEquals(DeviceCategory.OTHER, categorizer.categorize(usb(0xff)));
    }

    @Test
    void busOtherIsOther() {
        SysfsDevice d = new SysfsDevice("/sys/x", "x", Bus.OTHER, "0", "0", 0,
                Optional.empty(), Optional.empty(), Map.of());
        assertEquals(DeviceCategory.OTHER, categorizer.categorize(d));
    }

    @Test
    void usbWirelessControllerIsBluetooth() {
        assertEquals(DeviceCategory.BLUETOOTH, categorizer.categorize(usb(0xe0)));
    }

    @Test
    void pciUnclassifiedIsSystem() {
        assertEquals(DeviceCategory.SYSTEM, categorizer.categorize(pci(0x000000)));
    }
}
