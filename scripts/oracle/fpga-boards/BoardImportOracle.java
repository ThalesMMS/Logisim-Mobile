import com.cburch.logisim.fpga.data.BoardInformation;
import com.cburch.logisim.fpga.data.DriveStrength;
import com.cburch.logisim.fpga.data.FpgaIoInformationContainer;
import com.cburch.logisim.fpga.data.IoStandards;
import com.cburch.logisim.fpga.data.PinActivity;
import com.cburch.logisim.fpga.data.PullBehaviors;
import com.cburch.logisim.fpga.file.BoardReaderClass;
import com.cburch.logisim.fpga.settings.VendorSoftware;
import dev.logisim.mobile.core.FpgaBoardCodec;
import dev.logisim.mobile.core.FpgaBoardDefinition;
import dev.logisim.mobile.core.FpgaBoardImportResult;
import dev.logisim.mobile.core.FpgaBoardPin;
import dev.logisim.mobile.core.FpgaBoardResource;
import dev.logisim.mobile.core.FpgaElectrical;
import dev.logisim.mobile.core.FpgaPinDirection;
import dev.logisim.mobile.core.FpgaPull;
import dev.logisim.mobile.core.FpgaVendor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/** Compares physical board metadata read by the desktop and mobile importers. */
class BoardImportOracle {
    private static int comparisons;
    private static int resources;
    private static int pins;
    private static int scanningResources;
    private static int placeholders;

    private static void equal(String field, Object expected, Object actual) {
        comparisons++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(field + ": desktop=" + expected + ", mobile=" + actual);
        }
    }

    private static FpgaPull pull(char value) {
        return switch (value) {
            case PullBehaviors.PULL_UP -> FpgaPull.UP;
            case PullBehaviors.PULL_DOWN -> FpgaPull.DOWN;
            default -> FpgaPull.FLOAT;
        };
    }

    private static FpgaVendor vendor(char value) {
        return switch (value) {
            case VendorSoftware.VENDOR_ALTERA -> FpgaVendor.ALTERA;
            case VendorSoftware.VENDOR_XILINX -> FpgaVendor.XILINX;
            case VendorSoftware.VENDOR_VIVADO -> FpgaVendor.VIVADO;
            case VendorSoftware.VENDOR_OPENFPGA -> FpgaVendor.OPEN_FPGA;
            default -> FpgaVendor.UNKNOWN;
        };
    }

    private static void electrical(String field, String standard, char pullBehavior,
            Integer drive, boolean activeLow, FpgaElectrical actual) {
        equal(field + ".ioStandard", standard, actual.getIoStandard());
        equal(field + ".pull", pull(pullBehavior), actual.getPull());
        equal(field + ".driveMilliamps", drive, actual.getDriveMilliamps());
        equal(field + ".activeLow", activeLow, actual.getActiveLow());
    }

    private static FpgaPinDirection direction(FpgaIoInformationContainer component, int pin) {
        if (component.getExternalPinCount() > 0) return FpgaPinDirection.OUTPUT;
        if (component.hasInputs() && component.getInputs().contains(pin)) return FpgaPinDirection.INPUT;
        if (component.hasOutputs() && component.getOutputs().contains(pin)) return FpgaPinDirection.OUTPUT;
        if (component.hasIoPins() && component.getIos().contains(pin)) return FpgaPinDirection.INOUT;
        throw new AssertionError("Desktop component has no direction for physical pin " + pin);
    }

    private static void resource(String boardName, int index, FpgaIoInformationContainer expected,
            FpgaBoardResource actual) {
        String field = boardName + ".resources[" + index + "]";
        equal(field + ".id", "resource-" + index, actual.getId());
        equal(field + ".type", expected.getType().name().toLowerCase(Locale.ROOT),
                actual.getType().toLowerCase(Locale.ROOT));
        equal(field + ".label", Objects.requireNonNullElse(expected.getLabel(), ""), actual.getLabel());
        var rectangle = expected.getRectangle();
        if (rectangle == null || actual.getRectangle() == null) {
            equal(field + ".rectangle", rectangle, actual.getRectangle());
        } else {
            equal(field + ".rectangle.x", rectangle.getXpos(), actual.getRectangle().getX());
            equal(field + ".rectangle.y", rectangle.getYpos(), actual.getRectangle().getY());
            equal(field + ".rectangle.width", rectangle.getWidth(), actual.getRectangle().getWidth());
            equal(field + ".rectangle.height", rectangle.getHeight(), actual.getRectangle().getHeight());
        }
        int externalPins = expected.getExternalPinCount();
        int physicalPins = externalPins > 0 ? externalPins : expected.getNrOfPins();
        equal(field + ".physicalPinCount", physicalPins, actual.getPins().size());
        if (physicalPins == 0) placeholders++;
        String driveString = DriveStrength.getDriveString(expected.getDrive());
        Integer drive = driveString == null ? null : Integer.valueOf(driveString);
        for (int pin = 0; pin < physicalPins; pin++) {
            FpgaBoardPin actualPin = actual.getPins().get(pin);
            String pinField = field + ".pins[" + pin + "]";
            equal(pinField + ".resourceId", actual.getId(), actualPin.getResourceId());
            equal(pinField + ".index", pin, actualPin.getIndex());
            equal(pinField + ".location", expected.getPinLocation(pin), actualPin.getLocation());
            equal(pinField + ".direction", direction(expected, pin), actualPin.getDirection());
            electrical(pinField + ".electrical", IoStandards.getIoString(expected.getIoStandard()),
                    expected.getPullBehavior(), drive,
                    expected.getActivityLevel() == PinActivity.ACTIVE_LOW, actualPin.getElectrical());
            pins++;
        }
        if (externalPins > 0) scanningResources++;
        resources++;
    }

    private static void board(Path fixture) throws Exception {
        BoardInformation desktop = new BoardReaderClass(fixture.toAbsolutePath().toString())
                .getBoardInformation();
        if (desktop == null) throw new AssertionError("Desktop rejected " + fixture);
        var result = FpgaBoardCodec.INSTANCE.read(Files.readString(fixture));
        if (!(result instanceof FpgaBoardImportResult.Success success)) {
            throw new AssertionError("Mobile rejected " + fixture + ": " + result);
        }
        FpgaBoardDefinition mobile = success.getBoard();
        String name = fixture.getFileName().toString();
        equal(name + ".name", desktop.getBoardName(), mobile.getName());
        var expectedDevice = desktop.fpga;
        var actualDevice = mobile.getDevice();
        equal(name + ".device.vendor", vendor(expectedDevice.getVendor()), actualDevice.getVendor());
        equal(name + ".device.family", expectedDevice.getTechnology(), actualDevice.getFamily());
        equal(name + ".device.part", expectedDevice.getPart(), actualDevice.getPart());
        equal(name + ".device.packageName", expectedDevice.getPackage(), actualDevice.getPackageName());
        equal(name + ".device.speedGrade", expectedDevice.getSpeedGrade(), actualDevice.getSpeedGrade());
        equal(name + ".device.jtagPosition", expectedDevice.getFpgaJTAGChainPosition(), actualDevice.getJtagPosition());
        equal(name + ".device.flashPosition", expectedDevice.getFlashJTAGChainPosition(), actualDevice.getFlashPosition());
        equal(name + ".device.flashName", Objects.requireNonNullElse(expectedDevice.getFlashName(), ""), actualDevice.getFlashName());
        equal(name + ".device.usbTmc", expectedDevice.isUsbTmcDownloadRequired(), actualDevice.getUsbTmc());
        equal(name + ".unusedPinPull", pull(expectedDevice.getUnusedPinsBehavior()), mobile.getUnusedPinPull());
        if (mobile.getClock() == null) throw new AssertionError("Mobile omitted the clock for " + name);
        equal(name + ".clock.location", expectedDevice.getClockPinLocation(), mobile.getClock().getLocation());
        equal(name + ".clock.frequencyHz", expectedDevice.getClockFrequency(), mobile.getClock().getFrequencyHz());
        electrical(name + ".clock.electrical", IoStandards.getIoString(expectedDevice.getClockStandard()),
                expectedDevice.getClockPull(), null, false, mobile.getClock().getElectrical());
        var expectedResources = desktop.getAllComponents();
        equal(name + ".resourceCount", expectedResources.size(), mobile.getResources().size());
        var expectedWarnings = new ArrayList<String>();
        for (int i = 0; i < expectedResources.size(); i++) {
            var expected = expectedResources.get(i);
            resource(name, i, expected, mobile.getResources().get(i));
            if (expected.getExternalPinCount() == 0 && expected.getNrOfPins() == 0) {
                expectedWarnings.add("fpga_core_resource_without_pins:resource-" + i);
            }
        }
        var warnings = success.getWarningNotices().stream()
                .map(notice -> notice.getKey() + ":" + notice.getArguments().get("resource"))
                .toList();
        equal(name + ".warnings", expectedWarnings, warnings);
        System.out.println("PASS " + name + ": " + mobile.getResources().size()
                + " resources, " + mobile.getPins().size() + " physical pins, "
                + expectedWarnings.size() + " placeholders");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Pass at least one board XML fixture");
        for (String fixture : args) board(Path.of(fixture));
        System.out.println("PASS " + comparisons + " desktop/mobile board-field comparisons across "
                + args.length + " boards, " + resources + " resources, " + pins
                + " physical pins, including " + scanningResources + " scanning resources and "
                + placeholders + " placeholders");
    }
}
